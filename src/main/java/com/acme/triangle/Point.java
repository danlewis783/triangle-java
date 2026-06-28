package com.acme.triangle;

/**
 * An immutable coordinate pair: a single location - a vertex's position, or a
 * transient point such as a circumcentre or off-centre candidate that is not
 * (yet) a vertex. Bulk vertex coordinates live in {@link Points}; this is the
 * value type for an individual point.
 */
public final class Point {

    private final double x;
    private final double y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }
}
