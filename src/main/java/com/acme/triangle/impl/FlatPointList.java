package com.acme.triangle.impl;

import com.acme.triangle.Point;
import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;

/**
 * Flat, append-only vertex store: coordinates interleaved in a growable
 * {@code double[]} (vertex {@code i} at {@code 2i}/{@code 2i+1}) - the
 * representation every hot geometric test wants, one array read instead of a
 * {@code List} dereference plus two field loads per corner. This is the
 * mesher's internal vertex form; the public API keeps {@code List<Point>}, and
 * conversion happens only at phase boundaries ({@link #copyOf} in,
 * {@link #toPointList} out).
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

    /** A flat copy of {@code points} (the boundary conversion inward). */
    static FlatPointList copyOf(List<Point> points) {
        FlatPointList flat = new FlatPointList(points.size());
        for (Point p : points) {
            flat.add(p.getX(), p.getY());
        }
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

    /** The store in the public {@code List<Point>} form (a fresh immutable copy;
        the boundary conversion outward). */
    ImmutableList<Point> toPointList() {
        ImmutableList.Builder<Point> out = ImmutableList.builderWithExpectedSize(size);
        for (int i = 0; i < size; i++) {
            out.add(new Point(x(i), y(i)));
        }
        return out.build();
    }
}
