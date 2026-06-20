package com.acme.triangle.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Validates Triangle's mesh at each intermediate PHASE against the contract
 * invariants appropriate to that phase, proving the phase-by-phase acceptance
 * harness the pure-Java port will use as it implements each phase:
 *
 * <ul>
 *   <li><b>delaunay</b> - the Delaunay triangulation of the input points
 *       (no segments): with an empty input, {@link MeshValidator} requires
 *       global empty-circumcircle (no segment exemptions) and a valid mesh.</li>
 *   <li><b>cdt</b> - constrained Delaunay with holes carved and regions
 *       attributed but no refinement: the full input with the quality bound
 *       cleared, so segments/holes/regions are checked but not angle quality.</li>
 * </ul>
 *
 * The reference meshes come from the C phase_runner; they are validated
 * structurally, not matched byte-for-byte, since a port may produce a different
 * valid mesh at each phase.
 */
class PhaseValidationTest {

    @TestFactory
    List<DynamicTest> phaseMeshesSatisfyTheirPhaseContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {

            tests.add(dynamicTest(s.name + " [delaunay]", () -> {
                TriangleMesherOutput o =
                        MeshDump.load("/meshes/" + s.name + ".delaunay.txt");
                /* Empty input: no segments -> global Delaunay; no holes/quality. */
                assertThat(MeshValidator.validate(o, new TriangleMesherInput()))
                        .as("delaunay-phase violations for %s", s.name)
                        .isEmpty();
            }));

            tests.add(dynamicTest(s.name + " [cdt]", () -> {
                TriangleMesherOutput o =
                        MeshDump.load("/meshes/" + s.name + ".cdt.txt");
                TriangleMesherInput in = ScenarioFixtures.byName(s.name).input;
                in.minAngleDegrees = 0;          /* CDT phase is not refined */
                assertThat(MeshValidator.validate(o, in))
                        .as("cdt-phase violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }
}
