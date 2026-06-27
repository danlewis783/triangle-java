package com.acme.triangle;

import org.jspecify.annotations.Nullable;

/**
 * Output of {@link TriangleMesher}: the generated mesh.
 * <p>
 * Triangles are linear (three corners each). Neighbour slots follow
 * Triangle's convention: {@code neighborList[3*i + j]} is the triangle across
 * the edge OPPOSITE corner {@code j} of triangle {@code i}, or {@code -1} on a
 * boundary. This per-triangle slot alignment is part of the contract; the
 * global ordering of triangles is not.
 */
@SuppressWarnings("NullAway.Init")   // mutable bag: the producer populates the fields
public final class TriangleMesherOutput {

    /** Point coordinates, interleaved {@code x0,y0,x1,y1,...} (length 2*N). */
    public double[] pointList;

    /** Triangle corners, three zero-based point indices each (length 3*T). */
    public int[] triangleList;

    /** One region id per triangle; null when the mesh has no regions. */
    public double @Nullable [] triangleAttributeList;

    /** Three neighbour triangle indices per triangle, {@code -1} for none. */
    public int[] neighborList;

    /** Recovered subsegment endpoints, interleaved point indices; null if absent. */
    public int @Nullable [] segmentList;

    /** One marker per output segment; null if absent. */
    public int @Nullable [] segmentMarkerList;

    public int numberOfPoints;
    public int numberOfTriangles;
    public int numberOfSegments;
}
