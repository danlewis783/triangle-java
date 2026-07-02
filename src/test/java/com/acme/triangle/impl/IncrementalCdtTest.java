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
                CdtResult base = ConstrainedDelaunayTriangulator.build(s.input);
                if (base.triangles.liveCount() == 0) {
                    return;                         /* nothing to refine into */
                }

                IncrementalCdt mesh = new IncrementalCdt(base);
                int before = mesh.triangleCount();

                int inserts = 8;
                for (int i = 0; i < inserts; i++) {
                    insertCentroidOfLargestTriangle(mesh);
                }

                assertThat(mesh.adjacencyConsistent())
                        .as("maintained adjacency matches a rebuild for %s", s.name)
                        .isTrue();
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
                CdtResult base = ConstrainedDelaunayTriangulator.build(s.input);
                if (base.triangles.liveCount() == 0 || base.segmentMarkers.isEmpty()) {
                    return;
                }

                IncrementalCdt mesh = new IncrementalCdt(base);
                int origSegs = base.segmentMarkers.size();
                for (int i = 0; i < origSegs; i++) {
                    mesh.splitSegment(i);           /* index i stays the first half */
                }
                /* A few interior points too, exercising both paths together. */
                for (int i = 0; i < 4; i++) {
                    insertCentroidOfLargestTriangle(mesh);
                }

                assertThat(mesh.adjacencyConsistent())
                        .as("maintained adjacency matches a rebuild after splits for %s", s.name)
                        .isTrue();
                TriangleMesherOutput out = mesh.toOutput();
                assertThat(MeshValidator.validate(out, structuralOnly(s.input)))
                        .as("incremental CDT valid after splits for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    /** Insert a fresh vertex at the centroid of the current largest live
        triangle - a robustly interior point, and the containing triangle comes
        for free. Test-side geometry over the mesh's package-visible stores. */
    private static void insertCentroidOfLargestTriangle(IncrementalCdt mesh) {
        FlatTriangleList tris = mesh.triangles();
        FlatPointList points = mesh.points();
        int best = -1;
        double bestArea = -1;
        for (int i = 0; i < tris.slotCount(); i++) {
            if (!tris.isLive(i)) {
                continue;
            }
            int a = tris.a(i);
            int b = tris.b(i);
            int c = tris.c(i);
            double area = Math.abs((points.x(b) - points.x(a)) * (points.y(c) - points.y(a))
                    - (points.y(b) - points.y(a)) * (points.x(c) - points.x(a))) / 2.0;
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        int a = tris.a(best);
        int b = tris.b(best);
        int c = tris.c(best);
        double cx = (points.x(a) + points.x(b) + points.x(c)) / 3.0;
        double cy = (points.y(a) + points.y(b) + points.y(c)) / 3.0;
        mesh.insertInteriorPoint(cx, cy, best);
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
