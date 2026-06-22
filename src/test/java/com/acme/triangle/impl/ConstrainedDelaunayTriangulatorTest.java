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
