package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import java.util.ArrayList;
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

    private static final int MAX_ITERATIONS = 1_000_000;

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

        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < input.numberOfPoints; i++) {
            points.add(new double[]{input.pointList[2 * i], input.pointList[2 * i + 1]});
        }
        List<int[]> segments = new ArrayList<>();
        for (int i = 0; i < input.numberOfSegments; i++) {
            int marker = input.segmentMarkerList != null ? input.segmentMarkerList[i] : 0;
            segments.add(new int[]{input.segmentList[2 * i], input.segmentList[2 * i + 1],
                    marker});
        }

        TriangleMesherOutput mesh = null;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            mesh = ConstrainedDelaunayTriangulator.triangulate(synthInput(points, segments, input));

            int seg = encroachedSubsegment(mesh, points, segments);
            if (seg >= 0) {
                splitSegment(points, segments, seg);
                continue;
            }
            int bad = badTriangle(mesh, points, bound, maxAreaByAttr);
            if (bad < 0) {
                return mesh;                       /* quality achieved */
            }
            double[] centre = circumcentre(mesh, points, bad);
            int encroached = subsegmentEncroachedBy(centre, points, segments);
            if (encroached >= 0) {
                splitSegment(points, segments, encroached);
            } else {
                points.add(centre);
            }
        }
        throw new IllegalStateException("refinement did not converge");
    }

    private static TriangleMesherInput synthInput(List<double[]> points,
                                                  List<int[]> segments,
                                                  TriangleMesherInput original) {
        TriangleMesherInput in = new TriangleMesherInput();
        in.numberOfPoints = points.size();
        in.pointList = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            in.pointList[2 * i] = points.get(i)[0];
            in.pointList[2 * i + 1] = points.get(i)[1];
        }
        in.numberOfSegments = segments.size();
        in.segmentList = new int[segments.size() * 2];
        in.segmentMarkerList = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            in.segmentList[2 * i] = segments.get(i)[0];
            in.segmentList[2 * i + 1] = segments.get(i)[1];
            in.segmentMarkerList[i] = segments.get(i)[2];
        }
        in.holeList = original.holeList;
        in.numberOfHoles = original.numberOfHoles;
        in.regionList = original.regionList;
        in.numberOfRegions = original.numberOfRegions;
        in.quiet = true;
        return in;
    }

    /** First subsegment with an adjacent triangle apex inside its diametral disk. */
    private static int encroachedSubsegment(TriangleMesherOutput mesh,
                                            List<double[]> points,
                                            List<int[]> segments) {
        for (int s = 0; s < segments.size(); s++) {
            int a = segments.get(s)[0], b = segments.get(s)[1];
            for (int t = 0; t < mesh.numberOfTriangles; t++) {
                int c0 = mesh.triangleList[3 * t], c1 = mesh.triangleList[3 * t + 1],
                        c2 = mesh.triangleList[3 * t + 2];
                int apex = thirdVertex(c0, c1, c2, a, b);
                if (apex >= 0 && inDiametralDisk(points, a, b, points.get(apex))) {
                    return s;
                }
            }
        }
        return -1;
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

    /** A triangle below the angle bound, or larger than its region's max area. */
    private static int badTriangle(TriangleMesherOutput mesh, List<double[]> points,
                                   double bound, Map<Double, Double> maxAreaByAttr) {
        for (int t = 0; t < mesh.numberOfTriangles; t++) {
            double[] a = points.get(mesh.triangleList[3 * t]);
            double[] b = points.get(mesh.triangleList[3 * t + 1]);
            double[] c = points.get(mesh.triangleList[3 * t + 2]);
            if (minAngleDeg(a, b, c) < bound) {
                return t;                                   /* skinny */
            }
            if (!maxAreaByAttr.isEmpty() && mesh.triangleAttributeList != null) {
                Double maxArea = maxAreaByAttr.get(mesh.triangleAttributeList[t]);
                if (maxArea != null && triangleArea(a, b, c) > maxArea) {
                    return t;                               /* too large for region */
                }
            }
        }
        return -1;
    }

    private static double triangleArea(double[] a, double[] b, double[] c) {
        return Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
                / 2.0;
    }

    private static void splitSegment(List<double[]> points, List<int[]> segments, int s) {
        int[] seg = segments.get(s);
        double[] a = points.get(seg[0]), b = points.get(seg[1]);
        int m = points.size();
        points.add(new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2});
        segments.set(s, new int[]{seg[0], m, seg[2]});
        segments.add(new int[]{m, seg[1], seg[2]});
    }

    private static int thirdVertex(int c0, int c1, int c2, int a, int b) {
        boolean h0a = c0 == a, h1a = c1 == a, h2a = c2 == a;
        boolean h0b = c0 == b, h1b = c1 == b, h2b = c2 == b;
        if (!(h0a || h1a || h2a) || !(h0b || h1b || h2b)) {
            return -1;                              /* triangle lacks the edge */
        }
        if (c0 != a && c0 != b) return c0;
        if (c1 != a && c1 != b) return c1;
        return c2;
    }

    private static boolean inDiametralDisk(List<double[]> points, int a, int b,
                                           double[] p) {
        double[] pa = points.get(a), pb = points.get(b);
        double dot = (p[0] - pa[0]) * (p[0] - pb[0]) + (p[1] - pa[1]) * (p[1] - pb[1]);
        return dot < 0;                             /* angle a-p-b obtuse */
    }

    private static double[] circumcentre(TriangleMesherOutput mesh,
                                         List<double[]> points, int t) {
        double[] a = points.get(mesh.triangleList[3 * t]);
        double[] b = points.get(mesh.triangleList[3 * t + 1]);
        double[] c = points.get(mesh.triangleList[3 * t + 2]);
        double d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
        double a2 = a[0] * a[0] + a[1] * a[1];
        double b2 = b[0] * b[0] + b[1] * b[1];
        double c2 = c[0] * c[0] + c[1] * c[1];
        double ux = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d;
        double uy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d;
        return new double[]{ux, uy};
    }

    private static double minAngleDeg(double[] a, double[] b, double[] c) {
        double[][] p = {a, b, c};
        double best = 180;
        for (int k = 0; k < 3; k++) {
            double[] o = p[k], u = p[(k + 1) % 3], v = p[(k + 2) % 3];
            double ux = u[0] - o[0], uy = u[1] - o[1];
            double vx = v[0] - o[0], vy = v[1] - o[1];
            double lu = Math.hypot(ux, uy), lv = Math.hypot(vx, vy);
            if (lu == 0 || lv == 0) {
                return 0;
            }
            double cs = Math.max(-1, Math.min(1, (ux * vx + uy * vy) / (lu * lv)));
            best = Math.min(best, Math.toDegrees(Math.acos(cs)));
        }
        return best;
    }
}
