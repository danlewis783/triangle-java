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
 * <p>This is the interior-insertion core. Segment splitting and the refinement
 * loop integration build on it; until then nothing in production uses this class.
 */
final class IncrementalCdt {

    private final List<double[]> points = new ArrayList<>();
    private List<int[]> tris = new ArrayList<>();            /* CCW {a, b, c} */
    private List<Double> attrs;                              /* per-tri attr, or null */
    private final List<int[]> segments = new ArrayList<>();  /* {a, b, marker} */
    private final Set<Long> segSet = new HashSet<>();

    IncrementalCdt(TriangleMesherOutput base) {
        for (int i = 0; i < base.numberOfPoints; i++) {
            points.add(new double[]{base.pointList[2 * i], base.pointList[2 * i + 1]});
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
        if (base.segmentList != null) {
            for (int i = 0; i < base.numberOfSegments; i++) {
                int a = base.segmentList[2 * i], b = base.segmentList[2 * i + 1];
                int marker = base.segmentMarkerList != null ? base.segmentMarkerList[i] : 0;
                segments.add(new int[]{a, b, marker});
                segSet.add(key(a, b));
            }
        }
    }

    int pointCount() {
        return points.size();
    }

    int triangleCount() {
        return tris.size();
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
        int a = seg[0], b = seg[1], marker = seg[2];
        int[] seeds = incidentTriangles(a, b);
        if (seeds.length == 0) {
            throw new IllegalStateException("segment (" + a + "," + b + ") is not an edge");
        }
        double[] pa = points.get(a), pb = points.get(b);
        int mIdx = points.size();
        points.add(new double[]{(pa[0] + pb[0]) / 2.0, (pa[1] + pb[1]) / 2.0});

        segSet.remove(key(a, b));            /* let the cavity span the old segment */
        insertViaCavity(mIdx, seeds, key(a, b));

        segments.set(segIndex, new int[]{a, mIdx, marker});
        segments.add(new int[]{mIdx, b, marker});
        segSet.add(key(a, mIdx));
        segSet.add(key(mIdx, b));
        return mIdx;
    }

    /**
     * Constrained Bowyer-Watson insertion of an already-added vertex {@code pIdx}.
     * Starting from {@code seeds} (triangles already known to contain it in their
     * circumcircle), gather the cavity of triangles whose circumcircle contains
     * it - never crossing a current segment - then re-fan the cavity boundary
     * around it. The boundary edge equal to {@code skipEdgeKey} is not re-fanned
     * (used when splitting a boundary segment, where the split edge would form a
     * degenerate triangle); pass {@code -1L} for none. Each new triangle inherits
     * its source cavity triangle's region attribute, so a cavity that spans two
     * regions attributes correctly.
     */
    private void insertViaCavity(int pIdx, int[] seeds, long skipEdgeKey) {
        double[] p = points.get(pIdx);
        int[] adj = adjacency(tris);
        boolean[] inCavity = new boolean[tris.size()];
        List<Integer> cavity = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int s : seeds) {
            if (!inCavity[s]) {
                inCavity[s] = true;
                stack.push(s);
            }
        }
        while (!stack.isEmpty()) {
            int t = stack.pop();
            cavity.add(t);
            int[] tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = adj[3 * t + j];
                if (nb < 0 || inCavity[nb]) {
                    continue;
                }
                int u = tc[(j + 1) % 3], w = tc[(j + 2) % 3];
                if (segSet.contains(key(u, w))) {
                    continue;                       /* never cross a segment */
                }
                if (inCircle(tris.get(nb), p)) {
                    inCavity[nb] = true;
                    stack.push(nb);
                }
            }
        }

        /* Re-fan: a cavity edge is on the boundary when its neighbour is outside
           the cavity or it is a segment. Each new triangle keeps its source
           triangle's attribute. */
        List<int[]> newTris = new ArrayList<>();
        List<Double> newAttr = attrs != null ? new ArrayList<>() : null;
        for (int t : cavity) {
            int[] tc = tris.get(t);
            for (int j = 0; j < 3; j++) {
                int nb = adj[3 * t + j];
                int u = tc[(j + 1) % 3], w = tc[(j + 2) % 3];
                long k = key(u, w);
                boolean boundary = nb < 0 || !inCavity[nb] || segSet.contains(k);
                if (boundary && k != skipEdgeKey) {
                    newTris.add(ccw(u, w, pIdx));
                    if (attrs != null) {
                        newAttr.add(attrs.get(t));
                    }
                }
            }
        }

        List<int[]> kept = new ArrayList<>();
        List<Double> keptAttr = attrs != null ? new ArrayList<>() : null;
        for (int i = 0; i < tris.size(); i++) {
            if (!inCavity[i]) {
                kept.add(tris.get(i));
                if (attrs != null) {
                    keptAttr.add(attrs.get(i));
                }
            }
        }
        kept.addAll(newTris);
        if (attrs != null) {
            keptAttr.addAll(newAttr);
        }
        tris = kept;
        attrs = keptAttr;
    }

    /** The (one or two) triangles having both a and b as corners. */
    private int[] incidentTriangles(int a, int b) {
        int t1 = -1, t2 = -1;
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
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
        int t = tris.size();
        o.numberOfTriangles = t;
        o.triangleList = new int[3 * t];
        boolean haveAttr = attrs != null;
        if (haveAttr) {
            o.triangleAttributeList = new double[t];
        }
        for (int i = 0; i < t; i++) {
            int[] tr = tris.get(i);
            o.triangleList[3 * i] = tr[0];
            o.triangleList[3 * i + 1] = tr[1];
            o.triangleList[3 * i + 2] = tr[2];
            if (haveAttr) {
                o.triangleAttributeList[i] = attrs.get(i);
            }
        }
        o.neighborList = adjacency(tris);
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

    /** Centroid of the current largest-area triangle (a robust interior point). */
    double[] centroidOfLargestTriangle() {
        int best = -1;
        double bestArea = -1;
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
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

    /** neigh[3*i+j] = triangle across the edge opposite corner j, or -1. */
    private int[] adjacency(List<int[]> ts) {
        int n = ts.size();
        int[] neigh = new int[3 * n];
        Arrays.fill(neigh, -1);
        Map<Long, int[]> edge = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] t = ts.get(i);
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

    private int[] ccw(int a, int b, int c) {
        return orient(a, b, c) >= 0 ? new int[]{a, b, c} : new int[]{a, c, b};
    }

    private int orient(int a, int b, int c) {
        return Predicates.orient2d(points.get(a)[0], points.get(a)[1],
                points.get(b)[0], points.get(b)[1], points.get(c)[0], points.get(c)[1]);
    }

    private int orientXY(int a, int b, double x, double y) {
        return Predicates.orient2d(points.get(a)[0], points.get(a)[1],
                points.get(b)[0], points.get(b)[1], x, y);
    }

    private static long key(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
