package com.acme.triangle.impl;

/**
 * One triangle's side of a shared edge, recorded while adjacency is built: the
 * triangle's slot {@code tri} and the {@code corner} the edge lies opposite -
 * equivalently the edge's slot in that triangle, so the neighbour across it is
 * {@code neighbor[3*tri + corner]}. The first triangle to register an edge key
 * stores its side; the second to claim that key pairs with it.
 */
final class EdgeSide {

    final int tri;
    final int corner;

    EdgeSide(int tri, int corner) {
        this.tri = tri;
        this.corner = corner;
    }
}
