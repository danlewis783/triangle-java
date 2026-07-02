package com.acme.triangle.impl;

/**
 * The three CCW corner vertex indices of a triangle at the phase boundary
 * between the initial Delaunay ({@link DelaunayTriangulator}) and constraint
 * recovery ({@link ConstrainedDelaunayTriangulator}): the handoff carries only
 * corners, since the consumer derives adjacency and attributes itself. Hence
 * immutable, and deliberately distinct from {@link FlatTriangleList}, the
 * arena that additionally maintains neighbour links and a region attribute.
 */
final class Corners {

    final int a, b, c;

    Corners(int a, int b, int c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    /** Corner vertex index at position {@code i} (0, 1, 2), for the (i+1)%3 edge walks. */
    int corner(int i) {
        return i == 0 ? a : i == 1 ? b : c;
    }
}
