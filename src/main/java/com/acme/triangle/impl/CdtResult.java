package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * The constrained-Delaunay phase's product, in the pipeline's flat internal
 * forms: the vertex store, the compacted live triangle arena (neighbour ids in
 * slot indexing), the recovered subsegments as interleaved {@code (a, b)}
 * pairs with a marker per segment, and whether the triangles carry region
 * attributes. Single-use handoff: the refinement mesh <em>adopts</em> these
 * stores directly (no boundary copy), and the no-refinement path emits them
 * via {@link #toOutput}.
 */
final class CdtResult {

    final FlatPointList points;
    final FlatTriangleList triangles;
    final IntArrayList segments;              /* interleaved (a, b) per segment */
    final IntArrayList segmentMarkers;        /* one marker per segment */
    final boolean hasAttributes;

    CdtResult(FlatPointList points, FlatTriangleList triangles,
              IntArrayList segments, IntArrayList segmentMarkers, boolean hasAttributes) {
        this.points = points;
        this.triangles = triangles;
        this.segments = segments;
        this.segmentMarkers = segmentMarkers;
        this.hasAttributes = hasAttributes;
    }

    /** Marshal to the flat public output form (the no-refinement path). */
    TriangleMesherOutput toOutput() {
        TriangleMesherOutput out = new TriangleMesherOutput();
        out.numberOfPoints = points.size();
        out.pointList = points.toArray();
        TriangleUtils.writeTriangles(triangles, hasAttributes, out);
        out.numberOfSegments = segmentMarkers.size();
        out.segmentList = segments.toIntArray();
        out.segmentMarkerList = segmentMarkers.toIntArray();
        return out;
    }
}
