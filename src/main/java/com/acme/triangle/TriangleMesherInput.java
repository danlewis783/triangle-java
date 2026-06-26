package com.acme.triangle;

/**
 * Input to {@link TriangleMesher}: a planar straight-line graph plus options.
 * <p>
 * Plain-data fields (interleaved primitive arrays) so this type is both the
 * natural Java model and a clean marshalling boundary for the native adapter.
 * Coordinate arrays are interleaved {@code x0,y0,x1,y1,...}; index arrays are
 * zero-based into the point list.
 */
public final class TriangleMesherInput {

    /** Point coordinates, interleaved: {@code x0,y0,x1,y1,...} (length 2*N). */
    public double[] pointList;

    /** Segment endpoints, interleaved point indices: {@code p0,p1,p2,p3,...}. */
    public int[] segmentList;

    /** One marker per segment (opaque integer tag, round-tripped to output). */
    public int[] segmentMarkerList;

    /** One point per hole, interleaved {@code x0,y0,...}; marks a void region. */
    public double[] holeList;

    /** Per region: {@code x, y, attribute, maxArea} repeating (length 4*R). */
    public double[] regionList;

    public int numberOfPoints;
    public int numberOfSegments;
    public int numberOfHoles;
    public int numberOfRegions;

    /** Minimum interior angle (degrees) for quality meshing; {@code <= 0} = off. */
    public double minAngleDegrees;

    /** Suppress diagnostic output. */
    public boolean quiet;
}
