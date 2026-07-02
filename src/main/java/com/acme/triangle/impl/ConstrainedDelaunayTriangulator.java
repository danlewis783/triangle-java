package com.acme.triangle.impl;

import com.acme.triangle.Constraint;
import com.acme.triangle.DefaultImmutableTriangle;
import com.acme.triangle.ImmutableTriangle;
import com.acme.triangle.Point;
import com.acme.triangle.Region;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherInput2;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMesherOutput2;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Constrained Delaunay triangulation of a PSLG with holes and regions
 * (phase 2 of the port). Pipeline:
 *
 * <ol>
 *   <li>split any crossing input segments at their intersection (a new vertex);</li>
 *   <li>Delaunay-triangulate all points ({@link DelaunayTriangulator});</li>
 *   <li>recover each segment by flipping the edges it crosses;</li>
 *   <li>restore the Delaunay property on non-segment edges;</li>
 *   <li>carve: flood from outside (across non-segment hull edges) and from each
 *       hole point, removing reached triangles;</li>
 *   <li>attribute: flood from each region seed across non-segment edges.</li>
 * </ol>
 *
 * Steps 3-6 run on a {@link CdtMesh} - a triangle arena with maintained
 * neighbour links - so segment recovery walks the segment's channel locally and
 * every flip is O(1), rather than rebuilding a global edge map and shifting a
 * list per flip (the previous O(T^2) recovery/restoration). All geometric tests
 * use the robust {@link Geometry}. Refinement (quality) is phase 3; this produces
 * the unrefined constrained Delaunay mesh.
 */
public final class ConstrainedDelaunayTriangulator {

    private ConstrainedDelaunayTriangulator() {
    }

    public static TriangleMesherOutput triangulate(TriangleMesherInput in) {
        return triangulate(TriangleMesherInput2.from(in)).toFlat();
    }

    static TriangleMesherOutput2 triangulate(TriangleMesherInput2 in) {
        /* 1. Split crossing segments. */
        Pslg pslg = splitIntersections(in);
        List<Point> pts = pslg.getPoints();

        /* 2. Initial Delaunay of all points. */
        List<Corners> tris = DelaunayTriangulator.triangulate(pts);

        /* 3-6. Recover segments, restore Delaunay, carve, attribute - all on the
           maintained-adjacency arena. */
        CdtMesh mesh = new CdtMesh(pts, tris);
        for (Constraint s : pslg.segments) {
            mesh.recoverSegment(s.getA(), s.getB());
        }
        mesh.restoreDelaunay();
        boolean[] removed = mesh.carve(in.getHoles());
        double[] attr = mesh.attributeRegions(removed, in.getRegions());

        return mesh.buildOutput(removed, attr, pslg);
    }

    /* --- 1. segment intersection splitting ----------------------------------- */

    /**
     * Planar Straight-Line Graph (PSLG) — the normalized input representation
     * used by Triangle and this port.
     * <p>
     * A PSLG is a 2-D graph whose edges are straight line segments that
     * meet only at shared endpoints (no crossings in the interior). It captures
     * the vertex set and the boundary/constraint segments that the triangulation
     * must respect. This internal record holds the post-intersection-split form
     * produced by {@link #splitIntersections}.
     */
    private static final class Pslg {
        private final List<Point> points;
        private final List<Constraint> segments;

        private Pslg(List<Point> points, List<Constraint> segments) {
            this.points = points;
            this.segments = segments;
        }

        private List<Point> getPoints() {
            return points;
        }

        private List<Constraint> getSegments() {
            return segments;
        }
    }

