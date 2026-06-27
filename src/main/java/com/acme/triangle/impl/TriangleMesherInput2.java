package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesher;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link TriangleMesher}: a planar straight-line graph plus options.
 * <p>
 * The internal model: point coordinates live in a {@link Points} store, while
 * the remaining graph and option data stay as plain primitive arrays. Index
 * arrays are zero-based into the point list.
 */
@SuppressWarnings("NullAway.Init")   // mutable bag: re-packed from the public input
final class TriangleMesherInput2 {

    /** Input vertices ({@link Points}). */
    Points points;

    //TODO what here?
    /** Segment endpoints, interleaved point indices: {@code p0,p1,p2,p3,...}; null if none. */
    int @Nullable [] segmentList;

    /** One marker per segment (opaque integer tag, round-tripped to output); null if none. */
    int @Nullable [] segmentMarkerList;

    /** One point per hole, interleaved {@code x0,y0,...}; marks a void region; null if none. */
    double @Nullable [] holeList;

    /** Per region: {@code x, y, attribute, maxArea} repeating (length 4*R); null if none. */
    double @Nullable [] regionList;

    int numberOfSegments;
    int numberOfHoles;
    int numberOfRegions;
    //TODO end what here?


    /** Minimum interior angle (degrees) for quality meshing; {@code <= 0} = off. */
    double minAngleDegrees;

    /** Suppress diagnostic output. */
    boolean quiet;
}
