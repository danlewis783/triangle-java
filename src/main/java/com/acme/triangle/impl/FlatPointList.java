package com.acme.triangle.impl;

import java.util.Arrays;

/**
 * Flat, append-only vertex store: coordinates interleaved in a growable
 * {@code double[]} (vertex {@code i} at {@code 2i}/{@code 2i+1}) - the
 * representation every hot geometric test wants, one array read instead of an
 * object dereference plus two field loads per corner. This is the mesher's
 * internal vertex form; conversion to and from the public DTO's interleaved
 * arrays happens only at phase boundaries ({@link #copyOf} in,
 * {@link #toArray} out).
 * <p>
 * As immutable as meshing allows: a coordinate is never modified once added
 * (refinement only <em>appends</em> Steiner vertices), and the backing array is
 * never handed out.
 */
final class FlatPointList {

    private double[] xy;
    private int size;

    FlatPointList(int expectedPoints) {
        xy = new double[Math.max(8, 2 * expectedPoints)];
    }

    /** A flat copy of the first {@code n} points of the interleaved
        {@code x0,y0,x1,y1,...} array (the boundary conversion inward). */
    static FlatPointList copyOf(double[] interleaved, int n) {
        FlatPointList flat = new FlatPointList(n);
        System.arraycopy(interleaved, 0, flat.xy, 0, 2 * n);
        flat.size = n;
        return flat;
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

    /** Append a vertex; returns its index. */
    int add(double x, double y) {
        if (2 * size == xy.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);
        }
        xy[2 * size] = x;
        xy[2 * size + 1] = y;
        return size++;
    }

    /** The squared distance between vertices {@code a} and {@code b}. */
    double dist2(int a, int b) {
        double dx = x(a) - x(b);
        double dy = y(a) - y(b);
        return dx * dx + dy * dy;
    }

    /** The store as a tight interleaved {@code x0,y0,x1,y1,...} array (a fresh
        copy; the boundary conversion outward). */
    double[] toArray() {
        return Arrays.copyOf(xy, 2 * size);
    }
}