    private static Pslg splitIntersections(TriangleMesherInput2 in) {
        /* Growable working copy: intersection points are appended as crossings
           are resolved (the input store must not be mutated). */
        List<Point> pts = new ArrayList<>(in.getPoints());
        List<Constraint> segs = new ArrayList<>(in.getSegments());

        /* (a) Two segments cross: split both at their intersection point, and
           rescan (rare; crossings only shrink). A crossing split cannot create a
           new crossing - the halves are subsets of their parents - so this
           terminates after the initial crossing count. The bounding-box test
           rejects almost every pair before the exact-arithmetic cross() - on
           polygonal boundaries only neighbouring chords overlap boxes, so the
           O(S^2) scan is all cheap compares. */
        boolean changed = true;
        while (changed) {
            changed = false;
            double[] box = segmentBoxes(pts, segs);
            crossings:
            for (int i = 0; i < segs.size(); i++) {
                for (int j = i + 1; j < segs.size(); j++) {
                    if (box[4 * i] > box[4 * j + 1] || box[4 * j] > box[4 * i + 1]
                            || box[4 * i + 2] > box[4 * j + 3] || box[4 * j + 2] > box[4 * i + 3]) {
                        continue;                   /* disjoint boxes cannot cross */
                    }
                    Constraint a = segs.get(i);
                    Constraint b = segs.get(j);
                    if (share(a, b)) {
                        continue;
                    }
                    if (cross(pts, a.getA(), a.getB(), b.getA(), b.getB())) {
                        Point intersection = intersection(pts, a.getA(), a.getB(), b.getA(), b.getB());
                        pts.add(intersection);
                        int size = pts.size();
                        int c = size - 1;
                        segs.set(i, new Constraint(a.getA(), c, a.getMarker()));
                        segs.set(j, new Constraint(b.getA(), c, b.getMarker()));
                        segs.add(new Constraint(c, a.getB(), a.getMarker()));
                        segs.add(new Constraint(c, b.getB(), b.getMarker()));
                        changed = true;
                        break crossings;
                    }
                }
            }
        }

        /* (b) Vertices lying exactly on a segment (T-junctions): subdivide each
           segment at every interior vertex in one pass, sorted along the
           segment. The whole segment cannot be recovered as one edge with a
           vertex on it, so it becomes a chain of subsegments. One pass suffices:
           subdivision adds no vertices and no new crossings, so nothing here
           creates new T-junction candidates. (The old form re-scanned all S
           segments against all V vertices after every single split - quadratic
           blow-up on lattice inputs whose boundary segments carry hundreds of
           collinear vertices.) */
        int origSegs = segs.size();
        double[] box = segmentBoxes(pts, segs);
        for (int i = 0; i < origSegs; i++) {
            Constraint s = segs.get(i);
            int a = s.getA();
            int b = s.getB();
            Point ptA = pts.get(a);
            Point ptB = pts.get(b);
            double abx = ptB.getX() - ptA.getX();
            double aby = ptB.getY() - ptA.getY();
            List<Integer> chain = null;
            for (int v = 0; v < pts.size(); v++) {
                Point pv = pts.get(v);
                if (pv.getX() < box[4 * i] || pv.getX() > box[4 * i + 1]
                        || pv.getY() < box[4 * i + 2] || pv.getY() > box[4 * i + 3]) {
                    continue;                       /* outside the closed box: not on it */
                }
                if (v != a && v != b && onSegmentInterior(pts, a, b, v)) {
                    if (chain == null) {
                        chain = new ArrayList<>();
                    }
                    chain.add(v);
                }
            }
            if (chain == null) {
                continue;
            }
            /* Order by the projection along a->b (all collinear, so this is the
               true order along the segment). */
            chain.sort(Comparator.comparingDouble(v -> {
                Point pv = pts.get(v);
                return (pv.getX() - ptA.getX()) * abx + (pv.getY() - ptA.getY()) * aby;
            }));
            int prev = a;
            for (int v : chain) {
                if (prev == a) {
                    segs.set(i, new Constraint(a, v, s.getMarker()));
                } else {
                    segs.add(new Constraint(prev, v, s.getMarker()));
                }
                prev = v;
            }
            segs.add(new Constraint(prev, b, s.getMarker()));
        }

        return new Pslg(pts, segs);
    }

    private static boolean share(Constraint a, Constraint b) {
        return a.getA() == b.getA() || a.getA() == b.getB() || a.getB() == b.getA() || a.getB() == b.getB();
    }

