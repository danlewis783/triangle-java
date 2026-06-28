package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherOutput;
import java.util.List;

/**
 * Output of the Java meshing pipeline in the impl package's modelled form: a
 * {@link Points} store, the {@link Triangles} (corners, neighbour links and
 * optional region attributes), and the recovered {@link Constraint} subsegments.
 * The points and triangles get structure-of-arrays stores because they are the
 * large, hot collections; the subsegments are few and cold, so they stay a plain
 * list - the same shape {@link TriangleMesherInput2} carries them in. The
 * pipeline ({@link ConstrainedDelaunayTriangulator} and {@link IncrementalCdt})
 * produces and threads this through directly; it is marshalled back to the flat
 * public {@link TriangleMesherOutput} via {@link #toFlat} only at the {@link
 * com.acme.triangle.TriangleMesher} boundary.
 */
final class TriangleMesherOutput2 {

    final Points points;
    final Triangles triangles;
    final List<Constraint> segments;

    TriangleMesherOutput2(Points points, Triangles triangles, List<Constraint> segments) {
        this.points = points;
        this.triangles = triangles;
        this.segments = segments;
    }

    /** Marshal back to the flat public {@link TriangleMesherOutput} at the API
        boundary - the inverse of repacking the input. */
    TriangleMesherOutput toFlat() {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = points.size();
        o.pointList = points.toArray();

        int t = triangles.size();
        o.numberOfTriangles = t;
        int[] tri = new int[3 * t];
        int[] neigh = new int[3 * t];
        double[] attr = triangles.hasAttributes() ? new double[t] : null;
        for (int i = 0; i < t; i++) {
            tri[3 * i] = triangles.a(i);
            tri[3 * i + 1] = triangles.b(i);
            tri[3 * i + 2] = triangles.c(i);
            neigh[3 * i] = triangles.n0(i);
            neigh[3 * i + 1] = triangles.n1(i);
            neigh[3 * i + 2] = triangles.n2(i);
            if (attr != null) {
                attr[i] = triangles.attr(i);
            }
        }
        o.triangleList = tri;
        o.neighborList = neigh;
        o.triangleAttributeList = attr;

        int s = segments.size();
        o.numberOfSegments = s;
        int[] segList = new int[2 * s];
        int[] segMarkers = new int[s];
        for (int i = 0; i < s; i++) {
            Constraint c = segments.get(i);
            segList[2 * i] = c.a;
            segList[2 * i + 1] = c.b;
            segMarkers[i] = c.marker;
        }
        o.segmentList = segList;
        o.segmentMarkerList = segMarkers;
        return o;
    }
}
