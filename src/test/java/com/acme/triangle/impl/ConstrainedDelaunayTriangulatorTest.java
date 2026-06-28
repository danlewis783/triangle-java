package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase-2 port test: the pure-Java {@link ConstrainedDelaunayTriangulator}, run
 * on each scenario, must pass the CDT-phase contract - constrained Delaunay,
 * segments recovered as edges, holes carved, regions attributed - validated
 * with the quality bound cleared (refinement is phase 3).
 */
class ConstrainedDelaunayTriangulatorTest {

    @TestFactory
    List<DynamicTest> cdtOfScenariosIsValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput o =
                        ConstrainedDelaunayTriangulator.triangulate(s.input);
                TriangleMesherInput cdtInput = ScenarioFixtures.cdtPhaseInput(s.name);
                assertThat(o.numberOfTriangles)
                        .as("%s produced triangles", s.name).isGreaterThan(0);
                assertThat(MeshValidator.validate(o, cdtInput))
                        .as("cdt contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    /**
     * A vertex lying on a boundary segment (a T-junction). The segment cannot be
     * recovered as a single edge through the vertex, so it must be output as a
     * chain of subsegments. Reproduces the consumer-integration bug where the
     * output listed the un-split segment, which was not a mesh edge.
     */
    @Test
    void splitsASegmentAtAVertexLyingOnIt() {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 2, 0, 2, 1, 0, 1, 1, 0};   // vertex 4 on bottom
        in.numberOfPoints = 5;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};          // bottom is one segment
        in.segmentMarkerList = new int[]{7, 1, 1, 1};
        in.numberOfSegments = 4;
        in.quiet = true;

        TriangleMesherOutput o = ConstrainedDelaunayTriangulator.triangulate(in);

        assertThat(MeshValidator.validate(o, in))
                .as("contract violations").isEmpty();
        assertThat(hasOutputSegment(o, 0, 4)).as("(0,4) recovered").isTrue();
        assertThat(hasOutputSegment(o, 4, 1)).as("(4,1) recovered").isTrue();
        assertThat(hasOutputSegment(o, 0, 1)).as("un-split (0,1) NOT present").isFalse();
    }

    /**
     * Stress the segment-recovery path: a dense interior point cloud with a fan of
     * long constraints from one corner to scattered far points, each crossing many
     * Delaunay edges, so recovery does a large number of local walks and flips. The
     * far points are placed off the corner diagonals so no constraint is collinear
     * with another vertex (a separate, pre-existing degeneracy). The result must
     * still be a contract-valid constrained Delaunay mesh with the constraints
     * recovered as edges.
     */
    @Test
    void recoversManyCrossingConstraintsIntoAValidMesh() {
        /* corners, then four far points at distinct angles from corner 0 (none at
           45 degrees, so none collinear with a corner), then a dense interior. */
        double[] fixed = {
                0, 0, 1, 0, 1, 1, 0, 1,
                0.92, 0.28, 0.85, 0.62, 0.55, 0.95, 0.95, 0.45,
        };
        Random rng = new Random(42);
        int interior = 400;
        int total = 8 + interior;
        double[] pts = new double[2 * total];
        System.arraycopy(fixed, 0, pts, 0, fixed.length);
        for (int i = 8; i < total; i++) {
            pts[2 * i] = 0.02 + 0.96 * rng.nextDouble();
            pts[2 * i + 1] = 0.02 + 0.96 * rng.nextDouble();
        }

        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = total;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0, 0, 4, 0, 5, 0, 6, 0, 7};  // box + fan
        in.segmentMarkerList = new int[]{1, 1, 1, 1, 2, 2, 2, 2};
        in.numberOfSegments = 8;
        in.quiet = true;

        TriangleMesherOutput o = ConstrainedDelaunayTriangulator.triangulate(in);

        assertThat(o.numberOfTriangles).as("stress case produced triangles").isGreaterThan(interior);
        assertThat(MeshValidator.validate(o, in))
                .as("stress CDT contract violations").isEmpty();
    }

    /**
     * The square's two diagonals cross at its centre and are each collinear with
     * the opposite corner. After the crossing is split, each half-diagonal is a
     * constraint whose supporting line passes through that corner - a degeneracy
     * that stalls the local channel walk, so recovery falls back to a global seed.
     * The mesh must still be contract-valid (recovery does not silently fail).
     */
    @Test
    void recoversCollinearCrossingDiagonals() {
        Random rng = new Random(42);
        int interior = 400;
        int total = 4 + interior;
        double[] pts = new double[2 * total];
        pts[0] = 0; pts[1] = 0; pts[2] = 1; pts[3] = 0;
        pts[4] = 1; pts[5] = 1; pts[6] = 0; pts[7] = 1;
        for (int i = 4; i < total; i++) {
            pts[2 * i] = 0.02 + 0.96 * rng.nextDouble();
            pts[2 * i + 1] = 0.02 + 0.96 * rng.nextDouble();
        }
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = total;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0, 0, 2, 1, 3};  // box + both diagonals
        in.segmentMarkerList = new int[]{1, 1, 1, 1, 2, 2};
        in.numberOfSegments = 6;
        in.quiet = true;

        TriangleMesherOutput o = ConstrainedDelaunayTriangulator.triangulate(in);

        assertThat(MeshValidator.validate(o, in))
                .as("collinear crossing-diagonal CDT violations").isEmpty();
    }

    private static boolean hasOutputSegment(TriangleMesherOutput o, int a, int b) {
        for (int i = 0; i < o.numberOfSegments; i++) {
            int u = o.segmentList[2 * i], w = o.segmentList[2 * i + 1];
            if ((u == a && w == b) || (u == b && w == a)) {
                return true;
            }
        }
        return false;
    }
}
