package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

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
public final class JavaTriangleMesher implements TriangleMesher, TriangleMesher2 {

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
        return mesh(TriangleMesherInput2.from(input)).toFlat();
    }

    @Override
    public TriangleMesherOutput2 mesh(TriangleMesherInput2 input) {
        if (!needsRefinement(input)) {
            return ConstrainedDelaunayTriangulator.triangulate(input);
        }
        return refine(input);
    }

    private static boolean needsRefinement(TriangleMesherInput2 input) {
        if (input.minAngleDegrees > 0) {
            return true;
        }
        for (Region r : input.regions) {
            if (r.maxArea > 0) {                               /* a region max area */
                return true;
            }
        }
        return false;
    }

    private TriangleMesherOutput2 refine(TriangleMesherInput2 input) {
        double bound = input.minAngleDegrees;
        /* Below-bound test uses squared cosine vs cos^2(bound) - no trig per
           triangle (Triangle does the same, triangle.c:4036). */
        double cosBound = Math.cos(Math.toRadians(bound));
        double cosSqBound = cosBound * cosBound;
        Map<Double, Double> maxAreaByAttr = new HashMap<>();
        for (Region r : input.regions) {
            if (r.maxArea > 0) {
                maxAreaByAttr.put(r.attribute, r.maxArea);
            }
        }

        /* Build the constrained Delaunay mesh once, then refine it in place: each
           Steiner point or subsegment split updates the mesh locally instead of
           rebuilding it from scratch every iteration (the old ~O(N^3) cost). */
        IncrementalCdt mesh = new IncrementalCdt(
                ConstrainedDelaunayTriangulator.triangulate(input));

        /* Worst-first bad-triangle queue, keyed by shortest-edge length (shortest
           = highest priority; by length, NOT angle - a worst-angle-first variant
           regressed area cases). Seed once over the whole mesh, then re-test only
           the triangles each mutation changed (the mesh's "last fan"). Entries can
           go stale - their slot freed or reused - so revalidate on dequeue. */
        PriorityQueue<BadTri> bad =
                new PriorityQueue<>(Comparator.comparingDouble(e -> e.key));
        List<Triangle> seed = mesh.trianglesView();
        for (int id = 0; id < seed.size(); id++) {
            if (seed.get(id) != null) {
                enqueueIfBad(bad, mesh, id, cosSqBound, maxAreaByAttr);
            }
        }

        int maxVertices = Math.max(input.points.size() * MAX_VERTEX_FACTOR, MIN_VERTEX_CAP);
        while (true) {
            /* Live views, re-fetched each iteration: with maintained adjacency
               these are stable lists mutated in place (dead triangles appear as
               null, so the scans skip them). A mutation may free/reuse slots, so a
               triangle index is only valid until the next split/insert. */
            List<Triangle> tris = mesh.trianglesView();
            Points points = mesh.pointsView();

            /* Ruppert: clear every encroached subsegment before any bad triangle.
               The mesh maintains the encroached candidates incrementally, so this
               is O(1) amortized rather than an O(S) rescan each iteration. */
            int seg = mesh.pollEncroachedSubsegment();
            if (seg >= 0) {
                mesh.splitSegment(seg);
                enqueueFan(bad, mesh, cosSqBound, maxAreaByAttr);
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
                int encroached = mesh.insertInteriorOrEncroachedSegment(centre, t);
                if (encroached >= 0) {
                    mesh.splitSegment(encroached);
                    enqueueFan(bad, mesh, cosSqBound, maxAreaByAttr);
                    /* The off-centre was rejected, not inserted; t is unrefined, so
                       requeue it (no-op if the split happened to destroy it). */
                    enqueueIfBad(bad, mesh, t, cosSqBound, maxAreaByAttr);
                } else {
                    enqueueFan(bad, mesh, cosSqBound, maxAreaByAttr);
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

    /** A queued bad triangle: its slot id, its corners at enqueue time (to detect
        a stale entry once the slot is freed or reused), and its shortest-edge
        squared length as the priority key. */
    private static final class BadTri {
        final int id, a, b, c;
        final double key;

        BadTri(int id, int a, int b, int c, double key) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.c = c;
            this.key = key;
        }
    }

    /** Enqueue the triangle in slot {@code id} if it is currently bad. Reads the
        live mesh, so a dead slot is silently skipped. */
    private static void enqueueIfBad(PriorityQueue<BadTri> bad, IncrementalCdt mesh,
                                     int id, double cosSqBound,
                                     Map<Double, Double> maxAreaByAttr) {
        Triangle tc = mesh.trianglesView().get(id);
        if (tc == null) {
            return;
        }
        Points points = mesh.pointsView();
        int ia = tc.a, ib = tc.b, ic = tc.c;
        Point a = points.at(ia), b = points.at(ib), c = points.at(ic);
        boolean isBad = false;
        if (!maxAreaByAttr.isEmpty() && mesh.hasAttributes()) {
            Double maxArea = maxAreaByAttr.get(tc.attr);
            isBad = maxArea != null && triangleArea(a, b, c) > maxArea;
        }
        if (!isBad) {
            isBad = belowAngleBound(a, b, c, cosSqBound)
                    && !unsplittable(mesh, points, ia, ib, ic);
        }
        if (!isBad) {
            return;
        }
        double key = Math.min(dist2(a, b), Math.min(dist2(b, c), dist2(c, a)));
        bad.add(new BadTri(id, ia, ib, ic, key));
    }

    /** Re-test the triangles the most recent mutation created (the mesh's last
        fan), enqueuing any that are bad - the dirty set in place of a full rescan. */
    private static void enqueueFan(PriorityQueue<BadTri> bad, IncrementalCdt mesh,
                                   double cosSqBound, Map<Double, Double> maxAreaByAttr) {
        for (int id : mesh.lastFanTriangles()) {
            enqueueIfBad(bad, mesh, id, cosSqBound, maxAreaByAttr);
        }
    }

    /** Pop the highest-priority bad triangle still present in the mesh, discarding
        stale entries (slot freed, or reused for a different triangle). A surviving
        triangle's corners are unchanged, so it is still bad - no re-test needed. */
    private static int dequeueValidBad(PriorityQueue<BadTri> bad, IncrementalCdt mesh) {
        List<Triangle> tris = mesh.trianglesView();
        while (!bad.isEmpty()) {
            BadTri e = bad.poll();
            Triangle cur = tris.get(e.id);
            if (cur != null && sameCorners(cur, e.a, e.b, e.c)) {
                return e.id;
            }
        }
        return -1;
    }

    /** Whether {@code tc} has exactly the corner set {a, b, c} (the corners are
        distinct, so containment in both directions reduces to this). */
    private static boolean sameCorners(Triangle tc, int a, int b, int c) {
        return (tc.a == a || tc.b == a || tc.c == a)
                && (tc.a == b || tc.b == b || tc.c == b)
                && (tc.a == c || tc.b == c || tc.c == c);
    }

    /**
     * True if the triangle's smallest angle is below the bound, tested via the
     * squared cosine of the angle opposite the shortest edge against
     * {@code cos^2(bound)} - no {@code acos}/{@code sqrt} per triangle
     * (triangle.c:4036). The smallest angle is opposite the shortest edge and is
     * always acute, so the squared comparison is exact in sign.
     */
    private static boolean belowAngleBound(Point a, Point b, Point c,
                                           double cosSqBound) {
        double dxod = b.x - a.x, dyod = b.y - a.y;
        double dxda = c.x - b.x, dyda = c.y - b.y;
        double dxao = c.x - a.x, dyao = c.y - a.y;
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
    private static boolean unsplittable(IncrementalCdt cdt, Points points,
                                        int ia, int ib, int ic) {
        double ab = dist2(points, ia, ib);
        double bc = dist2(points, ib, ic);
        double ca = dist2(points, ic, ia);
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
        double d1 = dist2(points, b1, join);
        double d2 = dist2(points, b2, join);
        return d1 < 1.001 * d2 && d1 > 0.999 * d2;
    }

    private static double triangleArea(Point a, Point b, Point c) {
        return Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x))
                / 2.0;
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
    private static Point offCentre(List<Triangle> tris, Points points,
                                   int t, double boundDegrees) {
        Triangle tc = tris.get(t);
        Point a = points.at(tc.a), b = points.at(tc.b), c = points.at(tc.c);

        Point p, q, r;                             /* p,q = shortest edge; r = apex */
        double ab = dist2(a, b), bc = dist2(b, c), ca = dist2(c, a);
        if (ab <= bc && ab <= ca) {
            p = a; q = b; r = c;
        } else if (bc <= ab && bc <= ca) {
            p = b; q = c; r = a;
        } else {
            p = c; q = a; r = b;
        }

        double e = Math.sqrt(dist2(p, q));
        double mx = (p.x + q.x) / 2.0, my = (p.y + q.y) / 2.0;
        double nx = -(q.y - p.y), ny = q.x - p.x;       /* perpendicular to pq */
        double nlen = Math.hypot(nx, ny);
        if (nlen == 0) {
            return circumcentre(tris, points, t);
        }
        nx /= nlen;
        ny /= nlen;
        if ((r.x - mx) * nx + (r.y - my) * ny < 0) {    /* orient toward apex */
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
        double dc = Math.hypot(cc.x - mx, cc.y - my);
        if (dc > 0 && !Double.isNaN(dc) && !Double.isInfinite(dc) && h > dc) {
            h = dc;                                /* don't overshoot the circumcentre */
        }
        return new Point(mx + h * nx, my + h * ny);
    }

    private static double dist2(Point a, Point b) {
        double dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static double dist2(Points points, int a, int b) {
        double dx = points.x(a) - points.x(b), dy = points.y(a) - points.y(b);
        return dx * dx + dy * dy;
    }

    private static Point circumcentre(List<Triangle> tris, Points points, int t) {
        Triangle tc = tris.get(t);
        double ax = points.x(tc.a), ay = points.y(tc.a);
        double bx = points.x(tc.b), by = points.y(tc.b);
        double cx = points.x(tc.c), cy = points.y(tc.c);
        double d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
        double a2 = ax * ax + ay * ay;
        double b2 = bx * bx + by * by;
        double c2 = cx * cx + cy * cy;
        double ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d;
        double uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        return new Point(ux, uy);
    }

}
