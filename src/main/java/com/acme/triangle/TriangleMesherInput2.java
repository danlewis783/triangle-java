package com.acme.triangle;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Input to {@link TriangleMesher} in the impl package's modelled form: the
 * public {@link TriangleMesherInput}'s flat parallel arrays repacked into the
 * richer types - a {@code List<Point>} of vertices, {@link Constraint} segments,
 * {@link Point} holes, and {@link Region}s. Built once via {@link #from} at the
 * boundary; the Java meshing pipeline then operates on this instead of the flat
 * DTO. Empty lists stand in for "none", so nothing here is nullable.
 */
public final class TriangleMesherInput2 {

    private final ImmutableList<Point> points;
    private final ImmutableList<Constraint> segments;
    private final ImmutableList<Point> holes;
    private final ImmutableList<Region> regions;
    private final double minAngleDegrees;
    private final boolean quiet;

    public TriangleMesherInput2(List<Point> points, List<Constraint> segments, List<Point> holes,
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
    public static TriangleMesherInput2 from(TriangleMesherInput in) {
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

        return new TriangleMesherInput2(pointsList, segments.build(), holes.build(), regions.build(), in.minAngleDegrees, in.quiet);
    }

    /**
     * Marshal back to the flat public {@link TriangleMesherInput} - the inverse of
     * {@link #from}. Lets a flat-native mesher (e.g. the JNA adapter) accept the
     * modelled input by converting at its boundary. Empty lists marshal to the
     * flat "none" convention: a zero count with a {@code null} array.
     */
    public TriangleMesherInput toFlat() {
        TriangleMesherInput in = new TriangleMesherInput();
        in.numberOfPoints = points.size();
        in.pointList = PointUtils.flatten(points);

        int s = segments.size();
        in.numberOfSegments = s;
        if (s > 0) {
            int[] segList = new int[2 * s];
            int[] segMarkers = new int[s];
            for (int i = 0; i < s; i++) {
                Constraint c = segments.get(i);
                segList[2 * i] = c.getA();
                segList[2 * i + 1] = c.getB();
                segMarkers[i] = c.getMarker();
            }
            in.segmentList = segList;
            in.segmentMarkerList = segMarkers;
        }

        int h = holes.size();
        in.numberOfHoles = h;
        if (h > 0) {
            double[] holeList = new double[2 * h];
            for (int i = 0; i < h; i++) {
                Point p = holes.get(i);
                holeList[2 * i] = p.getX();
                holeList[2 * i + 1] = p.getY();
            }
            in.holeList = holeList;
        }

        int r = regions.size();
        in.numberOfRegions = r;
        if (r > 0) {
            double[] regionList = new double[4 * r];
            for (int i = 0; i < r; i++) {
                Region region = regions.get(i);
                Point site = region.getSite();
                regionList[4 * i] = site.getX();
                regionList[4 * i + 1] = site.getY();
                regionList[4 * i + 2] = region.getAttribute();
                regionList[4 * i + 3] = region.getMaxArea();
            }
            in.regionList = regionList;
        }

        in.minAngleDegrees = minAngleDegrees;
        in.quiet = quiet;
        return in;
    }
}
