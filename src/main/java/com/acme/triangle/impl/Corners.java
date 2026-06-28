package com.acme.triangle.impl;

import com.acme.triangle.Triangle;

/**
 * The three CCW corner vertex indices of a triangle during the construction
 * phase - the initial Delaunay ({@link DelaunayTriangulator}) and constraint
 * recovery ({@link ConstrainedDelaunayTriangulator}). There, triangles are
 * whole-replaced (a flip or cavity refill drops triangles and adds new ones),
 * never edited in place, and adjacency and attributes are derived on demand
 * rather than carried - so a corner-triple is all a triangle is. Hence
 * immutable, and deliberately distinct from {@link Triangle}, the refinement
 * kernel's mutable arena cell, which additionally maintains neighbour links and
 * a region attribute.
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
