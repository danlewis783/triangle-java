package com.acme.triangle.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;

/**
 * Flat triangle arena - the {@link FlatPointList} counterpart for triangles.
 * Each slot holds three CCW corner vertex indices, the three neighbour slot ids
 * across the opposite edges (or -1 on a boundary), and the region attribute, in
 * parallel flat arrays. Corner {@code j}'s opposite edge is
 * {@code (corner(j+1), corner(j+2))} and {@code neighbor(j)} is the triangle
 * across it - the indexing the cavity surgery walks. Dead slots are queued here
 * and reused (LIFO), so allocation and liveness discipline live in one place;
 * scanning consumers skip slots where {@link #isLive} is false.
 * <p>
 * As immutable as mesh surgery allows: a slot's corners and attribute are fixed
 * at {@link #alloc} (a flip or re-fan allocates new slots rather than editing
 * corners), neighbour links are the one mutable facet (relinked locally as fans
 * replace cavities), and the backing arrays are never handed out.
 */
final class FlatTriangleList {

    private static final int DEAD = -1;               /* corners[3t] of a dead slot */

    private int[] corners;                            /* 3 per slot */
    private int[] neighbors;                          /* 3 per slot, -1 on a boundary */
    private double[] attrs;                           /* 1 per slot */
    private int slots;                                /* arena size, dead included */
    private int live;
    private final IntArrayList freeSlots = new IntArrayList();

    FlatTriangleList(int expectedTriangles) {
        int cap = Math.max(4, expectedTriangles);
        corners = new int[3 * cap];
        neighbors = new int[3 * cap];
        attrs = new double[cap];
    }

    /** Arena size in slots, dead ones included; live slots satisfy {@link #isLive}. */
    int slotCount() {
        return slots;
    }

    int liveCount() {
        return live;
    }

    boolean isLive(int t) {
        return corners[3 * t] != DEAD;
    }

    /** Claim a slot (reusing a dead one, LIFO) for the CCW triangle
        {@code (a, b, c)} with neighbours {@code (n0, n1, n2)}; returns its id. */
    int alloc(int a, int b, int c, int n0, int n1, int n2, double attr) {
        int t;
        if (!freeSlots.isEmpty()) {
            t = freeSlots.popInt();
        } else {
            t = slots++;
            if (attrs.length < slots) {
                int cap = attrs.length * 2;
                corners = Arrays.copyOf(corners, 3 * cap);
                neighbors = Arrays.copyOf(neighbors, 3 * cap);
                attrs = Arrays.copyOf(attrs, cap);
            }
        }
        corners[3 * t] = a;
        corners[3 * t + 1] = b;
        corners[3 * t + 2] = c;
        neighbors[3 * t] = n0;
        neighbors[3 * t + 1] = n1;
        neighbors[3 * t + 2] = n2;
        attrs[t] = attr;
        live++;
        return t;
    }

    /** Mark slot {@code t} dead and queue it for reuse. */
    void free(int t) {
        corners[3 * t] = DEAD;
        freeSlots.push(t);
        live--;
    }

    /** Corner vertex index at position {@code j} (0, 1, 2), for the (j+1)%3 walks. */
    int corner(int t, int j) {
        return corners[3 * t + j];
    }

    int a(int t) {
        return corners[3 * t];
    }

    int b(int t) {
        return corners[3 * t + 1];
    }

    int c(int t) {
        return corners[3 * t + 2];
    }

    /** Neighbour slot id across the edge opposite corner {@code j}, or -1. */
    int neighbor(int t, int j) {
        return neighbors[3 * t + j];
    }

    void setNeighbor(int t, int j, int nb) {
        neighbors[3 * t + j] = nb;
    }

    /** Region attribute (meaningful only when the mesh carries attributes). */
    double attr(int t) {
        return attrs[t];
    }

    /** Whether {@code t} has vertex {@code v} as one of its corners. */
    boolean hasCorner(int t, int v) {
        return corners[3 * t] == v || corners[3 * t + 1] == v || corners[3 * t + 2] == v;
    }
}
