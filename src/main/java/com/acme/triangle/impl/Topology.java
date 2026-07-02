package com.acme.triangle.impl;

import com.acme.triangle.Triangle;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Shared mesh-topology helpers: the undirected edge key that matches two
 * triangles across a shared edge, and the adjacency build that pairs them. Both
 * were near-identical across the Delaunay, constrained-Delaunay, and refinement
 * stages (each over its own triangle representation); this is the single
 * implementation they all route through.
 */
final class Topology {

    private Topology() {
    }

    /**
     * An undirected edge identity: the two endpoint indices packed low-high into
     * a long, so {@code (a, b)} and {@code (b, a)} collide. Used as a map/set key
     * to find the at-most-two triangles on an edge without allocating an edge
     * object per lookup.
     */
    static long edgeKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    /**
     * Reads corner {@code c} (0, 1, 2) of triangle {@code t}. The indirection is
     * what lets {@link #neighbors} build adjacency over a flat {@code int[]}, a
     * {@link Triangle} list, or a {@link Corners} list alike.
     */
    @FunctionalInterface
    interface Corner {
        int of(int t, int c);
    }

    /**
     * Triangle adjacency: {@code neigh[3*i+j]} is the triangle sharing the edge
     * opposite corner {@code j} of triangle {@code i}, or {@code -1} on a
     * boundary. Corners are read CCW through {@code corner}, so the edge opposite
     * corner {@code j} is {@code (corner(i,(j+1)%3), corner(i,(j+2)%3))}; the
     * first triangle to register an edge is paired with the second to claim it.
     * Triangles must be live - callers compact out any dead slots first.
     */
    static int[] neighbors(int count, Corner corner) {
        int[] neigh = new int[3 * count];
        Arrays.fill(neigh, -1);
        /* Edge -> the flat neigh slot (3*tri+corner) that first claimed it; the
           primitive map keeps this build allocation-free per edge. */
        Long2IntOpenHashMap seen =
                new Long2IntOpenHashMap(3 * count / 2 + 1, Hash.FAST_LOAD_FACTOR);
        seen.defaultReturnValue(-1);
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < 3; j++) {
                long k = edgeKey(corner.of(i, (j + 1) % 3), corner.of(i, (j + 2) % 3));
                int prev = seen.get(k);
                if (prev < 0) {
                    seen.put(k, 3 * i + j);
                } else {
                    neigh[3 * i + j] = prev / 3;
                    neigh[prev] = i;
                }
            }
        }
        return neigh;
    }
}
