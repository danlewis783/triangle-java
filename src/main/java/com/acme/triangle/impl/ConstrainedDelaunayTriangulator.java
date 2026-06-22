package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.predicate.Predicates;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
 * All geometric tests use the robust {@link Predicates}. Refinement (quality) is
 * phase 3; this produces the unrefined constrained Delaunay mesh.
 */
public final class ConstrainedDelaunayTriangulator {

    private ConstrainedDelaunayTriangulator() {
    }

    public static TriangleMesherOutput triangulate(TriangleMesherInput in) {
        /* 1. Split crossing segments. */
        Pslg pslg = splitIntersections(in);
        double[] pts = pslg.points;
        int np = pslg.numPoints;

        /* 2. Initial Delaunay of all points. */
        List<int[]> tris = toList(DelaunayTriangulator.triangulate(pts, np).triangleList);

        /* 3. Recover segments. */
        Set<Long> segSet = new HashSet<>();
        for (int[] s : pslg.segments) {
            insertSegment(pts, tris, s[0], s[1]);
            segSet.add(key(s[0], s[1]));
        }

        /* 4. Restore constrained Delaunay. */
        restoreDelaunay(pts, tris, segSet);

        /* 5. Carve holes and concavities. */
        boolean[] removed = carve(pts, tris, segSet, in.holeList, in.numberOfHoles);

        /* 6. Attribute regions. */
        double[] attr = attributeRegions(pts, tris, removed, segSet,
                in.regionList, in.numberOfRegions);

        return buildOutput(pts, np, tris, removed, attr, pslg);
    }

    /* --- 1. segment intersection splitting ----------------------------------- */

    private static final class Pslg {
        double[] points;
        int numPoints;
        List<int[]> segments;     /* {a, b, marker} */
        boolean hasRegions;
    }

