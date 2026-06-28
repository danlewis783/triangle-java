package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;

final class TriangleMesherOutput2 {

    final Points points;
    final Triangles triangles;
    final Constraints segments;

    public TriangleMesherOutput2(Points points, Triangles triangles, Constraints segments) {
        this.points = points;
        this.triangles = triangles;
        this.segments = segments;
    }

    /** Repack a flat {@link TriangleMesherOutput} into the modelled form. */
    static TriangleMesherOutput2 from(TriangleMesherOutput o) {
        Points points = new Points(o.pointList, o.numberOfPoints);

        int t = o.numberOfTriangles;
        int[] triData = new int[6 * t];                /* a,b,c then n0,n1,n2 per triangle */
        for (int i = 0; i < t; i++) {
            triData[6 * i] = o.triangleList[3 * i];
            triData[6 * i + 1] = o.triangleList[3 * i + 1];
            triData[6 * i + 2] = o.triangleList[3 * i + 2];
            triData[6 * i + 3] = o.neighborList[3 * i];
            triData[6 * i + 4] = o.neighborList[3 * i + 1];
            triData[6 * i + 5] = o.neighborList[3 * i + 2];
        }
        Triangles triangles = new Triangles(triData, o.triangleAttributeList, t);

        int[] segList = o.segmentList, segMarkers = o.segmentMarkerList;
        int s = segList == null ? 0 : o.numberOfSegments;
        int[] segData = new int[3 * s];
        if (segList != null) {
            for (int i = 0; i < s; i++) {
                segData[3 * i] = segList[2 * i];
                segData[3 * i + 1] = segList[2 * i + 1];
                segData[3 * i + 2] = segMarkers != null ? segMarkers[i] : 0;
            }
        }
        Constraints segments = new Constraints(segData, s);

        return new TriangleMesherOutput2(points, triangles, segments);
    }
}
