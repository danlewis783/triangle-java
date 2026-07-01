package com.acme.triangle.impl;

import java.util.Arrays;

/**
 * Open-addressed hash map from non-negative {@code long} keys to non-negative
 * {@code int} values, for the edge-keyed lookups on the meshing hot paths
 * ({@link Topology#edgeKey}). {@code HashMap<Long, ...>} there boxes a key per
 * probe, and profiling showed those lookups dominating both construction and
 * refinement; this map allocates nothing per operation.
 * <p>
 * Keys must be &ge; 0 (edge keys always are: both packed endpoints are
 * non-negative), which frees -1 as the empty-slot sentinel. Values must be
 * &ge; 0 too, so {@code get}'s -1 doubles as "absent". Linear probing over a
 * power-of-two table; removal backshifts the following cluster, so there are no
 * tombstones to accumulate.
 */
final class LongIntMap {

    private static final long EMPTY = -1L;

    private long[] keys;
    private int[] vals;
    private int size;
    private int mask;

    LongIntMap(int expectedEntries) {
        int cap = Integer.highestOneBit(Math.max(4, expectedEntries * 2) - 1) * 2;
        keys = new long[cap];
        vals = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
    }

    /** The value for {@code key}, or -1 if absent. */
    int get(long key) {
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == key) {
                return vals[i];
            }
            if (k == EMPTY) {
                return -1;
            }
            i = (i + 1) & mask;
        }
    }

    /** Whether {@code key} is present. */
    boolean contains(long key) {
        return get(key) >= 0;
    }

    /** Map {@code key} to {@code value} (&ge; 0), replacing any previous value. */
    void put(long key, int value) {
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == key) {
                vals[i] = value;
                return;
            }
            if (k == EMPTY) {
                keys[i] = key;
                vals[i] = value;
                if (++size * 2 > keys.length) {
                    grow();
                }
                return;
            }
            i = (i + 1) & mask;
        }
    }

    /** Remove {@code key} if present, backshifting the probe cluster after it. */
    void remove(long key) {
        int i = slot(key);
        while (true) {
            long k = keys[i];
            if (k == EMPTY) {
                return;
            }
            if (k == key) {
                break;
            }
            i = (i + 1) & mask;
        }
        size--;
        /* Backshift: re-place each following cluster entry whose natural slot no
           longer reaches it once the hole at i opens. */
        int hole = i;
        int j = (i + 1) & mask;
        while (keys[j] != EMPTY) {
            int natural = slot(keys[j]);
            /* Does j's entry probe past the hole? (circular interval test) */
            boolean reachesHole = ((j - natural) & mask) >= ((j - hole) & mask);
            if (reachesHole) {
                keys[hole] = keys[j];
                vals[hole] = vals[j];
                hole = j;
            }
            j = (j + 1) & mask;
        }
        keys[hole] = EMPTY;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldVals = vals;
        int cap = oldKeys.length * 2;
        keys = new long[cap];
        vals = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != EMPTY) {
                int s = slot(k);
                while (keys[s] != EMPTY) {
                    s = (s + 1) & mask;
                }
                keys[s] = k;
                vals[s] = oldVals[i];
            }
        }
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;      /* Fibonacci mixing */
        return (int) (h >>> 32) & mask;
    }
}