    /** Closed bounding boxes per segment: {minX, maxX, minY, maxY} at 4i. */
    private static double[] segmentBoxes(List<Point> pts, List<Constraint> segs) {
        double[] box = new double[4 * segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            Point a = pts.get(segs.get(i).getA());
            Point b = pts.get(segs.get(i).getB());
            box[4 * i] = Math.min(a.getX(), b.getX());
            box[4 * i + 1] = Math.max(a.getX(), b.getX());
            box[4 * i + 2] = Math.min(a.getY(), b.getY());
            box[4 * i + 3] = Math.max(a.getY(), b.getY());
        }
        return box;
    }

    /** True if vertex v is exactly collinear with (a,b) and strictly between them. */
    private static boolean onSegmentInterior(List<Point> pts, int a, int b, int v) {
        if (orient(pts, a, b, v) != 0) {
            return false;
        }
        Point ptA = pts.get(a);
        Point ptB = pts.get(b);
        Point ptV = pts.get(v);
        double pax = ptA.getX();
        double pay = ptA.getY();
        double pbx = ptB.getX();
        double pby = ptB.getY();
        double pvx = ptV.getX();
        double pvy = ptV.getY();
        double toA = (pvx - pax) * (pbx - pax) + (pvy - pay) * (pby - pay);
        double toB = (pvx - pbx) * (pax - pbx) + (pvy - pby) * (pay - pby);
        return toA > 0 && toB > 0;            /* strictly inside, not at an endpoint */
    }

    private static Point intersection(List<Point> pts, int p0, int p1, int q0, int q1) {
        Point pt0 = pts.get(p0);
        double x1 = pt0.getX();
        double y1 = pt0.getY();

        Point pt1 = pts.get(p1);
        double x2 = pt1.getX();
        double y2 = pt1.getY();

        Point pt2 = pts.get(q0);
        double x3 = pt2.getX();
        double y3 = pt2.getY();

        Point pt3 = pts.get(q1);
        double x4 = pt3.getX();
        double y4 = pt3.getY();

        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        return new Point(x1 + t * (x2 - x1), y1 + t * (y2 - y1));
    }

    /* --- 3-6. constrained mesh on a maintained-adjacency arena --------------- */

    /**
     * The construction triangulation with maintained adjacency: parallel arrays
     * of corners and neighbour links (slot {@code i} is triangle {@code i}, edge
     * opposite corner {@code j} faces {@code nbr[3i+j]} or -1), plus one incident
     * triangle per vertex for walking. The triangle count is fixed - flips replace
     * two triangles in place and carving only marks them removed - so no slot is
     * ever added or freed. Built once from the initial Delaunay; from then on every
     * mutation is local.
     */
    private static final class CdtMesh {

        private final List<Point> pts;
        private final int nt;
        private final int[] cor;          /* 3 corner vertex indices per triangle */
        private final int[] nbr;          /* 3 neighbour triangle ids per triangle, -1 on a boundary */
        private final int[] vtri;         /* one incident triangle per vertex, for the rotation walks */
        /* Recovered segment edges: a primitive set, since the
           Delaunay-restoration loop probes it once per popped edge. */
        private final LongOpenHashSet segSet = new LongOpenHashSet(64, Hash.FAST_LOAD_FACTOR);

        CdtMesh(List<Point> pts, List<Corners> tris) {
            this.pts = pts;
            this.nt = tris.size();
            cor = new int[3 * nt];
            for (int i = 0; i < nt; i++) {
                Corners t = tris.get(i);
                cor[3 * i] = t.a;
                cor[3 * i + 1] = t.b;
                cor[3 * i + 2] = t.c;
            }
            nbr = Topology.neighbors(nt, (i, c) -> cor[3 * i + c]);
            vtri = new int[pts.size()];
            for (int i = 0; i < nt; i++) {
                vtri[cor[3 * i]] = i;
                vtri[cor[3 * i + 1]] = i;
                vtri[cor[3 * i + 2]] = i;
            }
        }

        /* --- arena helpers --------------------------------------------------- */

        private int localIndex(int t, int v) {
            return cor[3 * t] == v ? 0 : cor[3 * t + 1] == v ? 1 : 2;
        }

