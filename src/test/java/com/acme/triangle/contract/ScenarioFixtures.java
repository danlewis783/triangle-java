package com.acme.triangle.contract;

import com.acme.triangle.TriangleMesherInput;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared test scenarios, ported from the C reference's scenarios.c. Each
 * carries the input PSLG plus the meshing options, and points at the vendored
 * golden mesh ({@code /meshes/<name>.txt}) produced from that input. The same
 * inputs will later drive a Java mesher directly.
 */
public final class ScenarioFixtures {

    public static final class Scenario {
        public final String name;
        public final TriangleMesherInput input;
        public final String meshResource;

        Scenario(String name, TriangleMesherInput input) {
            this.name = name;
            this.input = input;
            this.meshResource = "/meshes/" + name + ".txt";
        }
    }

    private ScenarioFixtures() {
    }

    public static List<Scenario> all() {
        List<Scenario> s = new ArrayList<>();
        s.add(new Scenario("pslg_square", square(0.0)));
        s.add(new Scenario("pslg_square_quality", square(30.0)));
        s.add(new Scenario("regions", regions()));
        s.add(new Scenario("hole", hole()));
        s.add(new Scenario("segment_recovery", segmentRecovery()));
        s.add(new Scenario("segment_intersection", segmentIntersection()));
        s.add(new Scenario("concave_lshape", concaveLShape()));
        return s;
    }

    public static Scenario byName(String name) {
        for (Scenario s : all()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no scenario " + name);
    }

    private static TriangleMesherInput square(double minAngle) {
        TriangleMesherInput in = pslg(
                new double[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{0, 1, 1, 2, 2, 3, 3, 0},
                new int[]{11, 12, 13, 14});
        in.minAngleDegrees = minAngle;
        return in;
    }

    private static TriangleMesherInput regions() {
        TriangleMesherInput in = pslg(
                new double[]{0, 0, 1, 0, 2, 0, 2, 1, 1, 1, 0, 1},
                new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0, 1, 4},
                new int[]{1, 1, 1, 1, 1, 1, 2});
        in.regionList = new double[]{0.5, 0.5, 1.0, 0.05, 1.5, 0.5, 2.0, 0.20};
        in.numberOfRegions = 2;
        in.minAngleDegrees = 20.0;
        return in;
    }

    private static TriangleMesherInput hole() {
        TriangleMesherInput in = pslg(
                new double[]{0, 0, 4, 0, 4, 4, 0, 4, 1, 1, 3, 1, 3, 3, 1, 3},
                new int[]{0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4},
                new int[]{1, 1, 1, 1, 2, 2, 2, 2});
        in.holeList = new double[]{2, 2};
        in.numberOfHoles = 1;
        return in;
    }

    private static TriangleMesherInput segmentRecovery() {
        return pslg(
                new double[]{0, 0, 8, 0, 8, 4, 0, 4, 1, 2, 7, 2, 2, 3, 3, 1, 4, 3, 5, 1, 6, 3},
                new int[]{0, 1, 1, 2, 2, 3, 3, 0, 4, 5},
                new int[]{1, 1, 1, 1, 7});
    }

    private static TriangleMesherInput segmentIntersection() {
        return pslg(
                new double[]{0, 0, 4, 0, 4, 4, 0, 4},
                new int[]{0, 1, 1, 2, 2, 3, 3, 0, 0, 2, 1, 3},
                new int[]{1, 1, 1, 1, 5, 6});
    }

    private static TriangleMesherInput concaveLShape() {
        return pslg(
                new double[]{0, 0, 4, 0, 4, 2, 2, 2, 2, 4, 0, 4},
                new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0},
                new int[]{1, 1, 1, 1, 1, 1});
    }

    private static TriangleMesherInput pslg(double[] points, int[] segments,
                                            int[] markers) {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = points;
        in.numberOfPoints = points.length / 2;
        in.segmentList = segments;
        in.segmentMarkerList = markers;
        in.numberOfSegments = segments.length / 2;
        in.quiet = true;
        return in;
    }
}
