package com.acme.triangle.impl;

import com.acme.triangle.Constraint;
import com.acme.triangle.ImmutableTriangle;
import com.acme.triangle.Point;
import com.acme.triangle.Points;
import com.acme.triangle.Triangle;
import com.acme.triangle.TriangleMesherOutput2;
import org.jspecify.annotations.Nullable;

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
    private final List<@Nullable Triangle> tris = new ArrayList<>();    /* one cell per slot; null if dead */
    private final boolean haveAttr;                           /* whether triangles carry a region attribute */
    private final List<Segment> segments = new ArrayList<>();
    private final Set<Long> segSet = new HashSet<>();
    /* For each segment edge, one live incident triangle id - so the apexes that
       decide encroachment are an O(1) adjacency hop, not an O(T) edge->apex
       rebuild. Maintained as the fan replaces incident triangles. */
    private final Map<Long, Integer> segTri = new HashMap<>();
    /* Each segment edge -> its index in `segments`, so an encroached subsegment
       found by edge can be split by index (splitting keeps the first half at its
       index). Maintained alongside segSet. */
    private final Map<Long, Integer> segIndexByEdge = new HashMap<>();
    /* Work queue of subsegments that may be encroached (segment indices) - a
       superset of the truly-encroached set, so emptying it proves none is
       encroached. Seeded with every subsegment, then refilled with the new fan's
       subsegments after each split; an interior insertion never newly encroaches
       (its point is rejected if it would), so none is needed there. Candidates are
       validated on poll, so stale or now-clean entries are simply discarded - the
       O(S)-per-iteration full rescan becomes O(1) amortized. */
    private final Deque<Integer> encroachQueue = new ArrayDeque<>();

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

    IncrementalCdt(TriangleMesherOutput2 base) {
        /* Adopt the modelled stores the CDT just built and handed over (it is a
           transient, used by nobody else): the growable Points becomes the mesh's
           vertex store, and the triangles already carry compacted neighbour links,
           so construction relinks nothing - no from-scratch adjacency rebuild. */
        points = base.getPoints();
        for (int i = 0; i < points.size(); i++) {
            provenances.add(new Provenance(VertexType.INPUT, -1, -1));
        }
        List<ImmutableTriangle> bt = base.getTriangles();
        haveAttr = base.hasAttributes();
        for (ImmutableTriangle it : bt) {
            tris.add(new Triangle(it.getA(), it.getB(), it.getC(),
                    it.getN0(), it.getN1(), it.getN2(), it.getAttr()));
        }
        liveCount = bt.size();
        cavityGen = new int[Math.max(16, tris.size())];
        for (Constraint c : base.getSegments()) {
            int idx = segments.size();
            segments.add(new Segment(c.getA(), c.getB(), c.getMarker(), c.getA(), c.getB()));  /* original endpoints = a, b */
            segSet.add(key(c.getA(), c.getB()));
            segIndexByEdge.put(key(c.getA(), c.getB()), idx);
            encroachQueue.add(idx);                  /* seed: every subsegment is a candidate */
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

    /**
     * Whether triangles carry a region attribute (read via {@link Triangle#getAttr()}).
     */
    boolean hasAttributes() {
        return haveAttr;
    }

    /**
     * Stable ids of the triangles created by the most recent insertion or
     * segment split - the dirty set to re-test, so the refinement loop need not
     * rescan the whole mesh. Valid until the next mutation.
     */
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
     * via constrained Bowyer-Watson, locating its containing triangle by a full
     * scan. The refinement hot path uses this method instead, seeding from the
     * triangle it already holds; this overload is for callers inserting an
     * arbitrary interior point with no triangle in hand.
     *
     * @return the new vertex index
     */
    int insertInteriorPoint(Point p) {
        int start = locate(p.getX(), p.getY());
        if (start < 0) {
            throw new IllegalArgumentException("point is not inside the domain");
        }
        int pIdx = points.add(p);
        provenances.add(new Provenance(VertexType.FREE, -1, -1));
        insertViaCavity(pIdx, new int[]{start}, -1L);
        return pIdx;
    }

    /**
     * Try to insert an interior off-centre {@code p}, seeding the cavity from
     * {@code seedTriangle} - the bad triangle whose off-centre {@code p} is, so it
     * holds {@code p} in its circumcircle (no point location needed). Folds
     * Ruppert's "do not insert a point that encroaches a subsegment" guard into
     * the cavity gather, which is what makes it local: when no subsegment is
     * currently encroached (the precondition for refining a triangle), every
     * subsegment {@code p} could encroach lies on {@code p}'s cavity boundary, so
     * the gather tests exactly those - O(cavity), not an O(S) scan of all
     * subsegments. (A subsegment whose {@code p}-side incident triangle did not
     * hold {@code p} in its circumcircle would have that triangle's apex inside
     * its diametral disk - i.e. it would already be encroached, hence split first.)
     *
     * @return {@code -1} if {@code p} was inserted; otherwise the index of a
     *         subsegment {@code p} encroaches, for the caller to split instead
     */
    int insertInteriorOrEncroachedSegment(Point p, int seedTriangle) {
        List<Integer> cavity = new ArrayList<>();
        List<BoundaryEdge> fan = new ArrayList<>();
        int encroached = gatherCavity(p, new int[]{seedTriangle}, -1L, cavity, fan, true);
        if (encroached >= 0) {
            return encroached;                  /* p encroaches a subsegment; do not insert */
        }
        int pIdx = points.add(p);
        provenances.add(new Provenance(VertexType.FREE, -1, -1));
        commitCavity(pIdx, cavity, fan);
        return -1;
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
        segIndexByEdge.remove(key(a, b));
        insertViaCavity(mIdx, seeds, key(a, b));

        int secondIndex = segments.size();
        segments.set(segIndex, new Segment(a, mIdx, marker, origOrg, origDest));
        segments.add(new Segment(mIdx, b, marker, origOrg, origDest));
        segSet.add(key(a, mIdx));
        segSet.add(key(mIdx, b));
        segIndexByEdge.put(key(a, mIdx), segIndex);    /* first half keeps this index */
        segIndexByEdge.put(key(mIdx, b), secondIndex);
        /* The halves are new segments incident to mIdx; back each with one of the
           fan triangles just created (the re-fan only indexes pre-existing outer
           edges, so these are registered here). */
        indexSegmentTriangle(a, mIdx);
        indexSegmentTriangle(mIdx, b);
        /* The midpoint can newly encroach subsegments on the fan; re-queue them
           (and the two halves) as encroachment candidates. */
        enqueueEncroachFan();
        return mIdx;
    }

    /** Point {@link #segTri} for edge (a,b) at any live triangle in the last fan
        that carries it. */
    private void indexSegmentTriangle(int a, int b) {
        for (int id : lastFan) {
            Triangle t = tris.get(id);
            boolean ha = t.getA() == a || t.getB() == a || t.getC() == a;
            boolean hb = t.getA() == b || t.getB() == b || t.getC() == b;
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
     * maintaining adjacency: gather its cavity ({@link #gatherCavity}) and re-fan
     * it ({@link #commitCavity}). The boundary edge equal to {@code skipEdgeKey} is
     * not re-fanned (used when splitting a boundary segment, where the split edge
     * would form a degenerate triangle); pass {@code -1L} for none.
     */
    private void insertViaCavity(int pIdx, int[] seeds, long skipEdgeKey) {
        List<Integer> cavity = new ArrayList<>();
        List<BoundaryEdge> fan = new ArrayList<>();
        gatherCavity(points.at(pIdx), seeds, skipEdgeKey, cavity, fan, false);
        commitCavity(pIdx, cavity, fan);
    }

    /**
     * Gather the constrained Bowyer-Watson cavity for point {@code p} (raw coords,
     * not yet a vertex), seeded from {@code seeds}: walk the maintained neighbour
     * links collecting every triangle whose circumcircle contains {@code p} - never
     * crossing a current segment. The cavity slots are appended to {@code cavity}
     * and its boundary edges (outer neighbour + region attribute) to {@code fan},
     * skipping {@code skipEdgeKey}. Read-only: no slot is freed, so an encroachment
     * result can abort the insertion with nothing to roll back.
     *
     * @return when {@code checkEncroach}, the index of a boundary subsegment whose
     *         diametral disk contains {@code p} (so {@code p} encroaches it), or
     *         {@code -1} for none; always {@code -1} when not checking
     */
    private int gatherCavity(Point p, int[] seeds, long skipEdgeKey,
                             List<Integer> cavity, List<BoundaryEdge> fan,
                             boolean checkEncroach) {
        gen++;
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

        /* Collect the cavity-boundary edges (outer neighbour + region attribute). A
           cavity edge is on the boundary when its neighbour is outside the cavity
           or it is a segment; the split edge (skipEdgeKey) is never re-fanned. A
           candidate point can only encroach a boundary subsegment, so test those
           here as they are found. */
        int encroached = -1;
        for (int t : cavity) {
            Triangle tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = tc.neighbor(j);
                int u = tc.corner((j + 1) % 3), w = tc.corner((j + 2) % 3);
                long k = key(u, w);
                boolean segment = segSet.contains(k);
                boolean boundary = nb < 0 || cavityGen[nb] != gen || segment;
                if (boundary && k != skipEdgeKey) {
                    fan.add(new BoundaryEdge(u, w, nb, tc.getAttr()));
                    if (checkEncroach && encroached < 0 && segment && inDiametralDisk(u, w, p)) {
                        Integer si = segIndexByEdge.get(k);
                        if (si != null) {
                            encroached = si;
                        }
                    }
                }
            }
        }
        return encroached;
    }

    /**
     * Re-fan the gathered {@code cavity} around the freshly added vertex {@code
     * pIdx}: free the cavity slots and relink adjacency locally, recording the new
     * triangles in {@link #lastFan}. Each new triangle inherits its source cavity
     * triangle's region attribute, so a cavity that spans two regions attributes
     * correctly.
     * <p>
     * The fan is built as {@code {u, w, p}} with {@code p} always at corner 2: the
     * boundary edge {@code (u,w)} is then opposite corner 2 (slot 2 -&gt; the
     * outer-ring neighbour), and the two interior fan edges are {@code (p,u)} at
     * slot 1 and {@code (w,p)} at slot 0. Adjacent fan triangles are paired by
     * shared boundary vertex - each appears once as a {@code u} (slot-1 edge) and
     * once as a {@code w} (slot-0 edge) - and the outer-ring neighbour's link back
     * into the cavity is repointed to the new triangle.
     */
    private void commitCavity(int pIdx, List<Integer> cavity, List<BoundaryEdge> fan) {
        lastFan.clear();
        for (int t : cavity) {
            freeSlot(t);
        }
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
                tris.get(id).setN1(mu);
                tris.get(mu).setN0(id);
            }
            asU.put(u, id);
            Integer mw = asU.get(w);
            if (mw != null) {                               /* shares the (w,p) edge */
                tris.get(id).setN0(mw);
                tris.get(mw).setN1(id);
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

    /** The (one or two) live triangles incident to segment edge {@code (a,b)}, via
        the maintained {@link #segTri} index plus one adjacency hop - O(1), not an
        O(T) mesh scan. Empty if {@code (a,b)} is not a current segment edge. */
    private int[] incidentTriangles(int a, int b) {
        Integer t1 = segTri.get(key(a, b));
        if (t1 == null) {
            return new int[0];
        }
        Triangle tc = tris.get(t1);
        int apex = third(tc, a, b);
        int slot = tc.getA() == apex ? 0 : tc.getB() == apex ? 1 : 2;   /* edge (a,b) opposite the apex */
        int t2 = tc.neighbor(slot);
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
        int slot = tc.getA() == apex1 ? 0 : tc.getB() == apex1 ? 1 : 2;     /* edge (a,b) opp the apex */
        int nb = tc.neighbor(slot);
        int apex2 = nb < 0 ? -1 : third(tris.get(nb), a, b);
        return new int[]{apex1, apex2};
    }

    /** The corner of {@code tc} that is neither {@code a} nor {@code b}. */
    private static int third(Triangle tc, int a, int b) {
        if (tc.getA() != a && tc.getA() != b) {
            return tc.getA();
        }
        return tc.getB() != a && tc.getB() != b ? tc.getB() : tc.getC();
    }

    /**
     * The index of a subsegment that is currently encroached - an incident
     * triangle apex lies in its diametral disk - or {@code -1} if none is. Drains
     * the candidate {@link #encroachQueue}, discarding entries that are not (a
     * subsegment's encroachment changes only when its incident triangles do, and
     * the queue is refilled at exactly those points), so this is O(1) amortized in
     * place of a full O(S) rescan every refinement iteration.
     */
    int pollEncroachedSubsegment() {
        while (!encroachQueue.isEmpty()) {
            int s = encroachQueue.poll();
            Segment seg = segments.get(s);
            for (int apex : apexesOfSegment(seg.a, seg.b)) {
                if (apex >= 0 && inDiametralDisk(seg.a, seg.b, apex)) {
                    return s;
                }
            }
        }
        return -1;
    }

    /** Re-queue every subsegment among the most recent fan as an encroachment
        candidate - the only subsegments whose incident apex the mutation changed. */
    private void enqueueEncroachFan() {
        for (int id : lastFan) {
            Triangle t = tris.get(id);
            for (int j = 0; j < 3; j++) {
                Integer s = segIndexByEdge.get(key(t.corner(j), t.corner((j + 1) % 3)));
                if (s != null) {
                    encroachQueue.add(s);
                }
            }
        }
    }

    /** Whether vertex {@code p} lies in the diametral disk of subsegment
        {@code (a,b)} - i.e. the angle a-p-b is obtuse, so {@code (a,b)} is
        encroached by {@code p}. */
    private boolean inDiametralDisk(int a, int b, int p) {
        double pax = points.x(a), pay = points.y(a);
        double pbx = points.x(b), pby = points.y(b);
        double px = points.x(p), py = points.y(p);
        return (px - pax) * (px - pbx) + (py - pay) * (py - pby) < 0;
    }

    /** Whether the loose point {@code p} (a candidate vertex) lies in the diametral
        disk of subsegment {@code (a,b)} - so inserting it would encroach. */
    private boolean inDiametralDisk(int a, int b, Point p) {
        double pax = points.x(a), pay = points.y(a);
        double pbx = points.x(b), pby = points.y(b);
        return (p.getX() - pax) * (p.getX() - pbx) + (p.getY() - pay) * (p.getY() - pby) < 0;
    }

    TriangleMesherOutput2 toOutput() {
        /* Drop dead slots and remap neighbour links to the compacted indexing; the
           live triangles (mutable cells) are read through the ImmutableTriangle
           view they implement. */
        List<ImmutableTriangle> outTriangles = TriangleUtils.compact(tris);

        List<Constraint> outSegments = new ArrayList<>(segments.size());
        for (Segment sg : segments) {
            outSegments.add(new Constraint(sg.a, sg.b, sg.marker));
        }

        /* A fresh, tight copy of the live coordinates - the mesh keeps its own
           growable store rather than handing out its mutable internals. */
        Points outPoints = new Points(points.toArray(), points.size());
        return new TriangleMesherOutput2(outPoints, outTriangles, outSegments, haveAttr);
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
            double area = Math.abs((points.x(t.getB()) - points.x(t.getA())) * (points.y(t.getC()) - points.y(t.getA()))
                    - (points.y(t.getB()) - points.y(t.getA())) * (points.x(t.getC()) - points.x(t.getA()))) / 2.0;
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        Triangle t = tris.get(best);
        double cx = (points.x(t.getA()) + points.x(t.getB()) + points.x(t.getC())) / 3.0;
        double cy = (points.y(t.getA()) + points.y(t.getB()) + points.y(t.getC())) / 3.0;
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
            int s1 = orientXY(t.getA(), t.getB(), x, y);
            int s2 = orientXY(t.getB(), t.getC(), x, y);
            int s3 = orientXY(t.getC(), t.getA(), x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inCircle(Triangle t, Point p) {
        return Geometry.inCircle(points, t.getA(), t.getB(), t.getC(), p);
    }

    private int orientXY(int a, int b, double x, double y) {
        return Geometry.orient2d(points, a, b, x, y);
    }

    private double dist2(int a, int b) {
        double dx = points.x(a) - points.x(b), dy = points.y(a) - points.y(b);
        return dx * dx + dy * dy;
    }

    private static long key(int a, int b) {
        return Topology.edgeKey(a, b);
    }
}
