package com.acme.triangle.impl;

import com.acme.triangle.predicate.Predicates;

/**
 * The single front door to the robust {@link Predicates}: every orientation and
 * in-circle test in the mesher routes through here, so {@code Predicates} stays
 * an implementation detail reached nowhere else in this package. Every stage -
 * the initial Delaunay, constrained-Delaunay construction, and the refinement
 * kernel - addresses its vertices through a {@link FlatPointList}, so these are
 * the one family the mesher needs, each feeding the same exact predicate.
 */
final class Geometry {

    private Geometry() {
    }

    /** Orientation of vertices (a, b, c): &gt;0 CCW, &lt;0 CW, 0 collinear. */
    static int orient2d(FlatPointList points, int a, int b, int c) {
        return Predicates.orient2d(points.x(a), points.y(a),
                points.x(b), points.y(b), points.x(c), points.y(c));
    }

    /** Orientation of directed edge (a, b) against the loose point (x, y). */
    static int orient2d(FlatPointList points, int a, int b, double x, double y) {
        return Predicates.orient2d(points.x(a), points.y(a),
                points.x(b), points.y(b), x, y);
    }

    /** Whether the loose point {@code (px, py)} lies inside the circumcircle of
        the CCW triangle with corners {@code (a, b, c)}. */
    static boolean inCircle(FlatPointList points, int a, int b, int c,
                            double px, double py) {
        return Predicates.incircle(
                points.x(a), points.y(a),
                points.x(b), points.y(b),
                points.x(c), points.y(c),
                px, py) > 0;
    }
}
