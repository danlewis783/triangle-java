package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import org.jspecify.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * All geometric tests use the robust {@link Geometry}. Refinement (quality) is
 * phase 3; this produces the unrefined constrained Delaunay mesh.
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
        Points pts = pslg.points;

        /* 2. Initial Delaunay of all points. (DelaunayTriangulator still takes the
           flat point array; that input signature is the one remaining seam.) */
        List<Corners> tris = DelaunayTriangulator.triangulate(pts.toArray(), pts.size());

        /* 3. Recover segments. */
        Set<Long> segSet = new HashSet<>();
        for (Constraint s : pslg.segments) {
            insertSegment(pts, tris, s.a, s.b);
            segSet.add(key(s.a, s.b));
        }

        /* 4. Restore constrained Delaunay. */
        restoreDelaunay(pts, tris, segSet);

        /* 5. Carve holes and concavities. */
        boolean[] removed = carve(pts, tris, segSet, in.holes);

        /* 6. Attribute regions. */
        double[] attr = attributeRegions(pts, tris, removed, segSet, in.regions);

        return buildOutput(tris, removed, attr, pslg);
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
        final Points points;
        final List<Constraint> segments;

        Pslg(Points points, List<Constraint> segments) {
            this.points = points;
            this.segments = segments;
        }
    }

    private static Pslg splitIntersections(TriangleMesherInput2 in) {
        /* Growable working copy: intersection points are appended as crossings
           are resolved (the input store must not be mutated). */
        Points pts = new Points(in.points.toArray(), in.points.size());
        List<Constraint> segs = new ArrayList<>(in.segments);

        boolean changed = true;
        while (changed) {
            changed = false;

            /* (a) Two segments cross: split both at their intersection point. */
            crossings:
            for (int i = 0; i < segs.size(); i++) {
                for (int j = i + 1; j < segs.size(); j++) {
                    Constraint a = segs.get(i), b = segs.get(j);
                    if (share(a, b)) {
                        continue;
                    }
                    if (cross(pts, a.a, a.b, b.a, b.b)) {
                        int c = pts.add(intersection(pts, a.a, a.b, b.a, b.b));
                        segs.set(i, new Constraint(a.a, c, a.marker));
                        segs.set(j, new Constraint(b.a, c, b.marker));
                        segs.add(new Constraint(c, a.b, a.marker));
                        segs.add(new Constraint(c, b.b, b.marker));
                        changed = true;
                        break crossings;
                    }
                }
            }
            if (changed) {
                continue;
            }

            /* (b) A vertex lies exactly on a segment (a T-junction): split the
               segment there. The whole segment cannot be recovered as one edge
               with a vertex on it, so it must become a chain of subsegments. */
            onEdge:
            for (int i = 0; i < segs.size(); i++) {
                Constraint s = segs.get(i);
                for (int v = 0; v < pts.size(); v++) {
                    if (v != s.a && v != s.b && onSegmentInterior(pts, s.a, s.b, v)) {
                        segs.set(i, new Constraint(s.a, v, s.marker));
                        segs.add(new Constraint(v, s.b, s.marker));
                        changed = true;
                        break onEdge;
                    }
                }
            }
        }

        return new Pslg(pts, segs);
    }

    private static boolean share(Constraint a, Constraint b) {
        return a.a == b.a || a.a == b.b || a.b == b.a || a.b == b.b;
    }

    /** True if vertex v is exactly collinear with (a,b) and strictly between them. */
    private static boolean onSegmentInterior(Points pts, int a, int b, int v) {
        if (orient(pts, a, b, v) != 0) {
            return false;
        }
        double pax = pts.x(a), pay = pts.y(a), pbx = pts.x(b), pby = pts.y(b);
        double pvx = pts.x(v), pvy = pts.y(v);
        double toA = (pvx - pax) * (pbx - pax) + (pvy - pay) * (pby - pay);
        double toB = (pvx - pbx) * (pax - pbx) + (pvy - pby) * (pay - pby);
        return toA > 0 && toB > 0;            /* strictly inside, not at an endpoint */
    }

    private static Point intersection(Points pts, int p0, int p1, int q0, int q1) {
        double x1 = pts.x(p0), y1 = pts.y(p0);
        double x2 = pts.x(p1), y2 = pts.y(p1);
        double x3 = pts.x(q0), y3 = pts.y(q0);
        double x4 = pts.x(q1), y4 = pts.y(q1);
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        return new Point(x1 + t * (x2 - x1), y1 + t * (y2 - y1));
    }

    /* --- 3. segment recovery via flips --------------------------------------- */

    /**
     * A convex edge flip: drop the two triangles in slots {@code triHi}/{@code
     * triLo} that share edge {@code (u, v)} and replace them with the two
     * triangles spanning the opposite diagonal {@code (p, q)}. The slots are
     * ordered larger-first so removing one does not shift the other's index.
     */
    private static final class Flip {
        final int triHi, triLo, u, v, p, q;

        Flip(int triHi, int triLo, int u, int v, int p, int q) {
            this.triHi = triHi;
            this.triLo = triLo;
            this.u = u;
            this.v = v;
            this.p = p;
            this.q = q;
        }
    }

    private static void insertSegment(Points pts, List<Corners> tris, int a, int b) {
        while (!isEdge(tris, a, b)) {
            Flip flip = findFlippableCrossing(pts, tris, a, b);
            if (flip == null) {
                return;                 /* defensive: nothing flippable */
            }
            doFlip(pts, tris, flip);
        }
    }

    private static @Nullable Flip findFlippableCrossing(Points pts, List<Corners> tris,
                                                        int a, int b) {
        Map<Long, EdgeSide> edge = new HashMap<>();
        for (int i = 0; i < tris.size(); i++) {
            Corners t = tris.get(i);
            for (int j = 0; j < 3; j++) {
                int u = t.corner((j + 1) % 3), v = t.corner((j + 2) % 3);
                long k = key(u, v);
                EdgeSide prev = edge.get(k);
                if (prev == null) {
                    edge.put(k, new EdgeSide(i, j));
                } else {
                    int t1 = prev.tri, t2 = i;
                    int p = tris.get(t1).corner(prev.corner);
                    int q = t.corner(j);
                    if (cross(pts, u, v, a, b) && convex(pts, u, v, p, q)) {
                        return new Flip(Math.max(t1, t2), Math.min(t1, t2), u, v, p, q);
                    }
                }
            }
        }
        return null;
    }

    private static void doFlip(Points pts, List<Corners> tris, Flip f) {
        tris.remove(f.triHi);              /* remove larger index first */
        tris.remove(f.triLo);
        tris.add(ccw(pts, f.p, f.v, f.q));
        tris.add(ccw(pts, f.q, f.u, f.p));
    }

    /* --- 4. constrained Delaunay restoration --------------------------------- */

    private static void restoreDelaunay(Points pts, List<Corners> tris,
                                        Set<Long> segSet) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Long, EdgeSide> edge = new HashMap<>();
            for (int i = 0; i < tris.size() && !changed; i++) {
                Corners t = tris.get(i);
                for (int j = 0; j < 3 && !changed; j++) {
                    int u = t.corner((j + 1) % 3), v = t.corner((j + 2) % 3);
                    long k = key(u, v);
                    EdgeSide prev = edge.get(k);
                    if (prev == null) {
                        edge.put(k, new EdgeSide(i, j));
                        continue;
                    }
                    if (segSet.contains(k)) {
                        continue;
                    }
                    int t1 = prev.tri;
                    int p = tris.get(t1).corner(prev.corner);
                    int q = t.corner(j);
                    if (Geometry.inCircle(pts, tris.get(t1), q) && convex(pts, u, v, p, q)) {
                        doFlip(pts, tris, new Flip(Math.max(t1, i), Math.min(t1, i),
                                u, v, p, q));
                        changed = true;
                    }
                }
            }
        }
    }

    /* --- 5/6. carving and region attribution --------------------------------- */

    /** Triangle adjacency: neigh[3*i+j] = triangle across the edge opposite
        corner j, or -1. Delegates to the shared {@link Topology#neighbors}. */
    private static int[] adjacency(List<Corners> tris) {
        return Topology.neighbors(tris.size(), (i, c) -> tris.get(i).corner(c));
    }

    private static boolean[] carve(Points pts, List<Corners> tris, Set<Long> segSet,
                                   List<Point> holes) {
        int n = tris.size();
        int[] neigh = adjacency(tris);
        boolean[] removed = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        /* Seed: triangles exposed to the outside across a non-segment hull edge. */
        for (int i = 0; i < n; i++) {
            Corners t = tris.get(i);
            for (int j = 0; j < 3; j++) {
                if (neigh[3 * i + j] == -1
                        && !segSet.contains(key(t.corner((j + 1) % 3), t.corner((j + 2) % 3)))
                        && !removed[i]) {
                    removed[i] = true;
                    queue.add(i);
                }
            }
        }
        /* Seed: triangle containing each hole point. */
        for (Point h : holes) {
            int t = locate(pts, tris, h.x, h.y);
            if (t >= 0 && !removed[t]) {
                removed[t] = true;
                queue.add(t);
            }
        }
        flood(tris, neigh, segSet, removed, queue);
        return removed;
    }

    private static double @Nullable [] attributeRegions(Points pts, List<Corners> tris,
                                             boolean[] removed, Set<Long> segSet,
                                             List<Region> regions) {
        if (regions.isEmpty()) {
            return null;
        }
        int n = tris.size();
        int[] neigh = adjacency(tris);
        double[] attr = new double[n];
        for (Region region : regions) {
            int start = locate(pts, tris, region.site.x, region.site.y);
            if (start < 0 || removed[start]) {
                continue;
            }
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean[] seen = new boolean[n];
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int t = queue.poll();
                attr[t] = region.attribute;
                Corners tc = tris.get(t);
                for (int j = 0; j < 3; j++) {
                    int nb = neigh[3 * t + j];
                    if (nb >= 0 && !removed[nb] && !seen[nb]
                            && !segSet.contains(key(tc.corner((j + 1) % 3), tc.corner((j + 2) % 3)))) {
                        seen[nb] = true;
                        queue.add(nb);
                    }
                }
            }
        }
        return attr;
    }

    private static void flood(List<Corners> tris, int[] neigh, Set<Long> segSet,
                              boolean[] removed, ArrayDeque<Integer> queue) {
        while (!queue.isEmpty()) {
            int t = queue.poll();
            Corners tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = neigh[3 * t + j];
                if (nb >= 0 && !removed[nb]
                        && !segSet.contains(key(tc.corner((j + 1) % 3), tc.corner((j + 2) % 3)))) {
                    removed[nb] = true;
                    queue.add(nb);
                }
            }
        }
    }

    /* The first triangle whose closed region contains (x,y). On-edge counts, so
       a seed lying exactly on a triangle edge still locates a triangle (it would
       be missed by a strictly-inside test). */
    private static int locate(Points pts, List<Corners> tris, double x, double y) {
        for (int i = 0; i < tris.size(); i++) {
            Corners t = tris.get(i);
            int s1 = Geometry.orient2d(pts, t.a, t.b, x, y);
            int s2 = Geometry.orient2d(pts, t.b, t.c, x, y);
            int s3 = Geometry.orient2d(pts, t.c, t.a, x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    /* --- output -------------------------------------------------------------- */

    private static TriangleMesherOutput2 buildOutput(List<Corners> tris, boolean[] removed,
                                                     double @Nullable [] attr, Pslg pslg) {
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < tris.size(); i++) {
            if (!removed[i]) {
                keep.add(i);
            }
        }
        int t = keep.size();

        /* Corners of the kept triangles, then their adjacency over the compacted
           indexing - assembled into the interleaved {@link Triangles} layout. */
        int[] corner = new int[3 * t];
        for (int i = 0; i < t; i++) {
            Corners tr = tris.get(keep.get(i));
            corner[3 * i] = tr.a;
            corner[3 * i + 1] = tr.b;
            corner[3 * i + 2] = tr.c;
        }
        double[] outAttr = null;
        if (attr != null) {
            outAttr = new double[t];
            for (int i = 0; i < t; i++) {
                outAttr[i] = attr[keep.get(i)];
            }
        }
        int[] neigh = Topology.neighbors(t, (i, c) -> corner[3 * i + c]);
        int[] triData = new int[6 * t];
        for (int i = 0; i < t; i++) {
            triData[6 * i] = corner[3 * i];
            triData[6 * i + 1] = corner[3 * i + 1];
            triData[6 * i + 2] = corner[3 * i + 2];
            triData[6 * i + 3] = neigh[3 * i];
            triData[6 * i + 4] = neigh[3 * i + 1];
            triData[6 * i + 5] = neigh[3 * i + 2];
        }
        Triangles triangles = new Triangles(triData, outAttr, t);

        int s = pslg.segments.size();
        int[] segData = new int[3 * s];
        for (int i = 0; i < s; i++) {
            Constraint c = pslg.segments.get(i);
            segData[3 * i] = c.a;
            segData[3 * i + 1] = c.b;
            segData[3 * i + 2] = c.marker;
        }
        Constraints segments = new Constraints(segData, s);

        /* The working point store is single-use (the PSLG is discarded after
           this), so the output adopts it directly rather than rebuilding a copy. */
        return new TriangleMesherOutput2(pslg.points, triangles, segments);
    }

    /* --- geometry helpers ---------------------------------------------------- */

    private static boolean isEdge(List<Corners> tris, int a, int b) {
        for (Corners t : tris) {
            boolean ha = t.a == a || t.b == a || t.c == a;
            boolean hb = t.a == b || t.b == b || t.c == b;
            if (ha && hb) {
                return true;
            }
        }
        return false;
    }

    private static boolean cross(Points pts, int u, int v, int a, int b) {
        int d1 = orient(pts, a, b, u), d2 = orient(pts, a, b, v);
        int d3 = orient(pts, u, v, a), d4 = orient(pts, u, v, b);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static boolean convex(Points pts, int u, int v, int p, int q) {
        return orient(pts, p, q, u) * orient(pts, p, q, v) < 0;
    }

    private static Corners ccw(Points pts, int a, int b, int c) {
        return orient(pts, a, b, c) >= 0 ? new Corners(a, b, c) : new Corners(a, c, b);
    }

    /** Orientation of (a, b, c) - the single orientation entry the
        construction-phase helpers share, over the working {@link Points} store. */
    private static int orient(Points pts, int a, int b, int c) {
        return Geometry.orient2d(pts, a, b, c);
    }

    private static long key(int a, int b) {
        return Topology.edgeKey(a, b);
    }
}
