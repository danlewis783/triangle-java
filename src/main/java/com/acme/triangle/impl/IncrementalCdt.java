package com.acme.triangle.impl;

import com.acme.triangle.Constraint;
import com.acme.triangle.ImmutableTriangle;
import com.acme.triangle.Point;
import com.acme.triangle.TriangleMesherOutput2;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * id (its slot index in the {@link FlatTriangleList} arena) and carries its own
 * three neighbour ids; insertion walks the cavity through those links and
 * relinks the new fan locally, so it is O(cavity) instead of rebuilding global
 * adjacency from a {@code HashMap} every time. Deleted triangles are marked
 * dead in the arena and their slots reused, so scanning consumers skip
 * non-live slots. The mesh is compacted only when {@link #toOutput()} builds
 * the final result, which emits the maintained adjacency directly (so the
 * {@code MeshValidator} neighbour-slot invariants are a precise oracle for the
 * surgery here).
 */
final class IncrementalCdt {

    private final FlatPointList points;                   /* coordinates per vertex */
    private final List<Provenance> provenances = new ArrayList<>();  /* identity per vertex */
    private final FlatTriangleList tris;                  /* the triangle arena */
    private final boolean haveAttr;                           /* whether triangles carry a region attribute */
    private final List<Segment> segments = new ArrayList<>();
    /* For each segment edge, one live incident triangle id - so the apexes that
       decide encroachment are an O(1) adjacency hop, not an O(T) edge->apex
       rebuild. Maintained as the fan replaces incident triangles. Primitive maps
       (get returns -1 when absent): the cavity gather probes an edge key per
       neighbour test, and boxed maps there dominated the refinement profile. */
    private final Long2IntOpenHashMap segTri = newEdgeMap();
    /* Each segment edge -> its index in `segments`, so an encroached subsegment
       found by edge can be split by index (splitting keeps the first half at its
       index). Its key set is exactly the current segment edges, so it doubles as
       the "is this edge a segment?" set for the cavity gather. */
    private final Long2IntOpenHashMap segIndexByEdge = newEdgeMap();

    private static Long2IntOpenHashMap newEdgeMap() {
        Long2IntOpenHashMap m = new Long2IntOpenHashMap(64, Hash.FAST_LOAD_FACTOR);
        m.defaultReturnValue(-1);
        return m;
    }

    /* Work queue of subsegments that may be encroached (segment indices) - a
       superset of the truly-encroached set, so emptying it proves none is
       encroached. Seeded with every subsegment, then refilled with the new fan's
       subsegments after each split; an interior insertion never newly encroaches
       (its point is rejected if it would), so none is needed there. Candidates are
       validated on poll, so stale or now-clean entries are simply discarded - the
       O(S)-per-iteration full rescan becomes O(1) amortized. */
    private final IntArrayFIFOQueue encroachQueue = new IntArrayFIFOQueue();

    /* The ids created by the most recent insertion/split (the new fan): the
       "dirty set" the refinement loop re-tests instead of rescanning the mesh. */
    private final IntArrayList lastFan = new IntArrayList();

    /* Generation-stamped cavity membership, so gathering a cavity is O(cavity)
       (never a full-size clear): a slot is in the current cavity iff its stamp
       equals the current generation. */
    private int[] cavityGen;
    private int gen;

    /* Generation-stamped fan-linking scratch (vertex -> the new fan triangle
       seeing it as u/w), replacing two boxed HashMaps per insertion; entries are
       valid iff their stamp equals the gather's generation. */
    private int[] fanAsU = new int[0];
    private int[] fanAsUGen = new int[0];
    private int[] fanAsW = new int[0];
    private int[] fanAsWGen = new int[0];

    /* Reusable per-insertion scratch, cleared at each gather: the flood stack
       the gather walks (a boxed ArrayDeque here was measurable in the JFR
       profile), the cavity slots it collects, and the boundary fan the commit
       consumes. */
    private final IntArrayList flood = new IntArrayList(64);
    private final IntArrayList cavityScratch = new IntArrayList();
    private final List<BoundaryEdge> fanScratch = new ArrayList<>();

    /* Encroachment lens factor: subsegment (a,b) is encroached by p iff the
       angle a-p-b is obtuse AND cos^2 of it is at least this - i.e. p lies in
       the diametral *lens* determined by the angle bound (Triangle's default
       test, triangle.c:3925: apex angle >= 180 - 2*minangle degrees). 0 is the
       plain diametral circle (any obtuse apex angle); (2cos^2(minangle) - 1)^2
       is Triangle's lens, which tolerates blunter apexes and so splits far
       fewer subsegments near closely spaced boundaries. */
    private final double lensFactor;

    /** Diametral-circle encroachment (conservative; structural tests use this). */
    IncrementalCdt(TriangleMesherOutput2 base) {
        this(base, 45.0);           /* cos^2(45) = 0.5 makes the lens the circle */
    }

    /** Encroachment via Triangle's diametral lens for the given quality bound
        (the bound the refinement loop is meshing toward). */
    IncrementalCdt(TriangleMesherOutput2 base, double minAngleDegrees) {
        double g = Math.cos(Math.toRadians(minAngleDegrees));
        g = g * g;
        lensFactor = (2.0 * g - 1.0) * (2.0 * g - 1.0);
        /* Adopt the modelled stores the CDT just built and handed over (it is a
           transient, used by nobody else): the modelled point list seeds the
           mesh's growable flat vertex store, and the triangles already carry
           compacted neighbour links, so construction relinks nothing - no
           from-scratch adjacency rebuild. */
        points = FlatPointList.copyOf(base.getPoints());
        for (int i = 0; i < points.size(); i++) {
            provenances.add(new Provenance(VertexType.INPUT, -1, -1));
        }
        List<ImmutableTriangle> bt = base.getTriangles();
        haveAttr = base.hasAttributes();
        tris = new FlatTriangleList(bt.size());
        for (ImmutableTriangle it : bt) {
            tris.alloc(it.getA(), it.getB(), it.getC(),
                    it.getN0(), it.getN1(), it.getN2(), it.getAttr());
        }
        cavityGen = new int[Math.max(16, tris.slotCount())];
        for (Constraint c : base.getSegments()) {
            int idx = segments.size();
            segments.add(new Segment(c.getA(), c.getB(), c.getMarker(), c.getA(), c.getB()));  /* original endpoints = a, b */
            segIndexByEdge.put(key(c.getA(), c.getB()), idx);
            encroachQueue.enqueue(idx);              /* seed: every subsegment is a candidate */
        }
        for (int i = 0; i < tris.slotCount(); i++) {       /* seed segment->triangle */
            for (int j = 0; j < 3; j++) {
                long k = key(tris.corner(i, j), tris.corner(i, (j + 1) % 3));
                if (segIndexByEdge.containsKey(k)) {
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
       than snapshotting it every iteration. With maintained adjacency these
       stores are mutated in place (slots reused/freed), so the references are
       stable; scanning consumers must skip non-live slots. */

    FlatPointList points() {
        return points;
    }

    FlatTriangleList triangles() {
        return tris;
    }

    /**
     * Whether triangles carry a region attribute (read via
     * {@link FlatTriangleList#attr}).
     */
    boolean hasAttributes() {
        return haveAttr;
    }

    /**
     * Stable ids of the triangles created by the most recent insertion or
     * segment split - the dirty set to re-test, so the refinement loop need not
     * rescan the whole mesh. Valid until the next mutation.
     */
    IntList lastFanTriangles() {
        return lastFan;
    }

    int pointCount() {
        return points.size();
    }

    int triangleCount() {
        return tris.liveCount();
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
        int pIdx = points.add(p.getX(), p.getY());
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
    int insertInteriorOrEncroachedSegment(double px, double py, int seedTriangle) {
        int encroached = gatherCavity(px, py, new int[]{seedTriangle}, -1L, true);
        if (encroached >= 0) {
            return encroached;                  /* p encroaches a subsegment; do not insert */
        }
        int pIdx = points.add(px, py);
        provenances.add(new Provenance(VertexType.FREE, -1, -1));
        commitCavity(pIdx);
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
        int a = seg.a;
        int b = seg.b;
        int marker = seg.marker;
        int origOrg = seg.origOrg;
        int origDest = seg.origDest;
        int[] seeds = incidentTriangles(a, b);
        if (seeds.length == 0) {
            throw new IllegalStateException("segment (" + a + "," + b + ") is not an edge");
        }
        double frac = shellSplitFraction(a, b);
        double ax = points.x(a);
        double ay = points.y(a);
        double bx = points.x(b);
        double by = points.y(b);
        int mIdx = points.add(ax + frac * (bx - ax), ay + frac * (by - ay));
        provenances.add(new Provenance(VertexType.SEGMENT, origOrg, origDest));

        segTri.remove(key(a, b));            /* let the cavity span the old segment */
        segIndexByEdge.remove(key(a, b));
        insertViaCavity(mIdx, seeds, key(a, b));

        int secondIndex = segments.size();
        segments.set(segIndex, new Segment(a, mIdx, marker, origOrg, origDest));
        segments.add(new Segment(mIdx, b, marker, origOrg, origDest));
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
        for (int i = 0; i < lastFan.size(); i++) {
            int id = lastFan.getInt(i);
            if (tris.hasCorner(id, a) && tris.hasCorner(id, b)) {
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
        gatherCavity(points.x(pIdx), points.y(pIdx), seeds, skipEdgeKey, false);
        commitCavity(pIdx);
    }

    /**
     * Gather the constrained Bowyer-Watson cavity for the point {@code (px, py)}
     * (raw coords, not necessarily a vertex yet), seeded from {@code seeds}: walk
     * the maintained neighbour links collecting every triangle whose circumcircle
     * contains the point - never crossing a current segment. The cavity slots are
     * collected into {@link #cavityScratch} and its boundary edges (outer
     * neighbour + region attribute) into {@link #fanScratch}, skipping
     * {@code skipEdgeKey}. Read-only: no slot is freed, so an encroachment result
     * can abort the insertion with nothing to roll back.
     *
     * @return when {@code checkEncroach}, the index of a boundary subsegment whose
     *         diametral lens contains the point (so it encroaches), or {@code -1}
     *         for none; always {@code -1} when not checking
     */
    private int gatherCavity(double px, double py, int[] seeds, long skipEdgeKey,
                             boolean checkEncroach) {
        gen++;
        IntArrayList cavity = cavityScratch;
        List<BoundaryEdge> fan = fanScratch;
        cavity.clear();
        fan.clear();
        for (int s : seeds) {
            if (cavityGen[s] != gen) {
                cavityGen[s] = gen;
                flood.push(s);
            }
        }
        while (!flood.isEmpty()) {
            int t = flood.popInt();
            cavity.add(t);
            for (int j = 0; j < 3; j++) {
                int nb = tris.neighbor(t, j);
                if (nb < 0 || cavityGen[nb] == gen) {
                    continue;
                }
                int u = tris.corner(t, (j + 1) % 3);
                int w = tris.corner(t, (j + 2) % 3);
                if (segIndexByEdge.containsKey(key(u, w))) {
                    continue;                       /* never cross a segment */
                }
                if (inCircle(nb, px, py)) {
                    cavityGen[nb] = gen;
                    flood.push(nb);
                }
            }
        }

        /* Collect the cavity-boundary edges (outer neighbour + region attribute). A
           cavity edge is on the boundary when its neighbour is outside the cavity
           or it is a segment; the split edge (skipEdgeKey) is never re-fanned. A
           candidate point can only encroach a boundary subsegment, so test those
           here as they are found. */
        int encroached = -1;
        for (int i = 0; i < cavity.size(); i++) {
            int t = cavity.getInt(i);
            for (int j = 0; j < 3; j++) {
                int nb = tris.neighbor(t, j);
                int u = tris.corner(t, (j + 1) % 3);
                int w = tris.corner(t, (j + 2) % 3);
                long k = key(u, w);
                int segIndex = segIndexByEdge.get(k);
                boolean segment = segIndex >= 0;
                boolean boundary = nb < 0 || cavityGen[nb] != gen || segment;
                if (boundary && k != skipEdgeKey) {
                    fan.add(new BoundaryEdge(u, w, nb, tris.attr(t)));
                    /* p encroaches the subsegment (diametral lens), or sits on
                       or beyond it - outside the region the cavity re-fans, so
                       inserting would fold the fan. The latter is Triangle's
                       blocked-walk rejection (VIOLATINGVERTEX, triangle.c:8375):
                       split the blocking subsegment instead, whatever the lens
                       says. Non-segment boundary edges cannot be violated: the
                       carved domain is segment-bounded, and across an interior
                       edge the circumcircle containment propagates. */
                    if (checkEncroach && encroached < 0 && segment
                            && (inDiametralLens(u, w, px, py)
                                || orientXY(u, w, px, py) <= 0)) {
                        encroached = segIndex;
                    }
                }
            }
        }
        return encroached;
    }

    /**
     * Re-fan the gathered cavity ({@link #cavityScratch}/{@link #fanScratch})
     * around the freshly added vertex {@code pIdx}: free the cavity slots and
     * relink adjacency locally, recording the new triangles in {@link #lastFan}.
     * Each new triangle inherits its source cavity triangle's region attribute,
     * so a cavity that spans two regions attributes correctly.
     * <p>
     * The fan is built as {@code {u, w, p}} with {@code p} always at corner 2: the
     * boundary edge {@code (u,w)} is then opposite corner 2 (slot 2 -&gt; the
     * outer-ring neighbour), and the two interior fan edges are {@code (p,u)} at
     * slot 1 and {@code (w,p)} at slot 0. Adjacent fan triangles are paired by
     * shared boundary vertex - each appears once as a {@code u} (slot-1 edge) and
     * once as a {@code w} (slot-0 edge) - and the outer-ring neighbour's link back
     * into the cavity is repointed to the new triangle.
     */
    private void commitCavity(int pIdx) {
        lastFan.clear();
        IntArrayList cavity = cavityScratch;
        for (int i = 0; i < cavity.size(); i++) {
            tris.free(cavity.getInt(i));
        }
        ensureFanScratch(points.size());
        for (BoundaryEdge f : fanScratch) {
            int u = f.u;
            int w = f.w;
            int nb = f.nb;
            int id = tris.alloc(u, w, pIdx, -1, -1, nb, f.attr);
            ensureCavityGen(tris.slotCount());
            lastFan.add(id);
            long uw = key(u, w);
            if (segIndexByEdge.containsKey(uw)) {              /* this fan tri now backs the segment */
                segTri.put(uw, id);
            }
            if (nb >= 0) {                                  /* repoint the outer ring */
                for (int k = 0; k < 3; k++) {
                    if (tris.corner(nb, k) != u && tris.corner(nb, k) != w) {
                        tris.setNeighbor(nb, k, id);        /* edge opposite the apex is (u,w) */
                        break;
                    }
                }
            }
            if (fanAsWGen[u] == gen) {                      /* shares the (p,u) edge */
                int mu = fanAsW[u];
                tris.setNeighbor(id, 1, mu);
                tris.setNeighbor(mu, 0, id);
            }
            fanAsU[u] = id;
            fanAsUGen[u] = gen;
            if (fanAsUGen[w] == gen) {                      /* shares the (w,p) edge */
                int mw = fanAsU[w];
                tris.setNeighbor(id, 0, mw);
                tris.setNeighbor(mw, 1, id);
            }
            fanAsW[w] = id;
            fanAsWGen[w] = gen;
        }
    }

    private void ensureFanScratch(int n) {
        if (fanAsU.length < n) {
            int cap = Math.max(n, fanAsU.length * 2);
            fanAsU = Arrays.copyOf(fanAsU, cap);
            fanAsUGen = Arrays.copyOf(fanAsUGen, cap);
            fanAsW = Arrays.copyOf(fanAsW, cap);
            fanAsWGen = Arrays.copyOf(fanAsWGen, cap);
        }
    }

    /** A cavity-boundary edge to re-fan: the oriented edge (u, w), the outer
        neighbour slot across it (or -1), and the region attribute inherited from
        the cavity triangle it came from. */
    private static final class BoundaryEdge {
        final int u;
        final int w;
        final int nb;
        final double attr;

        BoundaryEdge(int u, int w, int nb, double attr) {
            this.u = u;
            this.w = w;
            this.nb = nb;
            this.attr = attr;
        }
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
        int t1 = segTri.get(key(a, b));
        if (t1 < 0) {
            return new int[0];
        }
        int apex = third(t1, a, b);
        int slot = tris.a(t1) == apex ? 0 : tris.b(t1) == apex ? 1 : 2; /* edge (a,b) opposite the apex */
        int t2 = tris.neighbor(t1, slot);
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
        int t1 = segTri.get(key(a, b));
        if (t1 < 0) {
            return new int[]{-1, -1};
        }
        int apex1 = third(t1, a, b);
        int slot = tris.a(t1) == apex1 ? 0 : tris.b(t1) == apex1 ? 1 : 2;   /* edge (a,b) opp the apex */
        int nb = tris.neighbor(t1, slot);
        int apex2 = nb < 0 ? -1 : third(nb, a, b);
        return new int[]{apex1, apex2};
    }

    /** The corner of triangle {@code t} that is neither {@code a} nor {@code b}. */
    private int third(int t, int a, int b) {
        if (tris.a(t) != a && tris.a(t) != b) {
            return tris.a(t);
        }
        return tris.b(t) != a && tris.b(t) != b ? tris.b(t) : tris.c(t);
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
            int s = encroachQueue.dequeueInt();
            Segment seg = segments.get(s);
            for (int apex : apexesOfSegment(seg.a, seg.b)) {
                if (apex >= 0 && inDiametralLens(seg.a, seg.b, apex)) {
                    return s;
                }
            }
        }
        return -1;
    }

    /** Re-queue every subsegment among the most recent fan as an encroachment
        candidate - the only subsegments whose incident apex the mutation changed. */
    private void enqueueEncroachFan() {
        for (int i = 0; i < lastFan.size(); i++) {
            int t = lastFan.getInt(i);
            for (int j = 0; j < 3; j++) {
                int s = segIndexByEdge.get(key(tris.corner(t, j), tris.corner(t, (j + 1) % 3)));
                if (s >= 0) {
                    encroachQueue.enqueue(s);
                }
            }
        }
    }

    /** Whether vertex {@code p} encroaches subsegment {@code (a,b)}: p lies in
        its diametral lens (see {@link #lensFactor}). */
    private boolean inDiametralLens(int a, int b, int p) {
        return inDiametralLens(a, b, points.x(p), points.y(p));
    }

    /** Whether the loose point {@code (px, py)} (a candidate vertex) lies in the
        diametral lens of subsegment {@code (a,b)} - so inserting it would
        encroach. */
    private boolean inDiametralLens(int a, int b, double px, double py) {
        double dax = points.x(a) - px;
        double day = points.y(a) - py;
        double dbx = points.x(b) - px;
        double dby = points.y(b) - py;
        double dot = dax * dbx + day * dby;
        if (dot >= 0.0) {
            return false;                       /* apex angle not even obtuse */
        }
        return dot * dot >= lensFactor * (dax * dax + day * day) * (dbx * dbx + dby * dby);
    }

    TriangleMesherOutput2 toOutput() {
        /* Drop dead slots and remap neighbour links to the compacted indexing. */
        List<ImmutableTriangle> outTriangles = TriangleUtils.compact(tris);

        ImmutableList.Builder<Constraint> outSegments = ImmutableList.builderWithExpectedSize(segments.size());
        for (Segment sg : segments) {
            outSegments.add(new Constraint(sg.a, sg.b, sg.marker));
        }

        /* The flat store materialized to the modelled List<Point> form - the mesh
           keeps its own growable store rather than handing out its internals. */
        return new TriangleMesherOutput2(points.toPointList(), outTriangles,
                outSegments.build(), haveAttr);
    }

    /**
     * Debug oracle (used by tests): the maintained neighbour links equal a
     * from-scratch rebuild over the live triangles. Cross-checks the hand-relinked
     * adjacency directly, with tighter localization than the output validator.
     */
    boolean adjacencyConsistent() {
        int[] remap = new int[tris.slotCount()];
        Arrays.fill(remap, -1);
        int[] liveSlot = new int[tris.liveCount()];
        int n = 0;
        for (int i = 0; i < tris.slotCount(); i++) {
            if (tris.isLive(i)) {
                remap[i] = n;
                liveSlot[n++] = i;
            }
        }
        int[] fresh = Topology.neighbors(n, (i, c) -> tris.corner(liveSlot[i], c));
        for (int i = 0; i < tris.slotCount(); i++) {
            if (!tris.isLive(i)) {
                continue;
            }
            int ni = remap[i];
            for (int j = 0; j < 3; j++) {
                int nb = tris.neighbor(i, j);
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
        for (int i = 0; i < tris.slotCount(); i++) {
            if (!tris.isLive(i)) {
                continue;
            }
            int a = tris.a(i);
            int b = tris.b(i);
            int c = tris.c(i);
            double area = Math.abs((points.x(b) - points.x(a)) * (points.y(c) - points.y(a))
                    - (points.y(b) - points.y(a)) * (points.x(c) - points.x(a))) / 2.0;
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        int a = tris.a(best);
        int b = tris.b(best);
        int c = tris.c(best);
        double cx = (points.x(a) + points.x(b) + points.x(c)) / 3.0;
        double cy = (points.y(a) + points.y(b) + points.y(c)) / 3.0;
        return new Point(cx, cy);
    }

    /* --- helpers (mirroring ConstrainedDelaunayTriangulator) ----------------- */

    private int locate(double x, double y) {
        for (int i = 0; i < tris.slotCount(); i++) {
            if (!tris.isLive(i)) {
                continue;
            }
            int s1 = orientXY(tris.a(i), tris.b(i), x, y);
            int s2 = orientXY(tris.b(i), tris.c(i), x, y);
            int s3 = orientXY(tris.c(i), tris.a(i), x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inCircle(int t, double px, double py) {
        return Geometry.inCircle(points, tris.a(t), tris.b(t), tris.c(t), px, py);
    }

    private int orientXY(int a, int b, double x, double y) {
        return Geometry.orient2d(points, a, b, x, y);
    }

    private double dist2(int a, int b) {
        return points.dist2(a, b);
    }

    private static long key(int a, int b) {
        return Topology.edgeKey(a, b);
    }
}
