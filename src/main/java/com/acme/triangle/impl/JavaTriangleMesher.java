package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.Point;
import com.acme.triangle.Region;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;

import it.unimi.dsi.fastutil.ints.IntList;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java {@link TriangleMesher}: constrained Delaunay
 * ({@link ConstrainedDelaunayTriangulator}) followed by Ruppert refinement to
 * the requested minimum angle.
 * <p>
 * Refinement (Ruppert's algorithm): while a subsegment is encroached - a
 * vertex lies in its diametral circle - split it at its midpoint; otherwise,
 * while a triangle is below the angle bound, insert its circumcircle centre,
 * unless that centre would encroach a subsegment, in which case split the
 * subsegment instead. The mesh is an {@link IncrementalCdt} updated in place;
 * bad triangles are processed worst-first from a queue keyed by shortest-edge
 * length (Triangle's scheme), re-testing only the triangles a mutation changed
 * rather than rescanning the whole mesh.
 * <p>
 * This honours both the angle bound ({@code minAngleDegrees}) and per-region
 * maximum-area constraints (the 4th value of each {@code regionList} entry). A
 * global (non-regional) area bound is not part of the target API. Refinement
 * assumes the input segments do not cross each other (true for quality inputs
 * here); the constrained-Delaunay step handles crossings for the unrefined case.
 */
public final class JavaTriangleMesher implements TriangleMesher {

    /** Off-centre placement aims this many degrees above the requested bound so
        new triangles clear the threshold rather than sitting exactly on it. */
    private static final double OFF_CENTRE_MARGIN_DEG = 1.0;

    /** Non-termination backstop: cap the vertex count at this multiple of the
        input size (or {@link #MIN_VERTEX_CAP}, whichever is larger). Native
        Triangle has no such count cap - it refines until either quality is met or
        a split point rounds onto an existing vertex (a precision error that aborts
        the process). We lack that precision-exhaustion path and are a simpler
        Ruppert/Üngör variant with no termination guarantee above ~20.7°, so a cap
        turns a genuinely non-terminating refinement into a fast, typed failure
        instead of an unbounded loop. It is sized to only catch real runaway, not
        to reject a feasible-but-large mesh: a high angle bound on a fine-featured
        input can legitimately need tens of thousands of vertices (e.g. one captured
        q=33 case native meshes with ~41k triangles ≈ 20k vertices). */
    private static final int MAX_VERTEX_FACTOR = 50;
    private static final int MIN_VERTEX_CAP = 100_000;

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        return mesh(ModelledInput.from(input)).toFlat();
    }

    /** The pipeline over the modelled form; conversion happens only at the
        public {@code mesh} boundary above. */
    private ModelledOutput mesh(ModelledInput input) {
        if (!needsRefinement(input)) {
            return ConstrainedDelaunayTriangulator.triangulate(input);
        }
        return refine(input);
    }

    private static boolean needsRefinement(ModelledInput input) {
        if (input.getMinAngleDegrees() > 0) {
            return true;
        }
        for (Region r : input.getRegions()) {
            if (r.getMaxArea() > 0) {                               /* a region max area */
                return true;
            }
        }
        return false;
    }

    private ModelledOutput refine(ModelledInput input) {
        double bound = input.getMinAngleDegrees();
        /* Below-bound test uses squared cosine vs cos^2(bound) - no trig per
           triangle (Triangle does the same, triangle.c:4036). */
        double cosBound = Math.cos(Math.toRadians(bound));
        double cosSqBound = cosBound * cosBound;
        AreaBounds areaBounds = AreaBounds.of(input.getRegions());

        /* Build the constrained Delaunay mesh once, then refine it in place: each
           Steiner point or subsegment split updates the mesh locally instead of
           rebuilding it from scratch every iteration (the old ~O(N^3) cost). */
        IncrementalCdt mesh = new IncrementalCdt(
                ConstrainedDelaunayTriangulator.triangulate(input), bound);

        /* Worst-first bad-triangle queue, keyed by shortest-edge length (shortest
           = highest priority; by length, NOT angle - a worst-angle-first variant
           regressed area cases). Seed once over the whole mesh, then re-test only
           the triangles each mutation changed (the mesh's "last fan"). Entries can
           go stale - their slot freed or reused - so revalidate on dequeue. */
        BadTriQueue bad = new BadTriQueue();
        FlatTriangleList seed = mesh.triangles();
        for (int id = 0; id < seed.slotCount(); id++) {
            if (seed.isLive(id)) {
                enqueueIfBad(bad, mesh, id, cosSqBound, areaBounds);
            }
        }

        int maxVertices = Math.max(input.getPoints().size() * MAX_VERTEX_FACTOR, MIN_VERTEX_CAP);
        while (true) {
            /* Live views, re-fetched each iteration: with maintained adjacency
               these are stable stores mutated in place (scans skip non-live
               slots). A mutation may free/reuse slots, so a triangle index is
               only valid until the next split/insert. */
            FlatTriangleList tris = mesh.triangles();
            FlatPointList points = mesh.points();

            /* Ruppert: clear every encroached subsegment before any bad triangle.
               The mesh maintains the encroached candidates incrementally, so this
               is O(1) amortized rather than an O(S) rescan each iteration. */
            int seg = mesh.pollEncroachedSubsegment();
            if (seg >= 0) {
                mesh.splitSegment(seg);
                enqueueFan(bad, mesh, cosSqBound, areaBounds);
            } else {
                int t = dequeueValidBad(bad, mesh);
                if (t < 0) {
                    return mesh.toOutput();        /* quality achieved: build output once */
                }
                Point centre = offCentre(tris, points, t, bound);
                /* Insert the off-centre seeded from t (the bad triangle whose
                   off-centre it is, holding it in its circumcircle - no O(T) point
                   location). The mesh folds the Ruppert encroachment guard into the
                   cavity gather, so if the off-centre would encroach a subsegment it
                   is not inserted and that subsegment is returned to split instead -
                   O(cavity), not an O(S) scan of every subsegment. */
                int encroached = mesh.insertInteriorOrEncroachedSegment(
                        centre.getX(), centre.getY(), t);
                if (encroached >= 0) {
                    mesh.splitSegment(encroached);
                    enqueueFan(bad, mesh, cosSqBound, areaBounds);
                    /* The off-centre was rejected, not inserted; t is unrefined, so
                       requeue it (no-op if the split happened to destroy it). */
                    enqueueIfBad(bad, mesh, t, cosSqBound, areaBounds);
                } else {
                    enqueueFan(bad, mesh, cosSqBound, areaBounds);
                }
            }

            if (mesh.pointCount() > maxVertices) {
                throw new MeshContractException(
                        "refinement did not converge within " + maxVertices + " vertices",
                        Collections.singletonList("quality: minimum angle bound "
                                + bound + " degrees not reached"));
            }
        }
    }

    /**
     * Per-region maximum-area bounds keyed by region attribute, as parallel
     * arrays - a handful of entries scanned linearly. This test runs on every
     * bad-triangle check, and the {@code Map<Double, Double>} it replaces boxed
     * the attribute on each lookup (~10% of area-constrained refinement in the
     * JFR profile). Duplicate attributes keep the last bound, like the map did.
     */
    private static final class AreaBounds {
        private final double[] attrs;
        private final double[] bounds;

        private AreaBounds(double[] attrs, double[] bounds) {
            this.attrs = attrs;
            this.bounds = bounds;
        }

        static AreaBounds of(List<Region> regions) {
            double[] attrs = new double[regions.size()];
            double[] bounds = new double[regions.size()];
            int n = 0;
            for (Region r : regions) {
                if (r.getMaxArea() <= 0) {
                    continue;
                }
                int i = 0;
                while (i < n && attrs[i] != r.getAttribute()) {
                    i++;
                }
                attrs[i] = r.getAttribute();
                bounds[i] = r.getMaxArea();
                if (i == n) {
                    n++;
                }
            }
            return new AreaBounds(Arrays.copyOf(attrs, n), Arrays.copyOf(bounds, n));
        }

        boolean isEmpty() {
            return attrs.length == 0;
        }

        /** The area bound for {@code attr}, or -1 if that region has none. */
        double boundFor(double attr) {
            for (int i = 0; i < attrs.length; i++) {
                if (attrs[i] == attr) {
                    return bounds[i];
                }
            }
            return -1;
        }
    }

    /** A queued bad triangle: its slot id, its corners at enqueue time (to detect
        a stale entry once the slot is freed or reused), and its shortest-edge
        squared length as the priority key. Doubles as an intrusive
        {@link BadTriQueue} node via {@code next}. */
    private static final class BadTri {
        final int id, a, b, c;
        final double key;
        @Nullable BadTri next;

        BadTri(int id, int a, int b, int c, double key) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.c = c;
            this.key = key;
        }
    }

    /**
     * Worst-first bad-triangle queue as 4096 FIFO bins keyed by the magnitude of
     * the shortest-edge length - Triangle's scheme (triangle.c:3722), which
     * out-performed its author's heap "by a larger margin than I'd suspected":
     * enqueue and dequeue are O(1) where the binary heap's sift was ~20% of
     * refinement time here. The price is ordering that is only
     * &radic;2-granular (each exponent of the squared key is split once), i.e.
     * exactly the worst-first-by-length processing the refinement needs, just
     * FIFO among near-equal keys. Bins are intrusive singly-linked lists through
     * {@link BadTri#next}; a linked chain of non-empty bins keeps "highest
     * non-empty" O(1) amortized.
     */
    private static final class BadTriQueue {
        private static final double SQRT2 = 1.4142135623730951;

        private final BadTri[] front = new BadTri[4096];
        private final BadTri[] tail = new BadTri[4096];
        private final int[] nextNonEmpty = new int[4096];
        private int firstNonEmpty = -1;

        void add(BadTri t) {
            int q = binOf(t.key);
            if (front[q] == null) {                 /* newly non-empty bin: link it in */
                if (q > firstNonEmpty) {
                    nextNonEmpty[q] = firstNonEmpty;
                    firstNonEmpty = q;
                } else {
                    int above = q + 1;              /* nearest non-empty higher-priority bin */
                    while (front[above] == null) {
                        above++;
                    }
                    nextNonEmpty[q] = nextNonEmpty[above];
                    nextNonEmpty[above] = q;
                }
                front[q] = t;
            } else {
                tail[q].next = t;
            }
            tail[q] = t;
            t.next = null;
        }

        @Nullable BadTri poll() {
            if (firstNonEmpty < 0) {
                return null;
            }
            BadTri t = front[firstNonEmpty];
            front[firstNonEmpty] = t.next;
            if (t.next == null) {
                firstNonEmpty = nextNonEmpty[firstNonEmpty];
            }
            return t;
        }

        /** Bin for a squared-length key: shorter edges get higher bins (dequeued
            first), two bins per power of two (Triangle's grading; a finer
            4-per-octave variant was tried and moved sizes chaotically, not
            better), clamped to the 4096 available. */
        private static int binOf(double key) {
            boolean shortEdge = key < 1.0;
            double length = shortEdge ? 1.0 / key : key;
            int exp = Math.getExponent(length);
            int halfStep = Math.scalb(length, -exp) > SQRT2 ? 1 : 0;
            int graded = 2 * exp + halfStep;
            int q = shortEdge ? 2048 + graded : 2047 - graded;
            return Math.max(0, Math.min(4095, q));
        }
    }

    /** Enqueue the triangle in slot {@code id} if it is currently bad. Reads the
        live mesh, so a dead slot is silently skipped. */
    private static void enqueueIfBad(BadTriQueue bad, IncrementalCdt mesh,
                                     int id, double cosSqBound,
                                     AreaBounds areaBounds) {
        FlatTriangleList tris = mesh.triangles();
        if (!tris.isLive(id)) {
            return;
        }
        FlatPointList points = mesh.points();
        int ia = tris.a(id);
        int ib = tris.b(id);
        int ic = tris.c(id);
        boolean isBad = false;
        if (!areaBounds.isEmpty() && mesh.hasAttributes()) {
            double maxArea = areaBounds.boundFor(tris.attr(id));
            isBad = maxArea > 0 && triangleArea(points, ia, ib, ic) > maxArea;
        }
        if (!isBad) {
            isBad = belowAngleBound(points, ia, ib, ic, cosSqBound)
                    && !unsplittable(mesh, points, ia, ib, ic);
        }
        if (!isBad) {
            return;
        }
        double key = Math.min(points.dist2(ia, ib),
                Math.min(points.dist2(ib, ic), points.dist2(ic, ia)));
        bad.add(new BadTri(id, ia, ib, ic, key));
    }

    /** Re-test the triangles the most recent mutation created (the mesh's last
        fan), enqueuing any that are bad - the dirty set in place of a full rescan. */
    private static void enqueueFan(BadTriQueue bad, IncrementalCdt mesh,
                                   double cosSqBound, AreaBounds areaBounds) {
        IntList fan = mesh.lastFanTriangles();
        for (int i = 0; i < fan.size(); i++) {
            enqueueIfBad(bad, mesh, fan.getInt(i), cosSqBound, areaBounds);
        }
    }

    /** Pop the highest-priority bad triangle still present in the mesh, discarding
        stale entries (slot freed, or reused for a different triangle). A surviving
        triangle's corners are unchanged, so it is still bad - no re-test needed. */
    private static int dequeueValidBad(BadTriQueue bad, IncrementalCdt mesh) {
        FlatTriangleList tris = mesh.triangles();
        for (BadTri e = bad.poll(); e != null; e = bad.poll()) {
            if (tris.isLive(e.id) && sameCorners(tris, e.id, e.a, e.b, e.c)) {
                return e.id;
            }
        }
        return -1;
    }

    /** Whether triangle {@code t} has exactly the corner set {a, b, c} (the
        corners are distinct, so containment in both directions reduces to this). */
    private static boolean sameCorners(FlatTriangleList tris, int t, int a, int b, int c) {
        return tris.hasCorner(t, a) && tris.hasCorner(t, b) && tris.hasCorner(t, c);
    }

    /**
     * True if the triangle's smallest angle is below the bound, tested via the
     * squared cosine of the angle opposite the shortest edge against
     * {@code cos^2(bound)} - no {@code acos}/{@code sqrt} per triangle
     * (triangle.c:4036). The smallest angle is opposite the shortest edge and is
     * always acute, so the squared comparison is exact in sign.
     */
    private static boolean belowAngleBound(FlatPointList pts, int a, int b, int c,
                                           double cosSqBound) {
        double dxod = pts.x(b) - pts.x(a), dyod = pts.y(b) - pts.y(a);
        double dxda = pts.x(c) - pts.x(b), dyda = pts.y(c) - pts.y(b);
        double dxao = pts.x(c) - pts.x(a), dyao = pts.y(c) - pts.y(a);
        double apexlen = dxod * dxod + dyod * dyod;        /* |a-b|^2, opposite c */
        double orglen = dxda * dxda + dyda * dyda;          /* |b-c|^2, opposite a */
        double destlen = dxao * dxao + dyao * dyao;         /* |a-c|^2, opposite b */
        double dot, denom;
        if (apexlen < orglen && apexlen < destlen) {
            dot = dxda * dxao + dyda * dyao;                /* angle at c */
            denom = orglen * destlen;
        } else if (orglen < destlen) {
            dot = dxod * dxao + dyod * dyao;                /* angle at a */
            denom = apexlen * destlen;
        } else {
            dot = dxod * dxda + dyod * dyda;                /* angle at b */
            denom = apexlen * orglen;
        }
        if (denom <= 0.0) {
            return true;                                    /* degenerate: treat as bad */
        }
        return dot * dot / denom > cosSqBound;
    }

    /**
     * Miller-Pav-Walkington rule (triangle.c:4084): a skinny triangle is left
     * unsplit if its shortest edge's endpoints both lie in segment interiors, on
     * two different input segments meeting at a join vertex, equidistant from
     * that join (on a common concentric shell). Such a triangle's poor angle is
     * imposed by the input near that feature; refining it only cascades. The
     * concentric-shell segment splitting in {@link IncrementalCdt} is what makes
     * the endpoints land equidistant so this rule can recognize them.
     */
    private static boolean unsplittable(IncrementalCdt cdt, FlatPointList points,
                                        int ia, int ib, int ic) {
        double ab = points.dist2(ia, ib);
        double bc = points.dist2(ib, ic);
        double ca = points.dist2(ic, ia);
        int b1, b2;                                         /* shortest-edge endpoints */
        if (ab <= bc && ab <= ca) {
            b1 = ia; b2 = ib;
        } else if (bc <= ab && bc <= ca) {
            b1 = ib; b2 = ic;
        } else {
            b1 = ic; b2 = ia;
        }

        Provenance p1 = cdt.provenance(b1), p2 = cdt.provenance(b2);
        if (p1.type != VertexType.SEGMENT || p2.type != VertexType.SEGMENT) {
            return false;
        }
        if ((p1.origOrg == p2.origOrg && p1.origDest == p2.origDest)
                || (p1.origOrg == p2.origDest && p1.origDest == p2.origOrg)) {
            return false;                          /* same input segment: split normally */
        }
        int join = -1;
        if (p1.origOrg == p2.origOrg || p1.origOrg == p2.origDest) {
            join = p1.origOrg;
        } else if (p1.origDest == p2.origOrg || p1.origDest == p2.origDest) {
            join = p1.origDest;
        }
        if (join < 0) {
            return false;                          /* the two segments do not meet */
        }
        double d1 = points.dist2(b1, join);
        double d2 = points.dist2(b2, join);
        return d1 < 1.001 * d2 && d1 > 0.999 * d2;
    }

    private static double triangleArea(FlatPointList pts, int a, int b, int c) {
        return Math.abs((pts.x(b) - pts.x(a)) * (pts.y(c) - pts.y(a))
                - (pts.y(b) - pts.y(a)) * (pts.x(c) - pts.x(a))) / 2.0;
    }

    /**
     * Off-centre Steiner point (Ungor 2004) for a below-bound triangle: a point
     * on the perpendicular bisector of the triangle's shortest edge, toward the
     * apex, at the height where the new triangle on that edge just clears the
     * angle bound; or the circumcentre if that is nearer. The bisector direction
     * is taken from the shortest edge directly (not from the circumcentre, which
     * is numerically unreliable for skinny triangles). Aims a small margin above
     * the bound so the new triangle is not re-selected at exactly the threshold.
     */
    private static Point offCentre(FlatTriangleList tris, FlatPointList points,
                                   int t, double boundDegrees) {
        int ia = tris.a(t);
        int ib = tris.b(t);
        int ic = tris.c(t);

        //p,q = shortest edge; r = apex
        int p;
        int q;
        int r;
        double ab = points.dist2(ia, ib), bc = points.dist2(ib, ic), ca = points.dist2(ic, ia);
        if (ab <= bc && ab <= ca) {
            p = ia; q = ib; r = ic;
        } else if (bc <= ab && bc <= ca) {
            p = ib; q = ic; r = ia;
        } else {
            p = ic; q = ia; r = ib;
        }

        double e = Math.sqrt(points.dist2(p, q));
        double mx = (points.x(p) + points.x(q)) / 2.0, my = (points.y(p) + points.y(q)) / 2.0;
        double nx = -(points.y(q) - points.y(p)), ny = points.x(q) - points.x(p);   /* perpendicular to pq */
        /* sqrt, not Math.hypot: hypot's overflow guard made these two calls ~90%
           of refinement time (JMH stack profile), like the old acos cost. */
        double nlen = Math.sqrt(nx * nx + ny * ny);
        if (nlen == 0) {
            return circumcentre(tris, points, t);
        }
        nx /= nlen;
        ny /= nlen;
        if ((points.x(r) - mx) * nx + (points.y(r) - my) * ny < 0) {  /* orient toward apex */
            nx = -nx;
            ny = -ny;
        }

        double targetAngle = Math.min(boundDegrees + OFF_CENTRE_MARGIN_DEG, 60.0);
        double beta = 1.0 / (2.0 * Math.sin(Math.toRadians(targetAngle)));
        double radicand = beta * beta - 0.25;
        if (radicand <= 0) {
            return circumcentre(tris, points, t);
        }
        /* Height giving the new triangle radius-edge ratio beta. Two heights
           qualify; take the tall one (apex out toward the circumcentre), which
           makes a well-shaped triangle and inserts few points - the short root
           puts the apex next to the edge and spawns slivers. */
        double h = e * (beta + Math.sqrt(radicand));

        Point cc = circumcentre(tris, points, t);
        double ccdx = cc.getX() - mx, ccdy = cc.getY() - my;
        double dc = Math.sqrt(ccdx * ccdx + ccdy * ccdy);
        if (dc > 0 && !Double.isNaN(dc) && !Double.isInfinite(dc) && h > dc) {
            h = dc;                                /* don't overshoot the circumcentre */
        }
        return new Point(mx + h * nx, my + h * ny);
    }

    private static Point circumcentre(FlatTriangleList tris, FlatPointList points, int t) {
        double ax = points.x(tris.a(t));
        double ay = points.y(tris.a(t));
        double bx = points.x(tris.b(t));
        double by = points.y(tris.b(t));
        double cx = points.x(tris.c(t));
        double cy = points.y(tris.c(t));

        double d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
        double a2 = ax * ax + ay * ay;
        double b2 = bx * bx + by * by;
        double c2 = cx * cx + cy * cy;
        double ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d;
        double uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        return new Point(ux, uy);
    }

}
