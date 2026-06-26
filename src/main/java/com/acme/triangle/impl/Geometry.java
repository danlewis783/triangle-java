package com.acme.triangle.impl;

import com.acme.triangle.predicate.Predicates;

/**
 * Robust geometric predicates over a flat interleaved coordinate array, where
 * vertex {@code i} is at {@code pts[2*i], pts[2*i+1]}. Wraps the exact
 * {@link Predicates} with the index addressing that the construction stages -
 * the initial Delaunay and constraint recovery - share.
 * <p>
 * The refinement kernel ({@link IncrementalCdt}) deliberately keeps its own
 * list-backed wrappers rather than routing here: it stores coordinates as a
 * growing {@code List<double[]>}, and these tests sit on its insertion hot path,
 * so it reads them directly without a second coordinate representation.
 */
final class Geometry {

    private Geometry() {
    }

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
}