        /** The corner of triangle {@code t} that is neither {@code x} nor {@code y}. */
        private int apex(int t, int x, int y) {
            int c0 = cor[3 * t];
            int c1 = cor[3 * t + 1];
            int c2 = cor[3 * t + 2];
            return (c0 != x && c0 != y) ? c0 : (c1 != x && c1 != y) ? c1 : c2;
        }

        /** The neighbour of {@code t} across edge {@code (x,y)} (opposite the third
            corner), or -1. */
        private int acrossEdge(int t, int x, int y) {
            for (int j = 0; j < 3; j++) {
                int c = cor[3 * t + j];
                if (c != x && c != y) {
                    return nbr[3 * t + j];
                }
            }
            return -1;
        }

        /** Set the neighbour of {@code t} across edge {@code (x,y)} to {@code value}. */
        private void setNeighborAcross(int t, int x, int y, int value) {
            for (int j = 0; j < 3; j++) {
                int c = cor[3 * t + j];
                if (c != x && c != y) {
                    nbr[3 * t + j] = value;
                    return;
                }
            }
        }

        /** Store {@code (a,b,c)} into slot {@code s}, oriented counterclockwise. */
        private void setCorCcw(int s, int a, int b, int c) {
            if (orient(pts, a, b, c) >= 0) {
                cor[3 * s] = a;
                cor[3 * s + 1] = b;
                cor[3 * s + 2] = c;
            } else {
                cor[3 * s] = a;
                cor[3 * s + 1] = c;
                cor[3 * s + 2] = b;
            }
        }

        /**
         * Flip the edge {@code (u,v)} shared by {@code t1} (apex {@code p}) and
         * {@code t2} (apex {@code q}) to the diagonal {@code (p,q)}, in place:
         * {@code t1} becomes the triangle on {@code v}'s side, {@code t2} the one on
         * {@code u}'s side, and the four outer neighbours are repointed.
         */
        private void flip(int t1, int t2, int u, int v, int p, int q) {
            int nVp = acrossEdge(t1, v, p);
            int nPu = acrossEdge(t1, p, u);
            int nVq = acrossEdge(t2, v, q);
            int nQu = acrossEdge(t2, q, u);
            setCorCcw(t1, p, v, q);                 /* the half containing v */
            setCorCcw(t2, q, u, p);                 /* the half containing u */
            setNeighborAcross(t1, p, v, nVp);
            setNeighborAcross(t1, v, q, nVq);
            setNeighborAcross(t1, q, p, t2);        /* the new diagonal */
            setNeighborAcross(t2, q, u, nQu);
            setNeighborAcross(t2, u, p, nPu);
            setNeighborAcross(t2, p, q, t1);
            if (nVp >= 0) {
                setNeighborAcross(nVp, v, p, t1);
            }
            if (nVq >= 0) {
                setNeighborAcross(nVq, v, q, t1);
            }
            if (nQu >= 0) {
                setNeighborAcross(nQu, q, u, t2);
            }
            if (nPu >= 0) {
                setNeighborAcross(nPu, u, p, t2);
            }
            vtri[v] = t1;
            vtri[u] = t2;
            vtri[p] = t1;
            vtri[q] = t1;
        }

        /* --- 3. segment recovery (Sloan's channel retriangulation) ----------- */

