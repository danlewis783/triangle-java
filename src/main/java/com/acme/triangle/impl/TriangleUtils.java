package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;
import java.util.Arrays;

/**
 * Emits a triangle arena into the flat public output form: drops dead slots and
 * remaps every neighbour id from slot indexing to the compacted output
 * indexing. The shared output tail of both the constrained-Delaunay build
 * ({@link CdtResult#toOutput}) and the refinement mesh
 * ({@code IncrementalCdt.toOutput}).
 * <p>
 * A dead slot's remap entry stays -1, so a neighbour that points at one - a
 * carved-away slot in the build, never a live triangle's neighbour in
 * refinement - collapses to a boundary (-1) for free, which is why both callers
 * can share this.
 */
final class TriangleUtils {

    private TriangleUtils() {
    }

    /**
     * Fill {@code out}'s triangle fields ({@code numberOfTriangles},
     * {@code triangleList}, {@code neighborList}, and - when {@code hasAttr} -
     * {@code triangleAttributeList}) with {@code tris}'s live slots in slot
     * order, neighbour ids remapped to the compacted indexing.
     */
    static void writeTriangles(FlatTriangleList tris, boolean hasAttr,
                               TriangleMesherOutput out) {
        int[] remap = new int[tris.slotCount()];
        Arrays.fill(remap, -1);
        int n = 0;
        for (int i = 0; i < tris.slotCount(); i++) {
            if (tris.isLive(i)) {
                remap[i] = n++;
            }
        }
        int[] corners = new int[3 * n];
        int[] neighbors = new int[3 * n];
        double[] attrs = hasAttr ? new double[n] : null;
        int k = 0;
        for (int i = 0; i < tris.slotCount(); i++) {
            if (!tris.isLive(i)) {
                continue;
            }
            corners[3 * k] = tris.a(i);
            corners[3 * k + 1] = tris.b(i);
            corners[3 * k + 2] = tris.c(i);
            for (int j = 0; j < 3; j++) {
                int nb = tris.neighbor(i, j);
                neighbors[3 * k + j] = nb < 0 ? -1 : remap[nb];
            }
            if (attrs != null) {
                attrs[k] = tris.attr(i);
            }
            k++;
        }
        out.numberOfTriangles = n;
        out.triangleList = corners;
        out.neighborList = neighbors;
        out.triangleAttributeList = attrs;
    }
}
