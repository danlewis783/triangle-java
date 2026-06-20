package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * Phase-3 port test: the full pure-Java {@link JavaTriangleMesher} (constrained
 * Delaunay + Ruppert refinement) must satisfy the complete contract - including
 * the minimum-angle quality bound - on every scenario, and must actually refine
 * a mesh that starts below the bound.
 */
class JavaTriangleMesherTest {

    private final TriangleMesher mesher = TriangleMeshers.javaMesher();

    @TestFactory
    List<DynamicTest> scenariosSatisfyTheFullContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput o = mesher.mesh(s.input);
                assertThat(MeshValidator.validate(o, s.input))
                        .as("contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    @Test
    void refinesASliverBelowTheAngleBound() {
        /* A 4x1 rectangle: the constrained Delaunay diagonal makes a ~14 degree
           triangle, so a 20 degree bound forces Ruppert refinement. */
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 4, 0, 4, 1, 0, 1};
        in.numberOfPoints = 4;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.minAngleDegrees = 20;
        in.quiet = true;

        TriangleMesherOutput o = mesher.mesh(in);

        assertThat(o.numberOfTriangles)
                .as("refinement inserted Steiner points")
                .isGreaterThan(2);
        assertThat(MeshValidator.validate(o, in))
                .as("refined sliver violations")
                .isEmpty();
    }
}