        /**
         * Make {@code (a,b)} an edge by retriangulating the channel of edges it
         * crosses (Sloan 1993): collect the crossing edges, then repeatedly take
         * one - if its quad is convex, flip it (re-queuing the new diagonal when it
         * still crosses), otherwise re-queue it to retry once neighbouring flips
         * have made it convex - until none remains. A convex crossing (a channel
         * "ear") always exists, so this terminates. Then register the subsegment.
         */
        void recoverSegment(int a, int b) {
            List<int[]> channel = new ArrayList<>();
            if (!collectChannel(a, b, channel)) {
                collectChannelGlobal(a, b, channel);   /* degenerate walk: seed globally */
            }
            ArrayDeque<int[]> queue = new ArrayDeque<>(channel);
            int guard = 0;
            int cap = 50 * (channel.size() + 1) + 64;
            while (!queue.isEmpty() && guard++ < cap) {
                int[] e = queue.poll();
                int u = e[0];
                int v = e[1];
                int t1 = edgeTriangle(u, v);
                if (t1 < 0) {
                    continue;                       /* edge already flipped away */
                }
                int t2 = acrossEdge(t1, u, v);
                if (t2 < 0 || !cross(pts, u, v, a, b)) {
                    continue;                       /* boundary, or no longer crossing */
                }
                int p = apex(t1, u, v);
                int q = apex(t2, u, v);
                if (convex(pts, u, v, p, q)) {
                    flip(t1, t2, u, v, p, q);
                    if (cross(pts, p, q, a, b)) {
                        queue.add(new int[]{p, q});  /* new diagonal still crosses; reprocess */
                    }
                } else {
                    queue.add(e);                   /* reflex now; retry after others flip */
                }
            }
            segSet.add(key(a, b));
        }

        /**
         * Collect the edges crossing {@code (a,b)} into {@code out} by walking the
         * channel from {@code a}, returning whether the walk completed (it stalls on
         * a degenerate alignment - e.g. {@code (a,b)} collinear with another vertex
         * - in which case the caller seeds globally). An empty channel with a true
         * return means {@code (a,b)} is already an edge.
         */
        private boolean collectChannel(int a, int b, List<int[]> out) {
            int t = enteringTriangle(a, b);
            if (t < 0) {
                return t == -1;                     /* -1: already an edge; -2: degenerate */
            }
            int ia = localIndex(t, a);
            int u = cor[3 * t + (ia + 1) % 3];
            int v = cor[3 * t + (ia + 2) % 3];
            int w = 0;
            int cap = 8 * nt + 64;
            while (w++ < cap) {
                out.add(new int[]{u, v});
                int t2 = acrossEdge(t, u, v);
                if (t2 < 0) {
                    return false;
                }
                int q = apex(t2, u, v);
                if (q == b) {
                    return true;                    /* reached b's triangle: channel complete */
                }
                if (cross(pts, v, q, a, b)) {       /* segment continues across (v,q) */
                    t = t2;
                    u = v;
                    v = q;
                } else if (cross(pts, q, u, a, b)) { /* across (q,u) */
                    t = t2;
                    v = u;
                    u = q;
                } else {
                    return false;                   /* degenerate: cannot step on */
                }
            }
            return false;
        }

        /** Seed the channel by scanning all triangles for edges crossing {@code
            (a,b)} - the robust fallback when the local walk stalls. */
        private void collectChannelGlobal(int a, int b, List<int[]> out) {
            out.clear();
            for (int t = 0; t < nt; t++) {
                for (int j = 0; j < 3; j++) {
                    int nb = nbr[3 * t + j];
                    if (nb < t) {                   /* each interior edge once; skips -1 */
                        continue;
                    }
                    int u = cor[3 * t + (j + 1) % 3];
                    int v = cor[3 * t + (j + 2) % 3];
                    if (cross(pts, u, v, a, b)) {
                        out.add(new int[]{u, v});
                    }
                }
            }
        }

        /**
         * The triangle incident to {@code a} whose interior the ray {@code a->b}
         * enters, or {@code -1} when {@code (a,b)} is already an edge. Rotates the
         * fan around {@code a} (counterclockwise, then clockwise if {@code a} is on
         * the hull). Returns {@code -2} if neither is found (degenerate; defensive).
         */
        private int enteringTriangle(int a, int b) {
            int t0 = vtri[a];
            int t = t0;
            boolean ccw = true;
            while (true) {
                int ia = localIndex(t, a);
                int p = cor[3 * t + (ia + 1) % 3];
                int q = cor[3 * t + (ia + 2) % 3];
                if (p == b || q == b) {
                    return -1;                      /* (a,b) is an edge of t */
                }
                if (orient(pts, a, p, b) > 0 && orient(pts, a, q, b) < 0) {
                    return t;                        /* a->b enters t through edge (p,q) */
                }
                int next = ccw ? nbr[3 * t + (ia + 1) % 3] : nbr[3 * t + (ia + 2) % 3];
                if (next < 0) {
                    if (ccw) {                       /* hit the hull; sweep the other way from t0 */
                        ccw = false;
                        t = nbr[3 * t0 + (localIndex(t0, a) + 2) % 3];
                        if (t < 0) {
                            return -2;
                        }
                        continue;
                    }
                    return -2;
                }
                t = next;
                if (ccw && t == t0) {
                    return -2;                       /* full fan, not found (defensive) */
                }
            }
        }

