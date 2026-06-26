package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.predicate.Predicates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delaunay triangulation of a point set by Bowyer-Watson incremental insertion.
 * <p>
 * Phase 1 of the pure-Java port. Because the consumer needs contract
 * equivalence (a valid Delaunay mesh), not Triangle's exact triangulation, this
 * uses the simplest correct algorithm rather than Triangle's divide-and-conquer:
 * insert each point, delete every triangle whose circumcircle contains it, and
 * fill the resulting cavity by joining the point to the cavity's boundary edges.
 * The robust {@link Predicates} make the incircle and orientation tests exact,
 * so the result is a genuine Delaunay triangulation.
 * <p>
 * Triangles are kept counterclockwise, and the output neighbour list follows
 * the convention {@code neighbor[3*i+j]} is the triangle across the edge
 * opposite corner {@code j}. This is the simple, correct version (O(n^2));
 * spatial acceleration can come later, validated by the same oracle.
 */
public final class DelaunayTriangulator {

    private DelaunayTriangulator() {
    }

    /**
     * @param points    interleaved coordinates {@code x0,y0,x1,y1,...}
     * @param numPoints number of points (indices 0..numPoints-1)
     * @return a Delaunay mesh: pointList, triangleList, neighborList, no segments
     */
    public static TriangleMesherOutput triangulate(double[] points, int numPoints) {
        if (numPoints < 3) {
            throw new IllegalArgumentException("need at least 3 points");
        }
        /* Working coordinates: the input points plus three super-triangle
           vertices large enough to contain them all. */
        double[] pts = Arrays.copyOf(points, (numPoints + 3) * 2);
        int sa = numPoints, sb = numPoints + 1, sc = numPoints + 2;
        addSuperTriangle(pts, numPoints, sa, sb, sc);

        List<Corners> triangles = new ArrayList<>();
        triangles.add(ccw(pts, sa, sb, sc));

        for (int p = 0; p < numPoints; p++) {
            insert(pts, triangles, p);
        }

        /* Drop triangles incident to a super-triangle vertex. */
        List<Corners> kept = new ArrayList<>();
        for (Corners t : triangles) {
            if (t.a < numPoints && t.b < numPoints && t.c < numPoints) {
                kept.add(t);
            }
        }
        return buildOutput(points, numPoints, kept);
    }

    private static void insert(double[] pts, List<Corners> triangles, int p) {
        List<Corners> survivors = new ArrayList<>();
        List<Corners> cavity = new ArrayList<>();
        for (Corners t : triangles) {
            if (inCircumcircle(pts, t, p)) {
                cavity.add(t);
            } else {
                survivors.add(t);
            }
        }
        /* All directed edges of cavity triangles; an edge is on the cavity
           boundary when its reverse is not also a cavity edge. */
        Set<Long> cavityEdges = new HashSet<>();
        for (Corners t : cavity) {
            cavityEdges.add(directed(t.a, t.b));
            cavityEdges.add(directed(t.b, t.c));
            cavityEdges.add(directed(t.c, t.a));
        }
        for (Corners t : cavity) {
            connect(pts, survivors, cavityEdges, t.a, t.b, p);
            connect(pts, survivors, cavityEdges, t.b, t.c, p);
            connect(pts, survivors, cavityEdges, t.c, t.a, p);
        }
        triangles.clear();
        triangles.addAll(survivors);
    }

    /** If (a,b) is a boundary edge, add the new triangle joining it to p (CCW). */
    private static void connect(double[] pts, List<Corners> out, Set<Long> cavityEdges,
                                int a, int b, int p) {
        if (cavityEdges.contains(directed(b, a))) {
            return;                                 /* interior cavity edge */
        }
        int s = orient(pts, a, b, p);
        if (s > 0) {
            out.add(new Corners(a, b, p));
        } else if (s < 0) {
            out.add(new Corners(b, a, p));
        }
        /* s == 0: p collinear with the boundary edge - degenerate, skip. */
    }

    private static TriangleMesherOutput buildOutput(double[] points, int n,
                                                    List<Corners> tris) {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = n;
        o.pointList = Arrays.copyOf(points, n * 2);
        o.numberOfTriangles = tris.size();
        o.numberOfSegments = 0;
        o.triangleList = new int[tris.size() * 3];
        for (int i = 0; i < tris.size(); i++) {
            Corners t = tris.get(i);
            o.triangleList[3 * i] = t.a;
            o.triangleList[3 * i + 1] = t.b;
            o.triangleList[3 * i + 2] = t.c;
        }
        o.neighborList = buildNeighbors(o.triangleList, tris.size());
        return o;
    }

    /** neighbor[3*i+j] = triangle across the edge opposite corner j, or -1. */
    private static int[] buildNeighbors(int[] tri, int t) {
        int[] neigh = new int[t * 3];
        Arrays.fill(neigh, -1);
        Map<Long, int[]> firstSeen = new HashMap<>();
        for (int i = 0; i < t; i++) {
            for (int j = 0; j < 3; j++) {
                int u = tri[3 * i + (j + 1) % 3];
                int v = tri[3 * i + (j + 2) % 3];
                long key = undirected(u, v);
                int[] prev = firstSeen.get(key);
                if (prev == null) {
                    firstSeen.put(key, new int[]{i, j});
                } else {
                    neigh[3 * i + j] = prev[0];
                    neigh[3 * prev[0] + prev[1]] = i;
                }
            }
        }
        return neigh;
    }

    private static void addSuperTriangle(double[] pts, int n, int sa, int sb, int sc) {
        double minx = pts[0], miny = pts[1], maxx = pts[0], maxy = pts[1];
        for (int i = 1; i < n; i++) {
            minx = Math.min(minx, pts[2 * i]);
            maxx = Math.max(maxx, pts[2 * i]);
            miny = Math.min(miny, pts[2 * i + 1]);
            maxy = Math.max(maxy, pts[2 * i + 1]);
        }
        double d = Math.max(maxx - minx, maxy - miny);
        if (d <= 0) {
            d = 1;
        }
        double m = 1000 * d;
        double cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
        pts[2 * sa] = cx - m;     pts[2 * sa + 1] = cy - m;
        pts[2 * sb] = cx + m;     pts[2 * sb + 1] = cy - m;
        pts[2 * sc] = cx;         pts[2 * sc + 1] = cy + m;
    }

    private static Corners ccw(double[] pts, int a, int b, int c) {
        return orient(pts, a, b, c) >= 0 ? new Corners(a, b, c) : new Corners(a, c, b);
    }

    private static int orient(double[] pts, int a, int b, int c) {
        return Predicates.orient2d(pts[2 * a], pts[2 * a + 1],
                pts[2 * b], pts[2 * b + 1], pts[2 * c], pts[2 * c + 1]);
    }

    private static boolean inCircumcircle(double[] pts, Corners t, int p) {
        return Predicates.incircle(
                pts[2 * t.a], pts[2 * t.a + 1],
                pts[2 * t.b], pts[2 * t.b + 1],
                pts[2 * t.c], pts[2 * t.c + 1],
                pts[2 * p], pts[2 * p + 1]) > 0;
    }

    private static long directed(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private static long undirected(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
