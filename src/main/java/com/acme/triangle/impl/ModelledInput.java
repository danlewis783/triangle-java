package com.acme.triangle.impl;

import com.acme.triangle.Constraint;
import com.acme.triangle.Point;
import com.acme.triangle.PointUtils;
import com.acme.triangle.Region;
import com.acme.triangle.TriangleMesherInput;
import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * The meshing pipeline's modelled input form: the public
 * {@link TriangleMesherInput}'s flat parallel arrays repacked into the richer
 * types - a {@code List<Point>} of vertices, {@link Constraint} segments,
 * {@link Point} holes, and {@link Region}s. Built once via {@link #from} at the
 * {@code TriangleMesher} boundary; the Java pipeline then operates on this
 * instead of the flat DTO. Empty lists stand in for "none", so nothing here is
 * nullable.
 */
final class ModelledInput {

    private final ImmutableList<Point> points;
    private final ImmutableList<Constraint> segments;
    private final ImmutableList<Point> holes;
    private final ImmutableList<Region> regions;
    private final double minAngleDegrees;
    private final boolean quiet;

    public ModelledInput(List<Point> points, List<Constraint> segments, List<Point> holes,
                                List<Region> regions, double minAngleDegrees, boolean quiet) {
        this.points = ImmutableList.copyOf(points);
        this.segments = ImmutableList.copyOf(segments);
        this.holes = ImmutableList.copyOf(holes);
        this.regions = ImmutableList.copyOf(regions);
        this.minAngleDegrees = minAngleDegrees;
        this.quiet = quiet;
    }

    public List<Point> getPoints() {
        return points;
    }

    public List<Constraint> getSegments() {
        return segments;
    }

    public List<Point> getHoles() {
        return holes;
    }

    public List<Region> getRegions() {
        return regions;
    }

    public double getMinAngleDegrees() {
        return minAngleDegrees;
    }

    public boolean isQuiet() {
        return quiet;
    }

    /** Repack a public {@link TriangleMesherInput} into the modelled form. */
    public static ModelledInput from(TriangleMesherInput in) {
        List<Point> pointsList = PointUtils.toImmutableList(in.numberOfPoints, in.pointList);

        ImmutableList.Builder<Constraint> segments = ImmutableList.builder();
        int[] segList = in.segmentList;
        int[] segMarkers = in.segmentMarkerList;
        if (segList != null) {
            for (int i = 0; i < in.numberOfSegments; i++) {
                int marker = segMarkers != null ? segMarkers[i] : 0;
                segments.add(new Constraint(segList[2 * i], segList[2 * i + 1], marker));
            }
        }

        ImmutableList.Builder<Point> holes = ImmutableList.builder();
        double[] holeList = in.holeList;
        if (holeList != null) {
            for (int i = 0; i < in.numberOfHoles; i++) {
                holes.add(new Point(holeList[2 * i], holeList[2 * i + 1]));
            }
        }

        ImmutableList.Builder<Region> regions = ImmutableList.builder();
        double[] regionList = in.regionList;
        if (regionList != null) {
            for (int i = 0; i < in.numberOfRegions; i++) {
                regions.add(new Region(new Point(regionList[4 * i], regionList[4 * i + 1]),
                        regionList[4 * i + 2], regionList[4 * i + 3]));
            }
        }

        return new ModelledInput(pointsList, segments.build(), holes.build(), regions.build(), in.minAngleDegrees, in.quiet);
    }

}
