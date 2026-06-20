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
 * Phase-1 port test: the pure-Java {@link DelaunayTriangulator}, run on each
 * scenario's input points, must produce a mesh that passes the same
 * delaunay-phase contract Triangle's output meets (global empty-circumcircle,
 * valid manifold, neighbour-slot semantics) - validated with an empty input so
 * MeshValidator requires global Delaunay.
 */
class DelaunayTriangulatorTest {

    @TestFactory
    List<DynamicTest> delaunayOfScenarioPointsIsValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherInput in = s.input;
                TriangleMesherOutput o =
                        DelaunayTriangulator.triangulate(in.pointList, in.numberOfPoints);
                assertThat(o.numberOfTriangles)
                        .as("%s produced triangles", s.name).isGreaterThan(0);
                assertThat(MeshValidator.validate(o, new TriangleMesherInput()))
                        .as("delaunay contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }
}
