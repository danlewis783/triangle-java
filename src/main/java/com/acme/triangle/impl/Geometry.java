package com.acme.triangle.impl;

import com.acme.triangle.Point;
import com.acme.triangle.predicate.Predicates;

import java.util.List;

/**
 * The single front door to the robust {@link Predicates}: every orientation and
 * in-circle test in the mesher routes through here, so {@code Predicates} stays
 * an implementation detail reached nowhere else in this package. Every stage -
 * the initial Delaunay, constrained-Delaunay construction, and the refinement
 * kernel - addresses its vertices through a {@code List<Point>}, so these are
 * the one family the mesher needs, each feeding the same exact predicate.
 */
final class Geometry {

    private Geometry() {
    }

    /** Orientation of vertices (a, b, c): &gt;0 CCW, &lt;0 CW, 0 collinear. */
    static int orient2d(List<Point> points, int a, int b, int c) {
        Point ptA = points.get(a);
        Point ptB = points.get(b);
        Point ptC = points.get(c);
        return Predicates.orient2d(ptA.getX(), ptA.getY(), ptB.getX(), ptB.getY(), ptC.getX(), ptC.getY());
    }

    /** Orientation of directed edge (a, b) against the loose point (x, y). */
    static int orient2d(List<Point> points, int a, int b, double x, double y) {
        Point ptA = points.get(a);
        Point ptB = points.get(b);
        return Predicates.orient2d(ptA.getX(), ptA.getY(), ptB.getX(), ptB.getY(), x, y);
    }

    /** Whether the loose point {@code p} lies inside the circumcircle of the CCW
        triangle with corners {@code (a, b, c)}. */
    static boolean inCircle(List<Point> points, int a, int b, int c, Point p) {
        Point ptA = points.get(a);
        Point ptB = points.get(b);
        Point ptC = points.get(c);
        return Predicates.incircle(
                ptA.getX(), ptA.getY(),
                ptB.getX(), ptB.getY(),
                ptC.getX(), ptC.getY(),
                p.getX(), p.getY()) > 0;
    }
}