        /** A triangle having edge {@code (u,v)}, found by rotating the fan around
            {@code u}, or -1 if {@code (u,v)} is not a current edge. */
        private int edgeTriangle(int u, int v) {
            int t0 = vtri[u];
            int t = t0;
            boolean ccw = true;
            while (true) {
                int iu = localIndex(t, u);
                if (cor[3 * t + (iu + 1) % 3] == v || cor[3 * t + (iu + 2) % 3] == v) {
                    return t;
                }
                int next = ccw ? nbr[3 * t + (iu + 1) % 3] : nbr[3 * t + (iu + 2) % 3];
                if (next < 0) {
                    if (ccw) {
                        ccw = false;
                        t = nbr[3 * t0 + (localIndex(t0, u) + 2) % 3];
                        if (t < 0) {
                            return -1;
                        }
                        continue;
                    }
                    return -1;
                }
                t = next;
                if (ccw && t == t0) {
                    return -1;
                }
            }
        }

        /* --- 4. constrained Delaunay restoration ----------------------------- */

        /**
         * Restore the Delaunay property on non-segment edges by Lawson flipping:
         * seed a stack with every edge, and for each popped edge that a neighbour
         * apex sees inside the triangle's circumcircle (and is flippable and not a
         * segment) flip it and re-push the local edges. O(flips) amortized in place
         * of the previous full rescan per flip.
         */
        void restoreDelaunay() {
            /* A primitive int stack of edge slots (3*t+j): it starts with all 3T
               edges and churns with every flip, so boxing here was measurable. */
            int[] stack = new int[3 * nt + 16];
            int top = 0;
            for (int e = 0; e < 3 * nt; e++) {      /* pops 3*nt-1 first, like the old LIFO push */
                stack[top++] = e;
            }
            int guard = 0;
            int cap = 200 * (nt + 1);
            while (top > 0 && guard++ < cap) {
                int e = stack[--top];
                int t = e / 3;
                int j = e % 3;
                int nb = nbr[3 * t + j];
                if (nb < 0) {
                    continue;
                }
                int u = cor[3 * t + (j + 1) % 3];
                int v = cor[3 * t + (j + 2) % 3];
                if (segSet.contains(key(u, v))) {
                    continue;
                }
                int p = cor[3 * t + j];
                int q = apex(nb, u, v);
                if (Geometry.inCircle(pts, cor[3 * t], cor[3 * t + 1], cor[3 * t + 2], pts.get(q))
                        && convex(pts, u, v, p, q)) {
                    flip(t, nb, u, v, p, q);
                    if (top + 6 > stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    for (int k = 0; k < 3; k++) {
                        stack[top++] = 3 * t + k;
                        stack[top++] = 3 * nb + k;
                    }
                }
            }
        }

        /* --- 5/6. carving and region attribution ----------------------------- */

        private boolean isSegmentEdge(int t, int j) {
            return segSet.contains(key(cor[3 * t + (j + 1) % 3], cor[3 * t + (j + 2) % 3]));
        }

        boolean[] carve(List<Point> holes) {
            boolean[] removed = new boolean[nt];
            ArrayDeque<Integer> queue = new ArrayDeque<>();

            /* Seed: triangles exposed to the outside across a non-segment hull edge. */
            for (int i = 0; i < nt; i++) {
                for (int j = 0; j < 3; j++) {
                    if (nbr[3 * i + j] == -1 && !isSegmentEdge(i, j) && !removed[i]) {
                        removed[i] = true;
                        queue.add(i);
                    }
                }
            }
            /* Seed: triangle containing each hole point. */
            for (Point h : holes) {
                int t = locate(h.getX(), h.getY());
                if (t >= 0 && !removed[t]) {
                    removed[t] = true;
                    queue.add(t);
                }
            }
            while (!queue.isEmpty()) {
                int t = queue.poll();
                for (int j = 0; j < 3; j++) {
                    int nb = nbr[3 * t + j];
                    if (nb >= 0 && !removed[nb] && !isSegmentEdge(t, j)) {
                        removed[nb] = true;
                        queue.add(nb);
                    }
                }
            }
            return removed;
        }

        double @Nullable [] attributeRegions(boolean[] removed, List<Region> regions) {
            if (regions.isEmpty()) {
                return null;
            }
            double[] attr = new double[nt];
            for (Region region : regions) {
                int start = locate(region.getSite().getX(), region.getSite().getY());
                if (start < 0 || removed[start]) {
                    continue;
                }
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                boolean[] seen = new boolean[nt];
                seen[start] = true;
                queue.add(start);
                while (!queue.isEmpty()) {
                    int t = queue.poll();
                    attr[t] = region.getAttribute();
                    for (int j = 0; j < 3; j++) {
                        int nb = nbr[3 * t + j];
                        if (nb >= 0 && !removed[nb] && !seen[nb] && !isSegmentEdge(t, j)) {
                            seen[nb] = true;
                            queue.add(nb);
                        }
                    }
                }
            }
            return attr;
        }

        /* The first triangle whose closed region contains (x,y). On-edge counts, so
           a seed lying exactly on a triangle edge still locates a triangle. */
        private int locate(double x, double y) {
            for (int i = 0; i < nt; i++) {
                int s1 = Geometry.orient2d(pts, cor[3 * i], cor[3 * i + 1], x, y);
                int s2 = Geometry.orient2d(pts, cor[3 * i + 1], cor[3 * i + 2], x, y);
                int s3 = Geometry.orient2d(pts, cor[3 * i + 2], cor[3 * i], x, y);
                boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
                boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
                if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                    return i;
                }
            }
            return -1;
        }

