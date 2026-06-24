package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.predicate.Predicates;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A constrained Delaunay mesh that supports incremental insertion of interior
 * Steiner points, so refinement need not rebuild the whole mesh from scratch
 * after every point (see the perf roadmap: the from-scratch rebuild makes
 * refinement ~O(N^3)).
 *
 * <p>It is built from an existing {@link ConstrainedDelaunayTriangulator}
 * output, whose carved domain is bounded <em>entirely by segments</em> - that is
 * the property that makes interior insertion safe: an interior point's cavity
 * can never escape the domain without first hitting a segment.
 *
 * <p>Interior insertion is constrained Bowyer-Watson: starting from the triangle
 * containing the point, gather every triangle whose circumcircle contains it,
 * never crossing a segment; remove that cavity and re-fan it around the new
 * point. The result is locally Delaunay with no global rework. New triangles
 * inherit the cavity's region attribute (the cavity lies within one region,
 * since it does not cross a segment).
 *
 * <p><b>Maintained adjacency (slice 5c-1).</b> Each triangle has a stable integer
 * id (its slot index) and carries its own three neighbour ids; insertion walks
 * the cavity through those links and relinks the new fan locally, so it is
 * O(cavity) instead of rebuilding global adjacency from a {@code HashMap} every
 * time. Deleted triangles are marked dead - their slot nulled and queued for
 * reuse - so the live views contain holes and scanning consumers skip nulls. The
 * mesh is compacted only when {@link #toOutput()} builds the final result, which
 * emits the maintained adjacency directly (so the {@code MeshValidator}
 * neighbour-slot invariants are a precise oracle for the surgery here).
 */
final class IncrementalCdt {

    /** Vertex provenance, needed by the small-feature refinement rules. */
    static final int INPUT = 0;     /* an original input vertex (segment joins) */
    static final int SEGMENT = 1;   /* created by splitting a segment (on its interior) */
    static final int FREE = 2;      /* an interior Steiner point */

    private final List<double[]> points = new ArrayList<>();
    private final List<Integer> vtype = new ArrayList<>();    /* INPUT/SEGMENT/FREE per vertex */
    private final List<int[]> vseg = new ArrayList<>();       /* {origOrg,origDest} for SEGMENT, else null */
    private final List<int[]> tris = new ArrayList<>();       /* CCW {a, b, c}, null if dead */
    private final List<int[]> nbrs = new ArrayList<>();       /* 3 neighbour ids (or -1), parallel to tris */
    private final List<Double> attrs;                         /* per-tri attr, or null */
    private final List<int[]> segments = new ArrayList<>();   /* {a,b,marker,origOrg,origDest} */
    private final Set<Long> segSet = new HashSet<>();

    /* Dead slots awaiting reuse, and the count of live triangles. */
    private final Deque<Integer> freeSlots = new ArrayDeque<>();
    private int liveCount;

    /* Generation-stamped cavity membership, so gathering a cavity is O(cavity)
       (never a full-size clear): a slot is in the current cavity iff its stamp
       equals the current generation. */
    private int[] cavityGen;
    private int gen;

    IncrementalCdt(TriangleMesherOutput base) {
        for (int i = 0; i < base.numberOfPoints; i++) {
            points.add(new double[]{base.pointList[2 * i], base.pointList[2 * i + 1]});
            vtype.add(INPUT);
            vseg.add(null);
        }
        boolean haveAttr = base.triangleAttributeList != null;
        attrs = haveAttr ? new ArrayList<>() : null;
        for (int i = 0; i < base.numberOfTriangles; i++) {
            tris.add(new int[]{base.triangleList[3 * i], base.triangleList[3 * i + 1],
                    base.triangleList[3 * i + 2]});
            if (haveAttr) {
                attrs.add(base.triangleAttributeList[i]);
            }
        }
        int[] adj = adjacency(tris);             /* one-time global build at construction */
        for (int i = 0; i < base.numberOfTriangles; i++) {
            nbrs.add(new int[]{adj[3 * i], adj[3 * i + 1], adj[3 * i + 2]});
        }
        liveCount = base.numberOfTriangles;
        cavityGen = new int[Math.max(16, tris.size())];
        if (base.segmentList != null) {
            for (int i = 0; i < base.numberOfSegments; i++) {
                int a = base.segmentList[2 * i], b = base.segmentList[2 * i + 1];
                int marker = base.segmentMarkerList != null ? base.segmentMarkerList[i] : 0;
                segments.add(new int[]{a, b, marker, a, b});   /* original endpoints = a, b */
                segSet.add(key(a, b));
            }
        }
    }

    int vertexType(int i) {
        return vtype.get(i);
    }

    /** For a SEGMENT vertex, the original input segment's endpoints; else null. */
    int[] vertexSeg(int i) {
        return vseg.get(i);
    }

    /* Live views for the refinement loop, so it reads the mesh in place rather
       than snapshotting it every iteration. With maintained adjacency these lists
       are mutated in place (slots reused/nulled), so the references are stable;
       dead triangles appear as null and scanning consumers must skip them. */

    List<double[]> pointsView() {
        return points;
    }

    List<int[]> trianglesView() {
        return tris;
    }

    /** Each entry is {a, b, marker, origOrg, origDest}. */
    List<int[]> segmentsView() {
        return segments;
    }

    /** Per-triangle region attributes parallel to {@link #trianglesView()}, or null. */
    List<Double> attrsView() {
        return attrs;
    }

    int pointCount() {
        return points.size();
    }

    int triangleCount() {
        return liveCount;
    }

    /**
     * Insert a point lying strictly inside the meshed domain (not on a segment)
     * via constrained Bowyer-Watson.
     *
     * @return the new vertex index
     */
    int insertInteriorPoint(double[] p) {
        int start = locate(p[0], p[1]);
        if (start < 0) {
            throw new IllegalArgumentException("point is not inside the domain");
        }
        int pIdx = points.size();
        points.add(p);
        vtype.add(FREE);
        vseg.add(null);
        insertViaCavity(pIdx, new int[]{start}, -1L);
        return pIdx;
    }

    /**
     * Split segment {@code segIndex} at its midpoint (Ruppert's subsegment
     * split): insert the midpoint as a vertex and replace the segment with its
     * two halves.
     *
     * <p>The midpoint is inserted with the same cavity machinery, seeded from the
     * triangles incident to the segment (robust - no point-location test on the
     * rounded midpoint). The old segment is dropped from the constraint set
     * first, so the cavity may span both of its sides - an interior
     * region-boundary segment splits both regions at once - and the two halves
     * are registered afterwards.
     *
     * @return the new midpoint vertex index
     */
    int splitSegment(int segIndex) {
        int[] seg = segments.get(segIndex);
        int a = seg[0], b = seg[1], marker = seg[2], origOrg = seg[3], origDest = seg[4];
        int[] seeds = incidentTriangles(a, b);
        if (seeds.length == 0) {
            throw new IllegalStateException("segment (" + a + "," + b + ") is not an edge");
        }
        double frac = shellSplitFraction(a, b);
        double[] pa = points.get(a), pb = points.get(b);
        int mIdx = points.size();
        points.add(new double[]{pa[0] + frac * (pb[0] - pa[0]),
                pa[1] + frac * (pb[1] - pa[1])});
        vtype.add(SEGMENT);
        vseg.add(new int[]{origOrg, origDest});

        segSet.remove(key(a, b));            /* let the cavity span the old segment */
        insertViaCavity(mIdx, seeds, key(a, b));

        segments.set(segIndex, new int[]{a, mIdx, marker, origOrg, origDest});
        segments.add(new int[]{mIdx, b, marker, origOrg, origDest});
        segSet.add(key(a, mIdx));
        segSet.add(key(mIdx, b));
        return mIdx;
    }

    /**
     * Concentric-shell split fraction (Triangle's rule, triangle.c:8163): if an
     * endpoint is an original join vertex, split at a power-of-two distance from
     * it so the split points around a join land on shared "shells" (equal radii),
     * which keeps the bridging triangles isosceles instead of ever-thinner
     * slivers and lets {@code badTriangle} recognize them as unsplittable.
     * Away from joins, split at the midpoint.
     */
    private double shellSplitFraction(int a, int b) {
        boolean acuteOrg = vtype.get(a) == INPUT;
        boolean acuteDest = vtype.get(b) == INPUT;
        if (!acuteOrg && !acuteDest) {
            return 0.5;
        }
        double len = Math.sqrt(dist2(points.get(a), points.get(b)));
        double npot = 1.0;
        while (len > 3.0 * npot) {
            npot *= 2.0;
        }
        while (len < 1.5 * npot) {
            npot *= 0.5;
        }
        double split = npot / len;          /* fraction from the origin endpoint */
        if (acuteDest) {
            split = 1.0 - split;            /* measure from the destination instead */
        }
        return split;
    }

    /**
     * Constrained Bowyer-Watson insertion of an already-added vertex {@code pIdx},
     * maintaining adjacency. Starting from {@code seeds} (triangles already known
     * to contain it in their circumcircle), walk the maintained neighbour links to
     * gather the cavity of triangles whose circumcircle contains it - never
     * crossing a current segment - then re-fan the cavity boundary around it,
     * relinking locally. The boundary edge equal to {@code skipEdgeKey} is not
     * re-fanned (used when splitting a boundary segment, where the split edge would
     * form a degenerate triangle); pass {@code -1L} for none. Each new triangle
     * inherits its source cavity triangle's region attribute, so a cavity that
     * spans two regions attributes correctly.
     *
     * <p>The fan is built as {@code {u, w, p}} with the inserted vertex {@code p}
     * always at corner 2: the boundary edge {@code (u,w)} is then opposite corner
     * 2 (slot 2 -&gt; the outer-ring neighbour), and the two interior fan edges are
     * {@code (p,u)} at slot 1 and {@code (w,p)} at slot 0. Adjacent fan triangles
     * are paired by shared boundary vertex - each appears once as a {@code u}
     * (slot-1 edge) and once as a {@code w} (slot-0 edge) - and the outer-ring
     * neighbour's link back into the cavity is repointed to the new triangle.
     */
    private void insertViaCavity(int pIdx, int[] seeds, long skipEdgeKey) {
        double[] p = points.get(pIdx);
        gen++;
        List<Integer> cavity = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int s : seeds) {
            if (cavityGen[s] != gen) {
                cavityGen[s] = gen;
                stack.push(s);
            }
        }
        while (!stack.isEmpty()) {
            int t = stack.pop();
            cavity.add(t);
            int[] tc = tris.get(t);
            int[] tn = nbrs.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = tn[j];
                if (nb < 0 || cavityGen[nb] == gen) {
                    continue;
                }
                int u = tc[(j + 1) % 3], w = tc[(j + 2) % 3];
                if (segSet.contains(key(u, w))) {
                    continue;                       /* never cross a segment */
                }
                if (inCircle(tris.get(nb), p)) {
                    cavityGen[nb] = gen;
                    stack.push(nb);
                }
            }
        }

        /* Collect the cavity-boundary edges (outer neighbour + region attribute)
           before freeing the cavity, since freeing nulls the slots. A cavity edge
           is on the boundary when its neighbour is outside the cavity or it is a
           segment; the split edge (skipEdgeKey) is never re-fanned. */
        List<int[]> fan = new ArrayList<>();               /* {u, w, outerNeighbour} */
        List<Double> fanAttr = attrs != null ? new ArrayList<>() : null;
        for (int t : cavity) {
            int[] tc = tris.get(t);
            int[] tn = nbrs.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = tn[j];
                int u = tc[(j + 1) % 3], w = tc[(j + 2) % 3];
                long k = key(u, w);
                boolean boundary = nb < 0 || cavityGen[nb] != gen || segSet.contains(k);
                if (boundary && k != skipEdgeKey) {
                    fan.add(new int[]{u, w, nb});
                    if (fanAttr != null) {
                        fanAttr.add(attrs.get(t));
                    }
                }
            }
        }
        for (int t : cavity) {
            freeSlot(t);
        }

        /* Re-fan and relink. asU/asW map each boundary vertex to the new triangle
           where it sits at corner 0 (a 'u') or corner 1 (a 'w'); the two share the
           interior fan edge through p and are linked when the second is created. */
        Map<Integer, Integer> asU = new HashMap<>();
        Map<Integer, Integer> asW = new HashMap<>();
        for (int i = 0; i < fan.size(); i++) {
            int[] f = fan.get(i);
            int u = f[0], w = f[1], nb = f[2];
            int id = allocSlot(new int[]{u, w, pIdx}, new int[]{-1, -1, nb},
                    fanAttr != null ? fanAttr.get(i) : null);
            if (nb >= 0) {                                  /* repoint the outer ring */
                int[] nc = tris.get(nb);
                int[] nn = nbrs.get(nb);
                for (int k = 0; k < 3; k++) {
                    if (nc[k] != u && nc[k] != w) {
                        nn[k] = id;                         /* edge opposite the apex is (u,w) */
                        break;
                    }
                }
            }
            Integer mu = asW.get(u);
            if (mu != null) {                               /* shares the (p,u) edge */
                nbrs.get(id)[1] = mu;
                nbrs.get(mu)[0] = id;
            }
            asU.put(u, id);
            Integer mw = asU.get(w);
            if (mw != null) {                               /* shares the (w,p) edge */
                nbrs.get(id)[0] = mw;
                nbrs.get(mw)[1] = id;
            }
            asW.put(w, id);
        }
    }

    /** Reuse a dead slot if any, else append; returns the slot id. */
    private int allocSlot(int[] corners, int[] links, Double attr) {
        int id;
        if (!freeSlots.isEmpty()) {
            id = freeSlots.pop();
            tris.set(id, corners);
            nbrs.set(id, links);
            if (attrs != null) {
                attrs.set(id, attr);
            }
        } else {
            id = tris.size();
            tris.add(corners);
            nbrs.add(links);
            if (attrs != null) {
                attrs.add(attr);
            }
            ensureCavityGen(id + 1);
        }
        liveCount++;
        return id;
    }

    private void freeSlot(int id) {
        tris.set(id, null);
        nbrs.set(id, null);
        freeSlots.push(id);
        liveCount--;
    }

    private void ensureCavityGen(int n) {
        if (cavityGen.length < n) {
            cavityGen = Arrays.copyOf(cavityGen, Math.max(n, cavityGen.length * 2));
        }
    }

    /** The (one or two) live triangles having both a and b as corners. */
    private int[] incidentTriangles(int a, int b) {
        int t1 = -1, t2 = -1;
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            if (t == null) {
                continue;
            }
            boolean ha = t[0] == a || t[1] == a || t[2] == a;
            boolean hb = t[0] == b || t[1] == b || t[2] == b;
            if (ha && hb) {
                if (t1 < 0) {
                    t1 = i;
                } else {
                    t2 = i;
                    break;
                }
            }
        }
        if (t1 < 0) {
            return new int[0];
        }
        return t2 < 0 ? new int[]{t1} : new int[]{t1, t2};
    }

    TriangleMesherOutput toOutput() {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = points.size();
        o.pointList = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            o.pointList[2 * i] = points.get(i)[0];
            o.pointList[2 * i + 1] = points.get(i)[1];
        }
        /* Compact live slots to contiguous output indices, remapping the
           maintained neighbour links through the same mapping. */
        int[] remap = new int[tris.size()];
        Arrays.fill(remap, -1);
        int nt = 0;
        for (int i = 0; i < tris.size(); i++) {
            if (tris.get(i) != null) {
                remap[i] = nt++;
            }
        }
        o.numberOfTriangles = nt;
        o.triangleList = new int[3 * nt];
        o.neighborList = new int[3 * nt];
        boolean haveAttr = attrs != null;
        if (haveAttr) {
            o.triangleAttributeList = new double[nt];
        }
        for (int i = 0; i < tris.size(); i++) {
            int[] tc = tris.get(i);
            if (tc == null) {
                continue;
            }
            int ni = remap[i];
            o.triangleList[3 * ni] = tc[0];
            o.triangleList[3 * ni + 1] = tc[1];
            o.triangleList[3 * ni + 2] = tc[2];
            int[] tn = nbrs.get(i);
            for (int j = 0; j < 3; j++) {
                o.neighborList[3 * ni + j] = tn[j] < 0 ? -1 : remap[tn[j]];
            }
            if (haveAttr) {
                o.triangleAttributeList[ni] = attrs.get(i);
            }
        }
        o.numberOfSegments = segments.size();
        o.segmentList = new int[2 * segments.size()];
        o.segmentMarkerList = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            int[] s = segments.get(i);
            o.segmentList[2 * i] = s[0];
            o.segmentList[2 * i + 1] = s[1];
            o.segmentMarkerList[i] = s[2];
        }
        return o;
    }

    /**
     * Debug oracle (used by tests): the maintained neighbour links equal a
     * from-scratch rebuild over the live triangles. Cross-checks the hand-relinked
     * adjacency directly, with tighter localization than the output validator.
     */
    boolean adjacencyConsistent() {
        List<int[]> live = new ArrayList<>();
        int[] remap = new int[tris.size()];
        Arrays.fill(remap, -1);
        for (int i = 0; i < tris.size(); i++) {
            if (tris.get(i) != null) {
                remap[i] = live.size();
                live.add(tris.get(i));
            }
        }
        int[] fresh = adjacency(live);
        for (int i = 0; i < tris.size(); i++) {
            if (tris.get(i) == null) {
                continue;
            }
            int ni = remap[i];
            int[] tn = nbrs.get(i);
            for (int j = 0; j < 3; j++) {
                int maint = tn[j] < 0 ? -1 : remap[tn[j]];
                if (maint != fresh[3 * ni + j]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Centroid of the current largest-area triangle (a robust interior point). */
    double[] centroidOfLargestTriangle() {
        int best = -1;
        double bestArea = -1;
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            if (t == null) {
                continue;
            }
            double[] a = points.get(t[0]), b = points.get(t[1]), c = points.get(t[2]);
            double area = Math.abs((b[0] - a[0]) * (c[1] - a[1])
                    - (b[1] - a[1]) * (c[0] - a[0])) / 2.0;
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        int[] t = tris.get(best);
        double[] a = points.get(t[0]), b = points.get(t[1]), c = points.get(t[2]);
        return new double[]{(a[0] + b[0] + c[0]) / 3.0, (a[1] + b[1] + c[1]) / 3.0};
    }

    /* --- helpers (mirroring ConstrainedDelaunayTriangulator) ----------------- */

    /** neigh[3*i+j] = triangle across the edge opposite corner j, or -1. Skips
        null (dead) slots, so it serves both the one-time construction build and
        the {@link #adjacencyConsistent()} cross-check. */
    private int[] adjacency(List<int[]> ts) {
        int n = ts.size();
        int[] neigh = new int[3 * n];
        Arrays.fill(neigh, -1);
        Map<Long, int[]> edge = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] t = ts.get(i);
            if (t == null) {
                continue;
            }
            for (int j = 0; j < 3; j++) {
                long k = key(t[(j + 1) % 3], t[(j + 2) % 3]);
                int[] prev = edge.get(k);
                if (prev == null) {
                    edge.put(k, new int[]{i, j});
                } else {
                    neigh[3 * i + j] = prev[0];
                    neigh[3 * prev[0] + prev[1]] = i;
                }
            }
        }
        return neigh;
    }

    private int locate(double x, double y) {
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            if (t == null) {
                continue;
            }
            int s1 = orientXY(t[0], t[1], x, y);
            int s2 = orientXY(t[1], t[2], x, y);
            int s3 = orientXY(t[2], t[0], x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inCircle(int[] t, double[] p) {
        return Predicates.incircle(
                points.get(t[0])[0], points.get(t[0])[1],
                points.get(t[1])[0], points.get(t[1])[1],
                points.get(t[2])[0], points.get(t[2])[1],
                p[0], p[1]) > 0;
    }

    private int orientXY(int a, int b, double x, double y) {
        return Predicates.orient2d(points.get(a)[0], points.get(a)[1],
                points.get(b)[0], points.get(b)[1], x, y);
    }

    private static double dist2(double[] p, double[] q) {
        double dx = p[0] - q[0], dy = p[1] - q[1];
        return dx * dx + dy * dy;
    }

    private static long key(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