    private static Pslg splitIntersections(TriangleMesherInput in) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < in.numberOfPoints; i++) {
            pts.add(new double[]{in.pointList[2 * i], in.pointList[2 * i + 1]});
        }
        List<int[]> segs = new ArrayList<>();
        for (int i = 0; i < in.numberOfSegments; i++) {
            int marker = in.segmentMarkerList != null ? in.segmentMarkerList[i] : 0;
            segs.add(new int[]{in.segmentList[2 * i], in.segmentList[2 * i + 1], marker});
        }

        boolean changed = true;
        while (changed) {
            changed = false;

            /* (a) Two segments cross: split both at their intersection point. */
            crossings:
            for (int i = 0; i < segs.size(); i++) {
                for (int j = i + 1; j < segs.size(); j++) {
                    int[] a = segs.get(i), b = segs.get(j);
                    if (share(a, b)) {
                        continue;
                    }
                    if (cross(pts, a[0], a[1], b[0], b[1])) {
                        double[] x = intersection(pts, a[0], a[1], b[0], b[1]);
                        int c = pts.size();
                        pts.add(x);
                        int[] a0 = {a[0], c, a[2]}, a1 = {c, a[1], a[2]};
                        int[] b0 = {b[0], c, b[2]}, b1 = {c, b[1], b[2]};
                        segs.set(i, a0);
                        segs.set(j, b0);
                        segs.add(a1);
                        segs.add(b1);
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
                int[] s = segs.get(i);
                for (int v = 0; v < pts.size(); v++) {
                    if (v != s[0] && v != s[1] && onSegmentInterior(pts, s[0], s[1], v)) {
                        segs.set(i, new int[]{s[0], v, s[2]});
                        segs.add(new int[]{v, s[1], s[2]});
                        changed = true;
                        break onEdge;
                    }
                }
            }
        }

        Pslg p = new Pslg();
        p.numPoints = pts.size();
        p.points = new double[p.numPoints * 2];
        for (int i = 0; i < p.numPoints; i++) {
            p.points[2 * i] = pts.get(i)[0];
            p.points[2 * i + 1] = pts.get(i)[1];
        }
        p.segments = segs;
        p.hasRegions = in.numberOfRegions > 0;
        return p;
    }

    private static boolean share(int[] a, int[] b) {
        return a[0] == b[0] || a[0] == b[1] || a[1] == b[0] || a[1] == b[1];
    }

    /** True if vertex v is exactly collinear with (a,b) and strictly between them. */
    private static boolean onSegmentInterior(List<double[]> pts, int a, int b, int v) {
        if (orient(pts, a, b, v) != 0) {
            return false;
        }
        double[] pa = pts.get(a), pb = pts.get(b), pv = pts.get(v);
        double toA = (pv[0] - pa[0]) * (pb[0] - pa[0]) + (pv[1] - pa[1]) * (pb[1] - pa[1]);
        double toB = (pv[0] - pb[0]) * (pa[0] - pb[0]) + (pv[1] - pb[1]) * (pa[1] - pb[1]);
        return toA > 0 && toB > 0;            /* strictly inside, not at an endpoint */
    }

    private static double[] intersection(List<double[]> pts, int p0, int p1,
                                         int q0, int q1) {
        double x1 = pts.get(p0)[0], y1 = pts.get(p0)[1];
        double x2 = pts.get(p1)[0], y2 = pts.get(p1)[1];
        double x3 = pts.get(q0)[0], y3 = pts.get(q0)[1];
        double x4 = pts.get(q1)[0], y4 = pts.get(q1)[1];
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        return new double[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    /* --- 3. segment recovery via flips --------------------------------------- */

    private static void insertSegment(double[] pts, List<int[]> tris, int a, int b) {
        while (!isEdge(tris, a, b)) {
            int[] flip = findFlippableCrossing(pts, tris, a, b);
            if (flip == null) {
                return;                 /* defensive: nothing flippable */
            }
            doFlip(pts, tris, flip);
        }
    }

    /** @return {triHi, triLo, u, v, p, q} for a convex crossing edge, or null. */
    private static int[] findFlippableCrossing(double[] pts, List<int[]> tris,
                                               int a, int b) {
        Map<Long, int[]> edge = new HashMap<>();   /* edge -> {tri, oppositeCornerIdx} */
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            for (int j = 0; j < 3; j++) {
                int u = t[(j + 1) % 3], v = t[(j + 2) % 3];
                long k = key(u, v);
                int[] prev = edge.get(k);
                if (prev == null) {
                    edge.put(k, new int[]{i, j});
                } else {
                    int t1 = prev[0], t2 = i;
                    int p = tris.get(t1)[prev[1]];
                    int q = t[j];
                    if (cross(pts, u, v, a, b) && convex(pts, u, v, p, q)) {
                        return new int[]{Math.max(t1, t2), Math.min(t1, t2), u, v, p, q};
                    }
                }
            }
        }
        return null;
    }

    private static void doFlip(double[] pts, List<int[]> tris, int[] f) {
        tris.remove(f[0]);                 /* remove larger index first */
        tris.remove(f[1]);
        int u = f[2], v = f[3], p = f[4], q = f[5];
        tris.add(ccw(pts, p, v, q));
        tris.add(ccw(pts, q, u, p));
    }

    /* --- 4. constrained Delaunay restoration --------------------------------- */

    private static void restoreDelaunay(double[] pts, List<int[]> tris,
                                        Set<Long> segSet) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Long, int[]> edge = new HashMap<>();
            for (int i = 0; i < tris.size() && !changed; i++) {
                int[] t = tris.get(i);
                for (int j = 0; j < 3 && !changed; j++) {
                    int u = t[(j + 1) % 3], v = t[(j + 2) % 3];
                    long k = key(u, v);
                    int[] prev = edge.get(k);
                    if (prev == null) {
                        edge.put(k, new int[]{i, j});
                        continue;
                    }
                    if (segSet.contains(k)) {
                        continue;
                    }
                    int t1 = prev[0];
                    int p = tris.get(t1)[prev[1]];
                    int q = t[j];
                    if (inCircle(pts, tris.get(t1), q) && convex(pts, u, v, p, q)) {
                        doFlip(pts, tris, new int[]{Math.max(t1, i), Math.min(t1, i),
                                u, v, p, q});
                        changed = true;
                    }
                }
            }
        }
    }

    /* --- 5/6. carving and region attribution --------------------------------- */

    /** triangle adjacency: neigh[3*i+j] = triangle across edge opposite corner j. */
    private static int[] adjacency(List<int[]> tris) {
        int n = tris.size();
        int[] neigh = new int[3 * n];
        Arrays.fill(neigh, -1);
        Map<Long, int[]> edge = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] t = tris.get(i);
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

    private static boolean[] carve(double[] pts, List<int[]> tris, Set<Long> segSet,
                                   double[] holes, int numHoles) {
        int n = tris.size();
        int[] neigh = adjacency(tris);
        boolean[] removed = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        /* Seed: triangles exposed to the outside across a non-segment hull edge. */
        for (int i = 0; i < n; i++) {
            int[] t = tris.get(i);
            for (int j = 0; j < 3; j++) {
                if (neigh[3 * i + j] == -1
                        && !segSet.contains(key(t[(j + 1) % 3], t[(j + 2) % 3]))
                        && !removed[i]) {
                    removed[i] = true;
                    queue.add(i);
                }
            }
        }
        /* Seed: triangle containing each hole point. */
        for (int h = 0; h < numHoles; h++) {
            int t = locate(pts, tris, holes[2 * h], holes[2 * h + 1]);
            if (t >= 0 && !removed[t]) {
                removed[t] = true;
                queue.add(t);
            }
        }
        flood(tris, neigh, segSet, removed, queue);
        return removed;
    }

    private static double[] attributeRegions(double[] pts, List<int[]> tris,
                                             boolean[] removed, Set<Long> segSet,
                                             double[] regions, int numRegions) {
        if (numRegions == 0) {
            return null;
        }
        int n = tris.size();
        int[] neigh = adjacency(tris);
        double[] attr = new double[n];
        for (int r = 0; r < numRegions; r++) {
            double rx = regions[4 * r], ry = regions[4 * r + 1];
            double rattr = regions[4 * r + 2];
            int start = locate(pts, tris, rx, ry);
            if (start < 0 || removed[start]) {
                continue;
            }
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean[] seen = new boolean[n];
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int t = queue.poll();
                attr[t] = rattr;
                int[] tc = tris.get(t);
                for (int j = 0; j < 3; j++) {
                    int nb = neigh[3 * t + j];
                    if (nb >= 0 && !removed[nb] && !seen[nb]
                            && !segSet.contains(key(tc[(j + 1) % 3], tc[(j + 2) % 3]))) {
                        seen[nb] = true;
                        queue.add(nb);
                    }
                }
            }
        }
        return attr;
    }

    private static void flood(List<int[]> tris, int[] neigh, Set<Long> segSet,
                              boolean[] removed, ArrayDeque<Integer> queue) {
        while (!queue.isEmpty()) {
            int t = queue.poll();
            int[] tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = neigh[3 * t + j];
                if (nb >= 0 && !removed[nb]
                        && !segSet.contains(key(tc[(j + 1) % 3], tc[(j + 2) % 3]))) {
                    removed[nb] = true;
                    queue.add(nb);
                }
            }
        }
    }

    /* The first triangle whose closed region contains (x,y). On-edge counts, so
       a seed lying exactly on a triangle edge still locates a triangle (it would
       be missed by a strictly-inside test). */
    private static int locate(double[] pts, List<int[]> tris, double x, double y) {
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            int s1 = orientXY(pts, t[0], t[1], x, y);
            int s2 = orientXY(pts, t[1], t[2], x, y);
            int s3 = orientXY(pts, t[2], t[0], x, y);
            boolean nonNeg = s1 >= 0 && s2 >= 0 && s3 >= 0;
            boolean nonPos = s1 <= 0 && s2 <= 0 && s3 <= 0;
            if ((nonNeg || nonPos) && !(s1 == 0 && s2 == 0 && s3 == 0)) {
                return i;
            }
        }
        return -1;
    }

    /* --- output -------------------------------------------------------------- */

    private static TriangleMesherOutput buildOutput(double[] pts, int np,
                                                    List<int[]> tris, boolean[] removed,
                                                    double[] attr, Pslg pslg) {
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < tris.size(); i++) {
            if (!removed[i]) {
                keep.add(i);
            }
        }
        int t = keep.size();
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = np;
        o.pointList = Arrays.copyOf(pts, np * 2);
        o.numberOfTriangles = t;
        o.triangleList = new int[3 * t];
        boolean haveAttr = attr != null;
        if (haveAttr) {
            o.triangleAttributeList = new double[t];
        }
        for (int i = 0; i < t; i++) {
            int[] tr = tris.get(keep.get(i));
            o.triangleList[3 * i] = tr[0];
            o.triangleList[3 * i + 1] = tr[1];
            o.triangleList[3 * i + 2] = tr[2];
            if (haveAttr) {
                o.triangleAttributeList[i] = attr[keep.get(i)];
            }
        }
        o.neighborList = rebuildNeighbors(o.triangleList, t);

        o.numberOfSegments = pslg.segments.size();
        o.segmentList = new int[2 * pslg.segments.size()];
        o.segmentMarkerList = new int[pslg.segments.size()];
        for (int i = 0; i < pslg.segments.size(); i++) {
            int[] s = pslg.segments.get(i);
            o.segmentList[2 * i] = s[0];
            o.segmentList[2 * i + 1] = s[1];
            o.segmentMarkerList[i] = s[2];
        }
        return o;
    }

    private static int[] rebuildNeighbors(int[] tri, int t) {
        int[] neigh = new int[3 * t];
        Arrays.fill(neigh, -1);
        Map<Long, int[]> edge = new HashMap<>();
        for (int i = 0; i < t; i++) {
            for (int j = 0; j < 3; j++) {
                long k = key(tri[3 * i + (j + 1) % 3], tri[3 * i + (j + 2) % 3]);
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

    /* --- geometry helpers ---------------------------------------------------- */

    private static List<int[]> toList(int[] triangleList) {
        List<int[]> tris = new ArrayList<>();
        for (int i = 0; i < triangleList.length; i += 3) {
            tris.add(new int[]{triangleList[i], triangleList[i + 1], triangleList[i + 2]});
        }
        return tris;
    }

    private static boolean isEdge(List<int[]> tris, int a, int b) {
        for (int[] t : tris) {
            boolean ha = t[0] == a || t[1] == a || t[2] == a;
            boolean hb = t[0] == b || t[1] == b || t[2] == b;
            if (ha && hb) {
                return true;
            }
        }
        return false;
    }

    private static boolean cross(double[] pts, int u, int v, int a, int b) {
        int d1 = orient(pts, a, b, u), d2 = orient(pts, a, b, v);
        int d3 = orient(pts, u, v, a), d4 = orient(pts, u, v, b);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static boolean cross(List<double[]> pts, int u, int v, int a, int b) {
        int d1 = orient(pts, a, b, u), d2 = orient(pts, a, b, v);
        int d3 = orient(pts, u, v, a), d4 = orient(pts, u, v, b);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static boolean convex(double[] pts, int u, int v, int p, int q) {
        return orient(pts, p, q, u) * orient(pts, p, q, v) < 0;
    }

    private static int[] ccw(double[] pts, int a, int b, int c) {
        return orient(pts, a, b, c) >= 0 ? new int[]{a, b, c} : new int[]{a, c, b};
    }

    private static boolean inCircle(double[] pts, int[] t, int p) {
        return Predicates.incircle(
                pts[2 * t[0]], pts[2 * t[0] + 1], pts[2 * t[1]], pts[2 * t[1] + 1],
                pts[2 * t[2]], pts[2 * t[2] + 1], pts[2 * p], pts[2 * p + 1]) > 0;
    }

    private static int orient(double[] pts, int a, int b, int c) {
        return Predicates.orient2d(pts[2 * a], pts[2 * a + 1], pts[2 * b],
                pts[2 * b + 1], pts[2 * c], pts[2 * c + 1]);
    }

    private static int orient(List<double[]> pts, int a, int b, int c) {
        return Predicates.orient2d(pts.get(a)[0], pts.get(a)[1], pts.get(b)[0],
                pts.get(b)[1], pts.get(c)[0], pts.get(c)[1]);
    }

    private static int orientXY(double[] pts, int a, int b, double x, double y) {
        return Predicates.orient2d(pts[2 * a], pts[2 * a + 1], pts[2 * b],
                pts[2 * b + 1], x, y);
    }

    private static long key(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
