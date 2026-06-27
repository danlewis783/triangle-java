package com.acme.triangle.impl;

import java.util.Arrays;

/**
 * A growable structure-of-arrays point store: vertex {@code i} is at
 * {@code (x(i), y(i))}, held in one interleaved {@code double[]}. This matches
 * the flat layout the input ({@code pointList}) and output already use - so the
 * mesh adopts and emits coordinates in bulk - and keeps the {@code 2*i} index
 * arithmetic in this one class rather than smeared across the kernel.
 * <p>
 * The hot path reads {@link #x}/{@link #y} (a plain array load, no allocation);
 * {@link #at} materializes a {@link Point} only where an individual value is
 * wanted, at the warm edges. Append-only: a vertex never moves once placed.
 */
final class Points {

    private double[] xy;
    private int size;

    /** Adopt the first {@code n} vertices from a flat interleaved array, with
        room to grow. */
    Points(double[] flat, int n) {
        xy = Arrays.copyOf(flat, 2 * Math.max(16, n));
        size = n;
    }

    int size() {
        return size;
    }

    double x(int i) {
        return xy[2 * i];
    }

    double y(int i) {
        return xy[2 * i + 1];
    }

    /** The vertex at {@code i} as a value (allocates - for warm/edge use). */
    Point at(int i) {
        return new Point(xy[2 * i], xy[2 * i + 1]);
    }

    /** Append a vertex, returning its index. */
    int add(double px, double py) {
        if (2 * (size + 1) > xy.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);   /* geometric growth, as ensureCavityGen */
        }
        int i = size++;
        xy[2 * i] = px;
        xy[2 * i + 1] = py;
        return i;
    }

    int add(Point p) {
        return add(p.x, p.y);
    }

    /** A tight flat copy of the live coordinates, for the output mesh. */
    double[] toArray() {
        return Arrays.copyOf(xy, 2 * size);
    }
}
