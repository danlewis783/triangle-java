package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import java.util.ArrayList;
import java.util.List;

/**
 * Input to {@link TriangleMesher} in the impl package's modelled form: the
 * public {@link TriangleMesherInput}'s flat parallel arrays repacked into the
 * richer types - a {@link Points} store, {@link Constraint} segments, {@link
 * Point} holes, and {@link Region}s. Built once via {@link #from} at the
 * boundary; the Java meshing pipeline then operates on this instead of the flat
 * DTO. Empty lists stand in for "none", so nothing here is nullable.
 */
final class TriangleMesherInput2 {

    final Points points;
    final List<Constraint> segments;
    final List<Point> holes;
    final List<Region> regions;
    final double minAngleDegrees;
    final boolean quiet;

    TriangleMesherInput2(Points points, List<Constraint> segments, List<Point> holes,
                         List<Region> regions, double minAngleDegrees, boolean quiet) {
        this.points = points;
        this.segments = segments;
        this.holes = holes;
        this.regions = regions;
        this.minAngleDegrees = minAngleDegrees;
        this.quiet = quiet;
    }

    /** Repack a public {@link TriangleMesherInput} into the modelled form. */
    static TriangleMesherInput2 from(TriangleMesherInput in) {
        Points points = new Points(in.pointList, in.numberOfPoints);

        List<Constraint> segments = new ArrayList<>();
        int[] segList = in.segmentList, segMarkers = in.segmentMarkerList;
        if (segList != null) {
            for (int i = 0; i < in.numberOfSegments; i++) {
                int marker = segMarkers != null ? segMarkers[i] : 0;
                segments.add(new Constraint(segList[2 * i], segList[2 * i + 1], marker));
            }
        }

        List<Point> holes = new ArrayList<>();
        double[] holeList = in.holeList;
        if (holeList != null) {
            for (int i = 0; i < in.numberOfHoles; i++) {
                holes.add(new Point(holeList[2 * i], holeList[2 * i + 1]));
            }
        }

        List<Region> regions = new ArrayList<>();
        double[] regionList = in.regionList;
        if (regionList != null) {
            for (int i = 0; i < in.numberOfRegions; i++) {
                regions.add(new Region(new Point(regionList[4 * i], regionList[4 * i + 1]),
                        regionList[4 * i + 2], regionList[4 * i + 3]));
            }
        }

        return new TriangleMesherInput2(points, segments, holes, regions,
                in.minAngleDegrees, in.quiet);
    }
}