        /* --- output ---------------------------------------------------------- */

        TriangleMesherOutput2 buildOutput(boolean[] removed, double @Nullable [] attr, Pslg pslg) {
            /* One cell per slot, null where carved away, each carrying its neighbour
               ids in slot indexing; TriangleUtils.compact drops the dead slots and
               remaps the neighbours (a neighbour pointing at a carved slot collapses
               to a boundary, since that slot's remap entry stays -1). */
            List<@Nullable ImmutableTriangle> slots = new ArrayList<>(nt);
            for (int i = 0; i < nt; i++) {
                slots.add(removed[i] ? null : new DefaultImmutableTriangle(
                        cor[3 * i], cor[3 * i + 1], cor[3 * i + 2],
                        nbr[3 * i], nbr[3 * i + 1], nbr[3 * i + 2],
                        attr != null ? attr[i] : 0.0));
            }

            /* The PSLG is single-use (discarded after this), so the output adopts its
               point store and recovered subsegment list directly rather than copying. */
            return new TriangleMesherOutput2(pslg.points, TriangleUtils.compact(slots), pslg.segments, attr != null);
        }
    }

    /* --- geometry helpers ---------------------------------------------------- */

    private static boolean cross(List<Point> pts, int u, int v, int a, int b) {
        int d1 = orient(pts, a, b, u);
        int d2 = orient(pts, a, b, v);
        int d3 = orient(pts, u, v, a);
        int d4 = orient(pts, u, v, b);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static boolean convex(List<Point> pts, int u, int v, int p, int q) {
        return orient(pts, p, q, u) * orient(pts, p, q, v) < 0;
    }

    /** Orientation of (a, b, c) - the single orientation entry the
        construction-phase code shares, over the working vertex list. */
    private static int orient(List<Point> pts, int a, int b, int c) {
        return Geometry.orient2d(pts, a, b, c);
    }

    private static long key(int a, int b) {
        return Topology.edgeKey(a, b);
    }
}
