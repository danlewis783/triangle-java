package com.acme.triangle.impl;

import com.acme.triangle.Point;
import com.acme.triangle.Points;
import com.acme.triangle.predicate.Predicates;

/**
 * The single front door to the robust {@link Predicates}: every orientation and
 * in-circle test in the mesher routes through here, so {@code Predicates} stays
 * an implementation detail reached nowhere else in this package. Every stage -
 * the initial Delaunay, constrained-Delaunay construction, and the refinement
 * kernel - addresses its vertices through a {@link Points} store, so these are
 * the one family the mesher needs, each feeding the same exact predicate.
 */
final class Geometry {

    private Geometry() {
    }

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
                p.getX(), p.getY()) > 0;
    }

    /** Whether vertex {@code d} lies inside the circumcircle of CCW triangle {@code t}. */
    static boolean inCircle(Points points, Corners t, int d) {
        return Predicates.incircle(
                points.x(t.a), points.y(t.a),
                points.x(t.b), points.y(t.b),
                points.x(t.c), points.y(t.c),
                points.x(d), points.y(d)) > 0;
    }
}
