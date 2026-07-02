package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Phase-1 port test: the pure-Java {@link DelaunayTriangulator}, run on each
 * scenario's input points, must produce a mesh that passes the same
 * delaunay-phase contract Triangle's output meets (global empty-circumcircle,
 * valid manifold, neighbour-slot semantics) - validated with an empty input so
 * MeshValidator requires global Delaunay.
 * <p>
 * The triangulator returns CCW {@link Corners}; this test derives the flat mesh
 * (points plus adjacency) the {@link MeshValidator} oracle expects, the same way
 * the downstream pipeline derives its own.
 */
class DelaunayTriangulatorTest {

    @TestFactory
    List<DynamicTest> delaunayOfScenarioPointsIsValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherInput in = s.input;
                List<Corners> tris = DelaunayTriangulator.triangulate(
                        FlatPointList.copyOf(in.pointList, in.numberOfPoints));
                TriangleMesherOutput o = toFlatMesh(in.pointList, in.numberOfPoints, tris);
                assertThat(o.numberOfTriangles)
                        .as("%s produced triangles", s.name).isGreaterThan(0);
                assertThat(MeshValidator.validate(o, new TriangleMesherInput()))
                        .as("delaunay contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    /** Pack the corner triples plus derived adjacency into the flat mesh the
        {@link MeshValidator} oracle reads. */
    private static TriangleMesherOutput toFlatMesh(double[] points, int numPoints,
                                                   List<Corners> tris) {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = numPoints;
        o.pointList = Arrays.copyOf(points, numPoints * 2);
        int t = tris.size();
        o.numberOfTriangles = t;
        o.numberOfSegments = 0;
        o.triangleList = new int[3 * t];
        for (int i = 0; i < t; i++) {
            Corners c = tris.get(i);
            o.triangleList[3 * i] = c.a;
            o.triangleList[3 * i + 1] = c.b;
            o.triangleList[3 * i + 2] = c.c;
        }
        int[] tri = o.triangleList;
        o.neighborList = Topology.neighbors(t, (i, c) -> tri[3 * i + c]);
        return o;
    }
}
