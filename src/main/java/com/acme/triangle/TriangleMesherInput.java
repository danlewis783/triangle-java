package com.acme.triangle;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link TriangleMesher}: a planar straight-line graph plus options.
 * <p>
 * Plain-data fields (interleaved primitive arrays) so this type is both the
 * natural Java model and a clean marshalling boundary for the native adapter.
 * Coordinate arrays are interleaved {@code x0,y0,x1,y1,...}; index arrays are
 * zero-based into the point list.
 * <p>
 * Prefer {@link #builder()} over populating the fields by hand: the builder
 * derives every count from what was added, so the count/array mismatches the
 * input contract rejects cannot be constructed.
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

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent construction of a {@link TriangleMesherInput} without hand-packing
     * parallel arrays: {@link #point} returns the new point's index for wiring
     * segments, and {@link #build} derives all counts. Quiet mode defaults to
     * on (a library should not print).
     *
     * <pre>{@code
     * TriangleMesherInput.Builder b = TriangleMesherInput.builder();
     * int a = b.point(0, 0);
     * int c = b.point(1, 0);
     * int d = b.point(1, 1);
     * TriangleMesherInput in = b.segment(a, c, 1).segment(c, d, 1).segment(d, a, 1)
     *         .minAngleDegrees(20)
     *         .build();
     * }</pre>
     */
    public static final class Builder {

        private final DoubleArrayList points = new DoubleArrayList();
        private final IntArrayList segments = new IntArrayList();
        private final IntArrayList segmentMarkers = new IntArrayList();
        private final DoubleArrayList holes = new DoubleArrayList();
        private final DoubleArrayList regions = new DoubleArrayList();
        private double minAngleDegrees;
        private boolean quiet = true;

        private Builder() {
        }

        /** Add a point; returns its index, for wiring segments. */
        public int point(double x, double y) {
            points.add(x);
            points.add(y);
            return points.size() / 2 - 1;
        }

        /** Add an unmarked segment (marker 0) between point indices {@code a}
            and {@code b}. */
        public Builder segment(int a, int b) {
            return segment(a, b, 0);
        }

        /** Add a segment between point indices {@code a} and {@code b} carrying
            {@code marker}. */
        public Builder segment(int a, int b, int marker) {
            segments.add(a);
            segments.add(b);
            segmentMarkers.add(marker);
            return this;
        }

        /** Mark the region containing {@code (x, y)} as a hole to carve away. */
        public Builder hole(double x, double y) {
            holes.add(x);
            holes.add(y);
            return this;
        }

        /** Seed the region containing {@code (x, y)} with {@code attribute} and,
            when {@code maxArea > 0}, a per-region maximum triangle area. */
        public Builder region(double x, double y, double attribute, double maxArea) {
            regions.add(x);
            regions.add(y);
            regions.add(attribute);
            regions.add(maxArea);
            return this;
        }

        /** Minimum interior angle in degrees for quality meshing; {@code <= 0} = off. */
        public Builder minAngleDegrees(double degrees) {
            this.minAngleDegrees = degrees;
            return this;
        }

        /** Diagnostic output suppression; the builder defaults to quiet. */
        public Builder quiet(boolean quiet) {
            this.quiet = quiet;
            return this;
        }

        public TriangleMesherInput build() {
            TriangleMesherInput in = new TriangleMesherInput();
            in.pointList = points.toDoubleArray();
            in.numberOfPoints = points.size() / 2;
            in.numberOfSegments = segmentMarkers.size();
            if (in.numberOfSegments > 0) {
                in.segmentList = segments.toIntArray();
                in.segmentMarkerList = segmentMarkers.toIntArray();
            }
            in.numberOfHoles = holes.size() / 2;
            if (in.numberOfHoles > 0) {
                in.holeList = holes.toDoubleArray();
            }
            in.numberOfRegions = regions.size() / 4;
            if (in.numberOfRegions > 0) {
                in.regionList = regions.toDoubleArray();
            }
            in.minAngleDegrees = minAngleDegrees;
            in.quiet = quiet;
            return in;
        }
    }
}
