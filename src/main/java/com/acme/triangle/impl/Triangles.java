package com.acme.triangle.impl;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A structure-of-arrays store of {@link Triangle}s, the packed counterpart to
 * {@link Points}: six ints per triangle ({@code a, b, c, n0, n1, n2}) in one
 * flat array, with by-index accessors and {@link #at} to materialize a single
 * {@code Triangle}. Append-only via {@link #add}.
 * <p>
 * Region attributes are carried in a parallel one-per-triangle array that is
 * {@code null} when the mesh has no regions - mirroring the public output's
 * empty {@code triangleAttributeList}, so {@link #hasAttributes()} distinguishes
 * "no attributes" from "all attributes are 0.0".
 */
final class Triangles {

    private int[] data;                // a, b, c, n0, n1, n2 per triangle
    private double @Nullable [] attr;  // region attribute per triangle, or null when none
    private int size;

    Triangles(int[] flatData, double @Nullable [] flatAttr, int n) {
        data = Arrays.copyOf(flatData, 6 * Math.max(16, n));
        attr = flatAttr == null ? null : Arrays.copyOf(flatAttr, Math.max(16, n));
        size = n;
    }

    int size() {
        return size;
    }

    /** Whether triangles carry a region attribute; if not, {@link #at}'s attribute is 0. */
    boolean hasAttributes() {
        return attr != null;
    }

    int a(int i) {
        return data[6 * i];
    }

    int b(int i) {
        return data[6 * i + 1];
    }

    int c(int i) {
        return data[6 * i + 2];
    }

    int n0(int i) {
        return data[6 * i + 3];
    }

    int n1(int i) {
        return data[6 * i + 4];
    }

    int n2(int i) {
        return data[6 * i + 5];
    }

    Triangle at(int i) {
        return new Triangle(data[6 * i], data[6 * i + 1], data[6 * i + 2],
                data[6 * i + 3], data[6 * i + 4], data[6 * i + 5],
                attr == null ? 0.0 : attr[i]);
    }

    int add(int a, int b, int c, int n0, int n1, int n2, double attr) {
        if (6 * (size + 1) > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
            if (this.attr != null) {
                this.attr = Arrays.copyOf(this.attr, this.attr.length * 2);
            }
        }
        int i = size++;
        data[6 * i] = a;
        data[6 * i + 1] = b;
        data[6 * i + 2] = c;
        data[6 * i + 3] = n0;
        data[6 * i + 4] = n1;
        data[6 * i + 5] = n2;
        if (this.attr != null) {
            this.attr[i] = attr;
        }
        return i;
    }

    int add(Triangle triangle) {
        return add(triangle.a, triangle.b, triangle.c,
                triangle.n0, triangle.n1, triangle.n2, triangle.attr);
    }
}
