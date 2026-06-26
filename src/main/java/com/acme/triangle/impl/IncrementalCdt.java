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
 * <p>
 * It is built from an existing {@link ConstrainedDelaunayTriangulator}
 * output, whose carved domain is bounded <em>entirely by segments</em> - that is
 * the property that makes interior insertion safe: an interior point's cavity
 * can never escape the domain without first hitting a segment.
 * <p>
 * Interior insertion is constrained Bowyer-Watson: starting from the triangle
 * containing the point, gather every triangle whose circumcircle contains it,
 * never crossing a segment; remove that cavity and re-fan it around the new
 * point. The result is locally Delaunay with no global rework. New triangles
 * inherit the cavity's region attribute (the cavity lies within one region,
 * since it does not cross a segment).
 * <p>
 * <b>Maintained adjacency (slice 5c-1).</b> Each triangle has a stable integer
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

    private final Points points;                              /* coordinates per vertex */
    private final List<Provenance> provenances = new ArrayList<>();  /* identity per vertex */
    private final List<Triangle> tris = new ArrayList<>();    /* one cell per slot; null if dead */
    private final boolean haveAttr;                           /* whether triangles carry a region attribute */
    private final List<Segment> segments = new ArrayList<>();
    private final Set<Long> segSet = new HashSet<>();
    /* For each segment edge, one live incident triangle id - so the apexes that
       decide encroachment are an O(1) adjacency hop, not an O(T) edge->apex
       rebuild. Maintained as the fan replaces incident triangles. */
    private final Map<Long, Integer> segTri = new HashMap<>();

    /* Dead slots awaiting reuse, and the count of live triangles. */
    private final Deque<Integer> freeSlots = new ArrayDeque<>();
    private int liveCount;

    /* The ids created by the most recent insertion/split (the new fan): the
       "dirty set" the refinement loop re-tests instead of rescanning the mesh. */
    private final List<Integer> lastFan = new ArrayList<>();

    /* Generation-stamped cavity membership, so gathering a cavity is O(cavity)
       (never a full-size clear): a slot is in the current cavity iff its stamp
       equals the current generation. */
    private int[] cavityGen;
    private int gen;

    IncrementalCdt(TriangleMesherOutput base) {
        points = new Points(base.pointList, base.numberOfPoints);
        for (int i = 0; i < base.numberOfPoints; i++) {
            provenances.add(new Provenance(VertexType.INPUT, -1, -1));
        }
        haveAttr = base.triangleAttributeList != null;
        for (int i = 0; i < base.numberOfTriangles; i++) {     /* corners now, links below */
            double attr = haveAttr ? base.triangleAttributeList[i] : 0.0;
            tris.add(new Triangle(base.triangleList[3 * i], base.triangleList[3 * i + 1],
                    base.triangleList[3 * i + 2], -1, -1, -1, attr));
        }
        int[] adj = adjacency(tris);             /* one-time global build at construction */
        for (int i = 0; i < base.numberOfTriangles; i++) {
            Triangle t = tris.get(i);
            t.n0 = adj[3 * i];
            t.n1 = adj[3 * i + 1];
            t.n2 = adj[3 * i + 2];
        }
        liveCount = base.numberOfTriangles;
        cavityGen = new int[Math.max(16, tris.size())];
        if (base.segmentList != null) {
            for (int i = 0; i < base.numberOfSegments; i++) {
                int a = base.segmentList[2 * i], b = base.segmentList[2 * i + 1];
                int marker = base.segmentMarkerList != null ? base.segmentMarkerList[i] : 0;
                segments.add(new Segment(a, b, marker, a, b));   /* original endpoints = a, b */
                segSet.add(key(a, b));
            }
        }
        for (int i = 0; i < tris.size(); i++) {            /* seed segment->triangle */
            Triangle t = tris.get(i);
            for (int j = 0; j < 3; j++) {
                long k = key(t.corner(j), t.corner((j + 1) % 3));
                if (segSet.contains(k)) {
                    segTri.put(k, i);
                }
            }
        }
    }

    /** The provenance (kind, and original segment for a split-segment vertex) of
        vertex {@code i}. */
    Provenance provenance(int i) {
        return provenances.get(i);
    }

    /* Live views for the refinement loop, so it reads the mesh in place rather
       than snapshotting it every iteration. With maintained adjacency these lists
       are mutated in place (slots reused/nulled), so the references are stable;
       dead triangles appear as null and scanning consumers must skip them. */

    Points pointsView() {
        return points;
    }

    List<Triangle> trianglesView() {
        return tris;
    }

    List<Segment> segmentsView() {
        return segments;
    }

    /** Whether triangles carry a region attribute (read via {@link Triangle#attr}). */
    boolean hasAttributes() {
        return haveAttr;
    }

    /** Stable ids of the triangles created by the most recent insertion or
        segment split - the dirty set to re-test, so the refinement loop need not
        rescan the whole mesh. Valid until the next mutation. */
    List<Integer> lastFanTriangles() {
        return lastFan;
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
    int insertInteriorPoint(Point p) {
        int start = locate(p.x, p.y);
        if (start < 0) {
            throw new IllegalArgumentException("point is not inside the domain");
        }
        int pIdx = points.add(p);
        provenances.add(new Provenance(VertexType.FREE, -1, -1));
        insertViaCavity(pIdx, new int[]{start}, -1L);
        return pIdx;
    }

    /**
     * Split segment {@code segIndex} at its midpoint (Ruppert's subsegment
     * split): insert the midpoint as a vertex and replace the segment with its
     * two halves.
     * <p>
     * The midpoint is inserted with the same cavity machinery, seeded from the
     * triangles incident to the segment (robust - no point-location test on the
     * rounded midpoint). The old segment is dropped from the constraint set
     * first, so the cavity may span both of its sides - an interior
     * region-boundary segment splits both regions at once - and the two halves
     * are registered afterwards.
     *
     * @return the new midpoint vertex index
     */
    int splitSegment(int segIndex) {
        Segment seg = segments.get(segIndex);
        int a = seg.a, b = seg.b, marker = seg.marker, origOrg = seg.origOrg, origDest = seg.origDest;
        int[] seeds = incidentTriangles(a, b);
        if (seeds.length == 0) {
            throw new IllegalStateException("segment (" + a + "," + b + ") is not an edge");
        }
        double frac = shellSplitFraction(a, b);
        double ax = points.x(a), ay = points.y(a), bx = points.x(b), by = points.y(b);
        int mIdx = points.add(ax + frac * (bx - ax), ay + frac * (by - ay));
        provenances.add(new Provenance(VertexType.SEGMENT, origOrg, origDest));

        segSet.remove(key(a, b));            /* let the cavity span the old segment */
        segTri.remove(key(a, b));
        insertViaCavity(mIdx, seeds, key(a, b));

        segments.set(segIndex, new Segment(a, mIdx, marker, origOrg, origDest));
        segments.add(new Segment(mIdx, b, marker, origOrg, origDest));
        segSet.add(key(a, mIdx));
        segSet.add(key(mIdx, b));
        /* The halves are new segments incident to mIdx; back each with one of the
           fan triangles just created (the re-fan only indexes pre-existing outer
           edges, so these are registered here). */
        indexSegmentTriangle(a, mIdx);
        indexSegmentTriangle(mIdx, b);
        return mIdx;
    }

    /** Point {@link #segTri} for edge (a,b) at any live triangle in the last fan
        that carries it. */
    private void indexSegmentTriangle(int a, int b) {
        for (int id : lastFan) {
            Triangle t = tris.get(id);
            boolean ha = t.a == a || t.b == a || t.c == a;
            boolean hb = t.a == b || t.b == b || t.c == b;
            if (ha && hb) {
                segTri.put(key(a, b), id);
                return;
            }
        }
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
        boolean acuteOrg = provenances.get(a).type == VertexType.INPUT;
        boolean acuteDest = provenances.get(b).type == VertexType.INPUT;
        if (!acuteOrg && !acuteDest) {
            return 0.5;
        }
        double len = Math.sqrt(dist2(a, b));
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
     * <p>
     * The fan is built as {@code {u, w, p}} with the inserted vertex {@code p}
     * always at corner 2: the boundary edge {@code (u,w)} is then opposite corner
     * 2 (slot 2 -&gt; the outer-ring neighbour), and the two interior fan edges are
     * {@code (p,u)} at slot 1 and {@code (w,p)} at slot 0. Adjacent fan triangles
     * are paired by shared boundary vertex - each appears once as a {@code u}
     * (slot-1 edge) and once as a {@code w} (slot-0 edge) - and the outer-ring
     * neighbour's link back into the cavity is repointed to the new triangle.
     */
    private void insertViaCavity(int pIdx, int[] seeds, long skipEdgeKey) {
        Point p = points.at(pIdx);
        gen++;
        lastFan.clear();
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
            Triangle tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = tc.neighbor(j);
                if (nb < 0 || cavityGen[nb] == gen) {
                    continue;
                }
                int u = tc.corner((j + 1) % 3), w = tc.corner((j + 2) % 3);
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
        List<BoundaryEdge> fan = new ArrayList<>();
        for (int t : cavity) {
            Triangle tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = tc.neighbor(j);
                int u = tc.corner((j + 1) % 3), w = tc.corner((j + 2) % 3);
                long k = key(u, w);
                boolean boundary = nb < 0 || cavityGen[nb] != gen || segSet.contains(k);
                if (boundary && k != skipEdgeKey) {
                    fan.add(new BoundaryEdge(u, w, nb, tc.attr));
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
        for (BoundaryEdge f : fan) {
            int u = f.u, w = f.w, nb = f.nb;
            int id = allocSlot(new Triangle(u, w, pIdx, -1, -1, nb, f.attr));
            lastFan.add(id);
            long uw = key(u, w);
            if (segSet.contains(uw)) {                      /* this fan tri now backs the segment */
                segTri.put(uw, id);
            }
            if (nb >= 0) {                                  /* repoint the outer ring */
                Triangle nc = tris.get(nb);
                for (int k = 0; k < 3; k++) {
                    if (nc.corner(k) != u && nc.corner(k) != w) {
                        nc.setNeighbor(k, id);              /* edge opposite the apex is (u,w) */
                        break;
                    }
                }
            }
            Integer mu = asW.get(u);
            if (mu != null) {                               /* shares the (p,u) edge */
                tris.get(id).n1 = mu;
                tris.get(mu).n0 = id;
            }
            asU.put(u, id);
            Integer mw = asU.get(w);
            if (mw != null) {                               /* shares the (w,p) edge */
                tris.get(id).n0 = mw;
                tris.get(mw).n1 = id;
            }
            asW.put(w, id);
        }
    }

    /** A cavity-boundary edge to re-fan: the oriented edge (u, w), the outer
        neighbour slot across it (or -1), and the region attribute inherited from
        the cavity triangle it came from. */
    private static final class BoundaryEdge {
        final int u, w, nb;
        final double attr;

        BoundaryEdge(int u, int w, int nb, double attr) {
            this.u = u;
            this.w = w;
            this.nb = nb;
            this.attr = attr;
        }
    }

    /** Reuse a dead slot if any, else append; returns the slot id. */
    private int allocSlot(Triangle t) {
        int id;
        if (!freeSlots.isEmpty()) {
            id = freeSlots.pop();
            tris.set(id, t);
        } else {
            id = tris.size();
            tris.add(t);
            ensureCavityGen(id + 1);
        }
        liveCount++;
        return id;
    }

    private void freeSlot(int id) {
        tris.set(id, null);
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
            Triangle t = tris.get(i);
            if (t == null) {
                continue;
            }
            boolean ha = t.a == a || t.b == a || t.c == a;
            boolean hb = t.a == b || t.b == b || t.c == b;
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

    /**
     * The opposite corners of the (one or two) triangles incident to segment edge
     * {@code (a,b)} - the apexes whose position decides whether the subsegment is
     * encroached. Uses the maintained {@link #segTri} index plus one adjacency hop,
     * so it is O(1) rather than a mesh scan. A boundary segment has one apex; the
     * second slot is then -1. Returns {-1, -1} if (a,b) is not a known segment.
     */
    int[] apexesOfSegment(int a, int b) {
        Integer t1 = segTri.get(key(a, b));
        if (t1 == null) {
            return new int[]{-1, -1};
        }
        Triangle tc = tris.get(t1);
        int apex1 = third(tc, a, b);
        int slot = tc.a == apex1 ? 0 : tc.b == apex1 ? 1 : 2;     /* edge (a,b) opp the apex */
        int nb = tc.neighbor(slot);
        int apex2 = nb < 0 ? -1 : third(tris.get(nb), a, b);
        return new int[]{apex1, apex2};
    }

    /** The corner of {@code tc} that is neither {@code a} nor {@code b}. */
    private static int third(Triangle tc, int a, int b) {
        if (tc.a != a && tc.a != b) {
            return tc.a;
        }
        return tc.b != a && tc.b != b ? tc.b : tc.c;
    }

    TriangleMesherOutput toOutput() {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = points.size();
        o.pointList = points.toArray();
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
        if (haveAttr) {
            o.triangleAttributeList = new double[nt];
        }
        for (int i = 0; i < tris.size(); i++) {
            Triangle tc = tris.get(i);
            if (tc == null) {
                continue;
            }
            int ni = remap[i];
            o.triangleList[3 * ni] = tc.a;
            o.triangleList[3 * ni + 1] = tc.b;
            o.triangleList[3 * ni + 2] = tc.c;
            for (int j = 0; j < 3; j++) {
                int nb = tc.neighbor(j);
                o.neighborList[3 * ni + j] = nb < 0 ? -1 : remap[nb];
            }
            if (haveAttr) {
                o.triangleAttributeList[ni] = tc.attr;
            }
        }
        o.numberOfSegments = segments.size();
        o.segmentList = new int[2 * segments.size()];
        o.segmentMarkerList = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            o.segmentList[2 * i] = s.a;
            o.segmentList[2 * i + 1] = s.b;
            o.segmentMarkerList[i] = s.marker;
        }
        return o;
    }

    /**
     * Debug oracle (used by tests): the maintained neighbour links equal a
     * from-scratch rebuild over the live triangles. Cross-checks the hand-relinked
     * adjacency directly, with tighter localization than the output validator.
     */
    boolean adjacencyConsistent() {
        List<Triangle> live = new ArrayList<>();
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
            Triangle t = tris.get(i);
            if (t == null) {
                continue;
            }
            int ni = remap[i];
            for (int j = 0; j < 3; j++) {
                int nb = t.neighbor(j);
                int maint = nb < 0 ? -1 : remap[nb];
                if (maint != fresh[3 * ni + j]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Centroid of the current largest-area triangle (a robust interior point). */
    Point centroidOfLargestTriangle() {
        int best = -1;
        double bestArea = -1;
        for (int i = 0; i < tris.size(); i++) {
            Triangle t = tris.get(i);
            if (t == null) {
                continue;
            }
            double area = Math.abs((points.x(t.b) - points.x(t.a)) * (points.y(t.c) - points.y(t.a))
                    - (points.y(t.b) - points.y(t.a)) * (points.x(t.c) - points.x(t.a))) / 2.0;
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        Triangle t = tris.get(best);
        double cx = (points.x(t.a) + points.x(t.b) + points.x(t.c)) / 3.0;
        double cy = (points.y(t.a) + points.y(t.b) + points.y(t.c)) / 3.0;
        return new Point(cx, cy);
    }

    /* --- helpers (mirroring ConstrainedDelaunayTriangulator) ----------------- */

    /** Triangle adjacency over the live triangles {@code ts}, delegating to the
        shared {@link Topology#neighbors}. Callers pass a null-free list: the
        construction build runs before any slot is freed, and
        {@link #adjacencyConsistent()} compacts the live triangles first. */
    private int[] adjacency(List<Triangle> ts) {
        return Topology.neighbors(ts.size(), (i, c) -> ts.get(i).corner(c));
    }

    private int locate(double x, double y) {
        for (int i = 0; i < tris.size(); i++) {
            Triangle t = tris.get(i);
            if (t == null) {
                continue;
            }
            int s1 = orientXY(t.a, t.b, x, y);
            int s2 = orientXY(t.b, t.c, x, y);
            int s3 = orientXY(t.c, t.a, x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inCircle(Triangle t, Point p) {
        return Predicates.incircle(
                points.x(t.a), points.y(t.a),
                points.x(t.b), points.y(t.b),
                points.x(t.c), points.y(t.c),
                p.x, p.y) > 0;
    }

    private int orientXY(int a, int b, double x, double y) {
        return Predicates.orient2d(points.x(a), points.y(a),
                points.x(b), points.y(b), x, y);
    }

    private double dist2(int a, int b) {
        double dx = points.x(a) - points.x(b), dy = points.y(a) - points.y(b);
        return dx * dx + dy * dy;
    }

    private static long key(int a, int b) {
        return Topology.edgeKey(a, b);
    }
}
