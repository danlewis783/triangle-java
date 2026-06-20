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
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * The capstone: the pure-Java and native implementations are interchangeable
 * behind the facade - each produces a contract-valid mesh for the same input.
 * Validation is contract-based, not byte-equality, since two correct meshers
 * legitimately produce different valid meshes.
 */
class DifferentialTest {

    private final TriangleMesher java = TriangleMeshers.javaMesher();
    private final TriangleMesher nativeMesher = TriangleMeshers.nativeMesher();

    /** Run each scenario through differential(java, native); strict mode throws
        if the Java mesh ever violates the contract. */
    @TestFactory
    List<DynamicTest> scenariosAgreeUnderDifferential() {
        TriangleMesher differential =
                TriangleMeshers.differential(java, nativeMesher);
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput out = differential.mesh(s.input);
                assertThat(MeshValidator.validate(out, s.input))
                        .as("%s under differential", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    /** Throw random PSLGs (square boundary + interior points, with and without a
        quality bound) at both meshers; each must produce a contract-valid mesh. */
    @Test
    void bothMeshersHonourTheContractOnRandomInputs() {
        Random rng = new Random(20260619L);
        for (int c = 0; c < 24; c++) {
            int interior = 3 + rng.nextInt(6);
            double bound = (c % 3 == 0) ? 20.0 : 0.0;
            TriangleMesherInput in = squareWithInteriorPoints(rng, interior, bound);

            TriangleMesherOutput javaOut = java.mesh(in);
            assertThat(MeshValidator.validate(javaOut, in))
                    .as("java mesh, case %d (interior=%d, q=%.0f)", c, interior, bound)
                    .isEmpty();

            TriangleMesherOutput nativeOut = nativeMesher.mesh(in);
            assertThat(MeshValidator.validate(nativeOut, in))
                    .as("native mesh, case %d (interior=%d, q=%.0f)", c, interior, bound)
                    .isEmpty();
        }
    }

    private static TriangleMesherInput squareWithInteriorPoints(Random rng,
                                                                int interior,
                                                                double bound) {
        int n = 4 + interior;
        double[] pts = new double[2 * n];
        /* Unit-square boundary corners (indices 0..3). */
        pts[0] = 0; pts[1] = 0;
        pts[2] = 1; pts[3] = 0;
        pts[4] = 1; pts[5] = 1;
        pts[6] = 0; pts[7] = 1;
        for (int i = 4; i < n; i++) {
            pts[2 * i] = 0.15 + 0.70 * rng.nextDouble();
            pts[2 * i + 1] = 0.15 + 0.70 * rng.nextDouble();
        }
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = n;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.minAngleDegrees = bound;
        in.quiet = true;
        return in;
    }
}
