package com.acme.triangle.impl;

import com.acme.triangle.predicate.Predicates;

/**
 * The single front door to the robust {@link Predicates}: every orientation and
 * in-circle test in the mesher routes through here, so {@code Predicates} stays
 * an implementation detail reached nowhere else in this package. Methods come in
 * families by the coordinate store the caller holds - a flat interleaved
 * {@code double[]} and a pre-flatten {@code List<double[]>} for the construction
 * stages, and a {@link Points} for the refinement kernel - each feeding the same
 * exact predicate.
 */
final class Geometry {

    private Geometry() {
    }

    /* --- flat double[]: vertex i at (pts[2*i], pts[2*i+1]) ------------------- */

    /** Orientation of vertices (a, b, c): &gt;0 CCW, &lt;0 CW, 0 collinear. */
    static int orient2d(double[] pts, int a, int b, int c) {
        return Predicates.orient2d(pts[2 * a], pts[2 * a + 1],
                pts[2 * b], pts[2 * b + 1], pts[2 * c], pts[2 * c + 1]);
    }

    /** Orientation of directed edge (a, b) against the loose point (x, y). */
    static int orient2d(double[] pts, int a, int b, double x, double y) {
        return Predicates.orient2d(pts[2 * a], pts[2 * a + 1],
                pts[2 * b], pts[2 * b + 1], x, y);
    }

    /** Whether vertex {@code d} lies inside the circumcircle of CCW triangle {@code t}. */
    static boolean inCircle(double[] pts, Corners t, int d) {
        return Predicates.incircle(
                pts[2 * t.a], pts[2 * t.a + 1],
                pts[2 * t.b], pts[2 * t.b + 1],
                pts[2 * t.c], pts[2 * t.c + 1],
                pts[2 * d], pts[2 * d + 1]) > 0;
    }

    /* --- Points (refinement kernel and the construction working set) --------- */

    /** Orientation of vertices (a, b, c): &gt;0 CCW, &lt;0 CW, 0 collinear. */
    static int orient2d(Points points, int a, int b, int c) {
        return Predicates.orient2d(points.x(a), points.y(a),
                points.x(b), points.y(b), points.x(c), points.y(c));
    }

    /** Orientation of directed edge (a, b) against the loose point (x, y). */
    static int orient2d(Points points, int a, int b, double x, double y) {
        return Predicates.orient2d(points.x(a), points.y(a),
                points.x(b), points.y(b), x, y);
    }

    /** Whether the loose point {@code p} lies inside the circumcircle of the CCW
        triangle with corners {@code (a, b, c)}. */
    static boolean inCircle(Points points, int a, int b, int c, Point p) {
        return Predicates.incircle(
                points.x(a), points.y(a),
                points.x(b), points.y(b),
                points.x(c), points.y(c),
                p.x, p.y) > 0;
    }
}
