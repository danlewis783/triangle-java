package com.acme.triangle.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delaunay triangulation of a point set by Bowyer-Watson incremental insertion.
 * <p>
 * Phase 1 of the pure-Java port. Because the consumer needs contract
 * equivalence (a valid Delaunay mesh), not Triangle's exact triangulation, this
 * uses the simplest correct algorithm rather than Triangle's divide-and-conquer:
 * insert each point, delete every triangle whose circumcircle contains it, and
 * fill the resulting cavity by joining the point to the cavity's boundary edges.
 * The robust {@link Geometry} predicates make the incircle and orientation
 * tests exact, so the result is a genuine Delaunay triangulation.
 * <p>
 * Triangles are returned counterclockwise as {@link Corners} - the
 * construction-phase corner triples the consumer
 * ({@link ConstrainedDelaunayTriangulator}) goes on to recover constraints
 * into. Adjacency and any flat output are derived by the caller, since this
 * phase needs to hand back only the corners. This is the simple, correct
 * version (O(n^2)); spatial acceleration can come later, validated by the same
 * oracle.
 */
public final class DelaunayTriangulator {

    private DelaunayTriangulator() {
    }

    /**
     * @param points the input vertex store; indices {@code 0..size-1} address it
     * @return the Delaunay triangles as CCW {@link Corners} over the input
     *         points (a fresh, mutable list the caller may refine in place)
     */
    public static List<Corners> triangulate(Points points) {
        int numPoints = points.size();
        if (numPoints < 3) {
            throw new IllegalArgumentException("need at least 3 points");
        }
        /* Working store: a copy of the input points (the caller's must not be
           mutated) plus three super-triangle vertices large enough to contain
           them all, appended at indices numPoints, numPoints+1, numPoints+2. */
        Points pts = new Points(points.toArray(), numPoints);
        int sa = numPoints, sb = numPoints + 1, sc = numPoints + 2;
        addSuperTriangle(pts, numPoints);

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
        return kept;
    }

    private static void insert(Points pts, List<Corners> triangles, int p) {
        List<Corners> survivors = new ArrayList<>();
        List<Corners> cavity = new ArrayList<>();
        for (Corners t : triangles) {
            if (Geometry.inCircle(pts, t, p)) {
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
    private static void connect(Points pts, List<Corners> out, Set<Long> cavityEdges,
                                int a, int b, int p) {
        if (cavityEdges.contains(directed(b, a))) {
            return;                                 /* interior cavity edge */
        }
        int s = Geometry.orient2d(pts, a, b, p);
        if (s > 0) {
            out.add(new Corners(a, b, p));
        } else if (s < 0) {
            out.add(new Corners(b, a, p));
        }
        /* s == 0: p collinear with the boundary edge - degenerate, skip. */
    }

    /** Append three super-triangle vertices enclosing the first {@code n} points,
        landing at indices {@code n}, {@code n+1}, {@code n+2}. */
    private static void addSuperTriangle(Points pts, int n) {
        double minx = pts.x(0), miny = pts.y(0), maxx = pts.x(0), maxy = pts.y(0);
        for (int i = 1; i < n; i++) {
            minx = Math.min(minx, pts.x(i));
            maxx = Math.max(maxx, pts.x(i));
            miny = Math.min(miny, pts.y(i));
            maxy = Math.max(maxy, pts.y(i));
        }
        double d = Math.max(maxx - minx, maxy - miny);
        if (d <= 0) {
            d = 1;
        }
        double m = 1000 * d;
        double cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
        pts.add(cx - m, cy - m);
        pts.add(cx + m, cy - m);
        pts.add(cx, cy + m);
    }

    private static Corners ccw(Points pts, int a, int b, int c) {
        return Geometry.orient2d(pts, a, b, c) >= 0 ? new Corners(a, b, c) : new Corners(a, c, b);
    }

    private static long directed(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }
}
