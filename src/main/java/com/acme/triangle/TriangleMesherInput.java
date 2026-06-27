package com.acme.triangle;

import org.jspecify.annotations.Nullable;

/**
 * Input to {@link TriangleMesher}: a planar straight-line graph plus options.
 * <p>
 * Plain-data fields (interleaved primitive arrays) so this type is both the
 * natural Java model and a clean marshalling boundary for the native adapter.
 * Coordinate arrays are interleaved {@code x0,y0,x1,y1,...}; index arrays are
 * zero-based into the point list.
 */
@SuppressWarnings("NullAway.Init")   // mutable bag: the caller populates the fields
public final class TriangleMesherInput {

    /** Point coordinates, interleaved: {@code x0,y0,x1,y1,...} (length 2*N). */
    public double[] pointList;

    /** Segment endpoints, interleaved point indices; null if none. */
    public int @Nullable [] segmentList;

    /** One marker per segment (opaque integer tag); null if none. */
    public int @Nullable [] segmentMarkerList;

    /** One point per hole, interleaved {@code x0,y0,...}; null if none. */
    public double @Nullable [] holeList;

    /** Per region: {@code x, y, attribute, maxArea} repeating (length 4*R); null if none. */
    public double @Nullable [] regionList;

    public int numberOfPoints;
    public int numberOfSegments;
    public int numberOfHoles;
    public int numberOfRegions;

    /** Minimum interior angle (degrees) for quality meshing; {@code <= 0} = off. */
    public double minAngleDegrees;

    /** Suppress diagnostic output. */
    public boolean quiet;
}
