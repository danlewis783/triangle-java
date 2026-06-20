package com.acme.triangle.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Proves {@link MeshValidator} correct: Triangle's known-good meshes for every
 * scenario must satisfy all six invariants, and each invariant must catch a
 * deliberately broken mesh (the teeth-tests). Together these establish the
 * contract-equivalence acceptance bar a Java mesher must later meet.
 */
class ContractTest {

    @TestFactory
    List<DynamicTest> triangleMeshesSatisfyTheContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput o = MeshDump.load(s.meshResource);
                assertThat(MeshValidator.validate(o, s.input))
                        .as("contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    // --- teeth-tests: each invariant must reject a broken mesh -------------- //

    @Test
    void topologyRejectsRepeatedCorner() {
        Scenario s = ScenarioFixtures.byName("pslg_square");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        o.triangleList[1] = o.triangleList[0];                 /* repeat a corner */
        assertThat(MeshValidator.validate(o, s.input))
                .anyMatch(m -> m.startsWith("topology"));
    }

    @Test
    void neighboursRejectOutOfRangeSlot() {
        Scenario s = ScenarioFixtures.byName("pslg_square_quality");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        o.neighborList[0] = 9999;
        assertThat(MeshValidator.validate(o, s.input))
                .anyMatch(m -> m.startsWith("neighbors"));
    }

    @Test
    void delaunayRejectsNonEmptyCircumcircle() {
        /* Two very flat triangles sharing edge (0,1); each apex lies inside the
           other's circumcircle, so the shared edge is not locally Delaunay. */
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = 4;
        o.pointList = new double[]{0, 0, 6, 0, 3, 0.2, 3, -0.2};
        o.numberOfTriangles = 2;
        o.triangleList = new int[]{0, 1, 2, 1, 0, 3};
        o.neighborList = new int[]{-1, -1, 1, -1, -1, 0};
        o.numberOfSegments = 0;
        o.segmentList = new int[0];

        List<String> v = MeshValidator.validate(o, new TriangleMesherInput());
        assertThat(v).anyMatch(m -> m.startsWith("delaunay"));
    }

    @Test
    void segmentsRejectNonEdgeSegment() {
        Scenario s = ScenarioFixtures.byName("pslg_square");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        o.segmentList[0] = 0;
        o.segmentList[1] = 9999;                                /* not a mesh edge */
        assertThat(MeshValidator.validate(o, s.input))
                .anyMatch(m -> m.startsWith("segments"));
    }

    @Test
    void holesRejectPointInsideMesh() {
        Scenario s = ScenarioFixtures.byName("hole");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        /* (2,0.3) is strictly inside a triangle of the meshed ring, not the
           carved hole and not on a corner diagonal. */
        TriangleMesherInput in = new TriangleMesherInput();
        in.holeList = new double[]{2, 0.3};
        in.numberOfHoles = 1;
        assertThat(MeshValidator.validate(o, in))
                .anyMatch(m -> m.startsWith("holes"));
    }

    @Test
    void regionsRejectAttributeLeakAcrossNonSegmentEdge() {
        Scenario s = ScenarioFixtures.byName("regions");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        /* Flip one triangle's attribute so it differs from an interior neighbour. */
        for (int i = 0; i < o.numberOfTriangles; i++) {
            o.triangleAttributeList[i] = o.triangleAttributeList[i] == 1.0 ? 2.0 : 1.0;
            break;
        }
        assertThat(MeshValidator.validate(o, s.input))
                .anyMatch(m -> m.startsWith("regions"));
    }

    @Test
    void qualityRejectsAnglesBelowBound() {
        Scenario s = ScenarioFixtures.byName("pslg_square_quality");
        TriangleMesherOutput o = MeshDump.load(s.meshResource);
        TriangleMesherInput in = ScenarioFixtures.byName("pslg_square_quality").input;
        in.minAngleDegrees = 89.0;                              /* impossible bound */
        assertThat(MeshValidator.validate(o, in))
                .anyMatch(m -> m.startsWith("quality"));
    }
}
