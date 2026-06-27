package com.acme.triangle.impl;

import java.util.Arrays;

/**
 * A structure-of-arrays store of {@link Segment}s, the packed counterpart to
 * {@link Points}: five ints per segment ({@code a, b, marker, origOrg,
 * origDest}) in one flat array, with by-index accessors and {@link #at} to
 * materialize a single {@code Segment}. Append-only via {@link #add}.
 */
final class Segments {

    private int[] flat; // a, b, marker, origOrg, origDest per segment
    private int size;

    Segments(int[] flat, int n) {
        this.flat = Arrays.copyOf(flat, 5 * Math.max(16, n));
        this.size = n;
    }

    int size() {
        return size;
    }

    int a(int i) {
        return flat[5 * i];
    }

    int b(int i) {
        return flat[5 * i + 1];
    }

    int marker(int i) {
        return flat[5 * i + 2];
    }

    int origOrg(int i) {
        return flat[5 * i + 3];
    }

    int origDest(int i) {
        return flat[5 * i + 4];
    }

    Segment at(int i) {
        return new Segment(flat[5 * i], flat[5 * i + 1], flat[5 * i + 2],
                flat[5 * i + 3], flat[5 * i + 4]);
    }

    int add(int a, int b, int marker, int origOrg, int origDest) {
        if (5 * (size + 1) > flat.length) {
            flat = Arrays.copyOf(flat, flat.length * 2);
        }
        int i = size++;
        flat[5 * i] = a;
        flat[5 * i + 1] = b;
        flat[5 * i + 2] = marker;
        flat[5 * i + 3] = origOrg;
        flat[5 * i + 4] = origDest;
        return i;
    }

    int add(Segment s) {
        return add(s.a, s.b, s.marker, s.origOrg, s.origDest);
    }
}
