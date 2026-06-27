package com.acme.triangle.impl;

/**
 * An immutable coordinate pair: a single location - a vertex's position, or a
 * transient point such as a circumcentre or off-centre candidate that is not
 * (yet) a vertex. Bulk vertex coordinates live in {@link Points}; this is the
 * value type for an individual point.
 */
final class Point {

    final double x, y;

    Point(double x, double y) {
        this.x = x;
        this.y = y;
    }
}
