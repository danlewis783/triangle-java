package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java {@link TriangleMesher}: constrained Delaunay
 * ({@link ConstrainedDelaunayTriangulator}) followed by Ruppert refinement to
 * the requested minimum angle.
 *
 * <p>Refinement (Ruppert's algorithm): while a subsegment is encroached - a
 * vertex lies in its diametral circle - split it at its midpoint; otherwise,
 * while a triangle is below the angle bound, insert its circumcircle centre,
 * unless that centre would encroach a subsegment, in which case split the
 * subsegment instead. Each change rebuilds the constrained Delaunay mesh (simple
 * and correct at these sizes); a spatial/local-update version can come later,
 * validated by the same contract.
 *
 * <p>This honours both the angle bound ({@code minAngleDegrees}) and per-region
 * maximum-area constraints (the 4th value of each {@code regionList} entry). A
 * global (non-regional) area bound is not part of the target API. Refinement
 * assumes the input segments do not cross each other (true for quality inputs
 * here); the constrained-Delaunay step handles crossings for the unrefined case.
 */
public final class JavaTriangleMesher implements TriangleMesher {

    /** Off-centre placement aims this many degrees above the requested bound so
        new triangles clear the threshold rather than sitting exactly on it. */
    private static final double OFF_CENTRE_MARGIN_DEG = 1.0;

    /** Convergence backstop: cap the vertex count at this multiple of the input
        size (or {@link #MIN_VERTEX_CAP}, whichever is larger). Off-centres make
        achievable bounds converge well under this; the cap turns a bound no
        Ruppert variant can satisfy (e.g. a high angle on a fine-featured input)
        into a fast, typed failure instead of an unbounded loop. */
    private static final int MAX_VERTEX_FACTOR = 50;
    private static final int MIN_VERTEX_CAP = 10_000;

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        if (!needsRefinement(input)) {
            return ConstrainedDelaunayTriangulator.triangulate(input);
        }
        return refine(input);
    }

    private static boolean needsRefinement(TriangleMesherInput input) {
        if (input.minAngleDegrees > 0) {
            return true;
        }
        if (input.regionList != null) {
            for (int r = 0; r < input.numberOfRegions; r++) {
                if (input.regionList[4 * r + 3] > 0) {        /* a region max area */
                    return true;
                }
            }
        }
        return false;
    }

    private TriangleMesherOutput refine(TriangleMesherInput input) {
        double bound = input.minAngleDegrees;
        /* Below-bound test uses squared cosine vs cos^2(bound) - no trig per
           triangle (Triangle does the same, triangle.c:4036). */
        double cosBound = Math.cos(Math.toRadians(bound));
        double cosSqBound = cosBound * cosBound;
        Map<Double, Double> maxAreaByAttr = new HashMap<>();
        if (input.regionList != null) {
            for (int r = 0; r < input.numberOfRegions; r++) {
                double attr = input.regionList[4 * r + 2];
                double maxArea = input.regionList[4 * r + 3];
                if (maxArea > 0) {
                    maxAreaByAttr.put(attr, maxArea);
                }
            }
        }

        /* Build the constrained Delaunay mesh once, then refine it in place: each
           Steiner point or subsegment split updates the mesh locally instead of
           rebuilding it from scratch every iteration (the old ~O(N^3) cost). */
        IncrementalCdt mesh = new IncrementalCdt(
                ConstrainedDelaunayTriangulator.triangulate(input));

        int maxVertices = Math.max(input.numberOfPoints * MAX_VERTEX_FACTOR, MIN_VERTEX_CAP);
        while (true) {
            /* Live views, re-fetched each iteration (the triangle/attr lists are
               replaced on mutation), so we never snapshot the whole mesh. */
            List<int[]> tris = mesh.trianglesView();
            List<double[]> points = mesh.pointsView();
            List<int[]> segments = mesh.segmentsView();
            List<Double> attrs = mesh.attrsView();

            int seg = encroachedSubsegment(tris, points, segments);
            if (seg >= 0) {
                mesh.splitSegment(seg);
            } else {
                int bad = badTriangle(mesh, tris, attrs, points, cosSqBound, maxAreaByAttr);
                if (bad < 0) {
                    return mesh.toOutput();        /* quality achieved: build output once */
                }
                double[] centre = offCentre(tris, points, bad, bound);
                int encroached = subsegmentEncroachedBy(centre, points, segments);
                if (encroached >= 0) {
                    mesh.splitSegment(encroached);
                } else {
                    mesh.insertInteriorPoint(centre);
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

    /** First subsegment with an adjacent triangle apex inside its diametral disk.
        Indexes each edge to its (at most two) opposite apexes once - O(T) - so
        the segment scan is O(T + S) rather than O(S * T). */
    private static int encroachedSubsegment(List<int[]> tris,
                                            List<double[]> points,
                                            List<int[]> segments) {
        Map<Long, int[]> edgeApex = new HashMap<>(2 * tris.size() + 1);
        for (int t = 0; t < tris.size(); t++) {
            int[] tc = tris.get(t);
            addApex(edgeApex, tc[1], tc[2], tc[0]);
            addApex(edgeApex, tc[2], tc[0], tc[1]);
            addApex(edgeApex, tc[0], tc[1], tc[2]);
        }
        for (int s = 0; s < segments.size(); s++) {
            int a = segments.get(s)[0], b = segments.get(s)[1];
            int[] apexes = edgeApex.get(key(a, b));
            if (apexes == null) {
                continue;
            }
            for (int ap : apexes) {
                if (ap >= 0 && inDiametralDisk(points, a, b, points.get(ap))) {
                    return s;
                }
            }
        }
        return -1;
    }

    /** Record {@code apex} as opposite the edge (u,w); a manifold edge has &le;2. */
    private static void addApex(Map<Long, int[]> edgeApex, int u, int w, int apex) {
        long k = key(u, w);
        int[] e = edgeApex.get(k);
        if (e == null) {
            edgeApex.put(k, new int[]{apex, -1});
        } else if (e[1] < 0) {
            e[1] = apex;
        }
    }

    private static long key(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static int subsegmentEncroachedBy(double[] p, List<double[]> points,
                                              List<int[]> segments) {
        for (int s = 0; s < segments.size(); s++) {
            if (inDiametralDisk(points, segments.get(s)[0], segments.get(s)[1], p)) {
                return s;
            }
        }
        return -1;
    }

    /** The first triangle that must be refined: larger than its region's max
        area, or below the angle bound and not an unsplittable small-feature
        triangle. Area is checked first (it always terminates); the skip rule
        applies only to the angle bound. */
    private static int badTriangle(IncrementalCdt cdt, List<int[]> tris,
                                   List<Double> attrs, List<double[]> points,
                                   double cosSqBound, Map<Double, Double> maxAreaByAttr) {
        boolean checkArea = !maxAreaByAttr.isEmpty() && attrs != null;
        for (int t = 0; t < tris.size(); t++) {
            int[] tc = tris.get(t);
            int ia = tc[0], ib = tc[1], ic = tc[2];
            double[] a = points.get(ia), b = points.get(ib), c = points.get(ic);
            if (checkArea) {
                Double maxArea = maxAreaByAttr.get(attrs.get(t));
                if (maxArea != null && triangleArea(a, b, c) > maxArea) {
                    return t;                               /* too large for region */
                }
            }
            if (belowAngleBound(a, b, c, cosSqBound)
                    && !unsplittable(cdt, points, ia, ib, ic)) {
                return t;                                   /* skinny and fixable */
            }
        }
        return -1;
    }

    /**
     * True if the triangle's smallest angle is below the bound, tested via the
     * squared cosine of the angle opposite the shortest edge against
     * {@code cos^2(bound)} - no {@code acos}/{@code sqrt} per triangle
     * (triangle.c:4036). The smallest angle is opposite the shortest edge and is
     * always acute, so the squared comparison is exact in sign.
     */
    private static boolean belowAngleBound(double[] a, double[] b, double[] c,
                                           double cosSqBound) {
        double dxod = b[0] - a[0], dyod = b[1] - a[1];
        double dxda = c[0] - b[0], dyda = c[1] - b[1];
        double dxao = c[0] - a[0], dyao = c[1] - a[1];
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
    private static boolean unsplittable(IncrementalCdt cdt, List<double[]> points,
                                        int ia, int ib, int ic) {
        double ab = dist2(points.get(ia), points.get(ib));
        double bc = dist2(points.get(ib), points.get(ic));
        double ca = dist2(points.get(ic), points.get(ia));
        int b1, b2;                                         /* shortest-edge endpoints */
        if (ab <= bc && ab <= ca) {
            b1 = ia; b2 = ib;
        } else if (bc <= ab && bc <= ca) {
            b1 = ib; b2 = ic;
        } else {
            b1 = ic; b2 = ia;
        }

        if (cdt.vertexType(b1) != IncrementalCdt.SEGMENT
                || cdt.vertexType(b2) != IncrementalCdt.SEGMENT) {
            return false;
        }
        int[] s1 = cdt.vertexSeg(b1), s2 = cdt.vertexSeg(b2);
        if (s1 == null || s2 == null) {
            return false;
        }
        if ((s1[0] == s2[0] && s1[1] == s2[1]) || (s1[0] == s2[1] && s1[1] == s2[0])) {
            return false;                          /* same input segment: split normally */
        }
        int join = -1;
        if (s1[0] == s2[0] || s1[0] == s2[1]) {
            join = s1[0];
        } else if (s1[1] == s2[0] || s1[1] == s2[1]) {
            join = s1[1];
        }
        if (join < 0) {
            return false;                          /* the two segments do not meet */
        }
        double d1 = dist2(points.get(b1), points.get(join));
        double d2 = dist2(points.get(b2), points.get(join));
        return d1 < 1.001 * d2 && d1 > 0.999 * d2;
    }

    private static double triangleArea(double[] a, double[] b, double[] c) {
        return Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
                / 2.0;
    }

    private static boolean inDiametralDisk(List<double[]> points, int a, int b,
                                           double[] p) {
        double[] pa = points.get(a), pb = points.get(b);
        double dot = (p[0] - pa[0]) * (p[0] - pb[0]) + (p[1] - pa[1]) * (p[1] - pb[1]);
        return dot < 0;                             /* angle a-p-b obtuse */
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
    private static double[] offCentre(List<int[]> tris, List<double[]> points,
                                      int t, double boundDegrees) {
        int[] tc = tris.get(t);
        double[] a = points.get(tc[0]), b = points.get(tc[1]), c = points.get(tc[2]);

        double[] p, q, r;                          /* p,q = shortest edge; r = apex */
        double ab = dist2(a, b), bc = dist2(b, c), ca = dist2(c, a);
        if (ab <= bc && ab <= ca) {
            p = a; q = b; r = c;
        } else if (bc <= ab && bc <= ca) {
            p = b; q = c; r = a;
        } else {
            p = c; q = a; r = b;
        }

        double e = Math.sqrt(dist2(p, q));
        double mx = (p[0] + q[0]) / 2.0, my = (p[1] + q[1]) / 2.0;
        double nx = -(q[1] - p[1]), ny = q[0] - p[0];   /* perpendicular to pq */
        double nlen = Math.hypot(nx, ny);
        if (nlen == 0) {
            return circumcentre(tris, points, t);
        }
        nx /= nlen;
        ny /= nlen;
        if ((r[0] - mx) * nx + (r[1] - my) * ny < 0) {  /* orient toward apex */
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

        double[] cc = circumcentre(tris, points, t);
        double dc = Math.hypot(cc[0] - mx, cc[1] - my);
        if (dc > 0 && !Double.isNaN(dc) && !Double.isInfinite(dc) && h > dc) {
            h = dc;                                /* don't overshoot the circumcentre */
        }
        return new double[]{mx + h * nx, my + h * ny};
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    private static double[] circumcentre(List<int[]> tris, List<double[]> points, int t) {
        int[] tc = tris.get(t);
        double[] a = points.get(tc[0]), b = points.get(tc[1]), c = points.get(tc[2]);
        double d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
        double a2 = a[0] * a[0] + a[1] * a[1];
        double b2 = b[0] * b[0] + b[1] * b[1];
        double c2 = c[0] * c[0] + c[1] * c[1];
        double ux = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d;
        double uy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d;
        return new double[]{ux, uy};
    }

}
