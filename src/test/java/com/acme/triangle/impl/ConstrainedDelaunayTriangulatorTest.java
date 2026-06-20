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
                TriangleMesherInput cdtInput = ScenarioFixtures.byName(s.name).input;
                cdtInput.minAngleDegrees = 0;          /* CDT phase: not refined */
                assertThat(o.numberOfTriangles)
                        .as("%s produced triangles", s.name).isGreaterThan(0);
                assertThat(MeshValidator.validate(o, cdtInput))
                        .as("cdt contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }
}
