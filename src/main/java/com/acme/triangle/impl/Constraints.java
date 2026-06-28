package com.acme.triangle.impl;

import java.util.Arrays;

/**
 * A structure-of-arrays store of output segments, the packed counterpart to
 * {@link Constraint}: three ints per segment ({@code a, b, marker}) in one flat
 * array, with by-index accessors and {@link #at} to materialize a single
 * {@link Constraint}. Append-only via {@link #add}.
 */
final class Constraints {

    private int[] flat; // a, b, marker per segment
    private int size;

    Constraints(int[] flat, int n) {
        this.flat = Arrays.copyOf(flat, 3 * Math.max(16, n));
        this.size = n;
    }

    int size() {
        return size;
    }

    int a(int i) {
        return flat[3 * i];
    }

    int b(int i) {
        return flat[3 * i + 1];
    }

    int marker(int i) {
        return flat[3 * i + 2];
    }

    Constraint at(int i) {
        return new Constraint(flat[3 * i], flat[3 * i + 1], flat[3 * i + 2]);
    }

    int add(int a, int b, int marker) {
        if (3 * (size + 1) > flat.length) {
            flat = Arrays.copyOf(flat, flat.length * 2);
        }
        int i = size++;
        flat[3 * i] = a;
        flat[3 * i + 1] = b;
        flat[3 * i + 2] = marker;
        return i;
    }

    int add(Constraint c) {
        return add(c.a, c.b, c.marker);
    }
}
