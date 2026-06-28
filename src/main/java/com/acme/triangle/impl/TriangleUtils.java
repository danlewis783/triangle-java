package com.acme.triangle.impl;

import com.acme.triangle.DefaultImmutableTriangle;
import com.acme.triangle.ImmutableTriangle;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Packs a slot-indexed triangle list - some slots dead ({@code null}) - into a
 * compacted {@link ImmutableTriangle} list: drops the dead slots and remaps every
 * neighbour id from slot index to the contiguous output index. The shared tail of
 * both the constrained-Delaunay build ({@link ConstrainedDelaunayTriangulator})
 * and the refinement output ({@link IncrementalCdt}).
 * <p>
 * A dead slot's remap entry stays -1, so a neighbour that points at one - a
 * carved-away slot in the build, never a live triangle's neighbour in refinement -
 * collapses to a boundary (-1) for free, which is why both callers can share this.
 */
final class TriangleUtils {

    private TriangleUtils() {
    }

    /**
     * @param slots one cell per slot, {@code null} for a dead/removed slot, each
     *              triangle carrying neighbour ids in <em>slot</em> indexing
     * @return the live triangles in slot order, neighbour ids remapped to the
     *         compacted output indexing (dead neighbours become a boundary, -1)
     */
    static List<ImmutableTriangle> compact(List<? extends @Nullable ImmutableTriangle> slots) {
        int[] remap = new int[slots.size()];
        Arrays.fill(remap, -1);
        int n = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) != null) {
                remap[i] = n++;
            }
        }
        List<ImmutableTriangle> packed = new ArrayList<>(n);
        for (ImmutableTriangle t : slots) {
            if (t == null) {
                continue;
            }
            packed.add(new DefaultImmutableTriangle(t.getA(), t.getB(), t.getC(),
                    mapNbr(t.getN0(), remap), mapNbr(t.getN1(), remap), mapNbr(t.getN2(), remap),
                    t.getAttr()));
        }
        return packed;
    }

    /**
     * A neighbour slot id mapped to its compacted output index, or -1 for a
     * boundary ({@code nb < 0}) or a neighbour pointing at a dead slot (whose
     * remap entry stayed -1).
     */
    private static int mapNbr(int nb, int[] remap) {
        return nb < 0 ? -1 : remap[nb];
    }
}
