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
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * Slice 1 of the incremental-refinement work: inserting interior Steiner points
 * into an {@link IncrementalCdt} must keep the mesh a contract-valid constrained
 * Delaunay triangulation - the same {@link MeshValidator} oracle the
 * from-scratch path is held to. We insert into each scenario's mesh and validate
 * against the input with the angle/area requirements dropped (this slice does
 * not refine, so it owes only the <em>structural</em> invariants).
 */
class IncrementalCdtTest {

    @TestFactory
    List<DynamicTest> insertingInteriorPointsKeepsTheMeshValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput base =
                        ConstrainedDelaunayTriangulator.triangulate(s.input);
                if (base.numberOfTriangles == 0) {
                    return;                         /* nothing to refine into */
                }

                IncrementalCdt mesh = new IncrementalCdt(base);
                int before = mesh.triangleCount();

                int inserts = 8;
                for (int i = 0; i < inserts; i++) {
                    mesh.insertInteriorPoint(mesh.centroidOfLargestTriangle());
                }

                TriangleMesherOutput out = mesh.toOutput();
                assertThat(MeshValidator.validate(out, structuralOnly(s.input)))
                        .as("incremental CDT valid for %s", s.name)
                        .isEmpty();
                assertThat(out.numberOfTriangles)
                        .as("insertions grew the mesh for %s", s.name)
                        .isGreaterThan(before);
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> splittingSegmentsKeepsTheMeshValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput base =
                        ConstrainedDelaunayTriangulator.triangulate(s.input);
                if (base.numberOfTriangles == 0 || base.numberOfSegments == 0) {
                    return;
                }

                IncrementalCdt mesh = new IncrementalCdt(base);
                int origSegs = base.numberOfSegments;
                for (int i = 0; i < origSegs; i++) {
                    mesh.splitSegment(i);           /* index i stays the first half */
                }
                /* A few interior points too, exercising both paths together. */
                for (int i = 0; i < 4; i++) {
                    mesh.insertInteriorPoint(mesh.centroidOfLargestTriangle());
                }

                TriangleMesherOutput out = mesh.toOutput();
                assertThat(MeshValidator.validate(out, structuralOnly(s.input)))
                        .as("incremental CDT valid after splits for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    /** A copy of the input with the angle bound and per-region area limits
        removed, so only the structural invariants are required. */
    private static TriangleMesherInput structuralOnly(TriangleMesherInput src) {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = src.pointList;
        in.numberOfPoints = src.numberOfPoints;
        in.segmentList = src.segmentList;
        in.segmentMarkerList = src.segmentMarkerList;
        in.numberOfSegments = src.numberOfSegments;
        in.holeList = src.holeList;
        in.numberOfHoles = src.numberOfHoles;
        if (src.regionList != null) {
            in.regionList = src.regionList.clone();
            for (int r = 0; r < src.numberOfRegions; r++) {
                in.regionList[4 * r + 3] = 0;       /* drop the area constraint */
            }
            in.numberOfRegions = src.numberOfRegions;
        }
        in.minAngleDegrees = 0;
        in.quiet = true;
        return in;
    }
}
