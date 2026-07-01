package com.acme.triangle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link TriangleMeshers2} exposes the same meshers and decorators as
 * {@link TriangleMeshers}, over the modelled {@link TriangleMesherInput2}/{@link
 * TriangleMesherOutput2} types. Each produces a contract-valid mesh; validation
 * is contract-based (via the flat marshalling), not byte-equality.
 */
class TriangleMeshers2Test {

    private final TriangleMesher2 java = TriangleMeshers2.javaMesher();
    private final TriangleMesher2 nativeMesher = TriangleMeshers2.nativeMesher();

    /** A modelled mesher that returns a structurally invalid mesh (out-of-range corner). */
    private static final TriangleMesher2 BAD = input -> new TriangleMesherOutput2(
            ImmutableList.of(new Point(0, 0), new Point(1, 0), new Point(0, 1)),
            ImmutableList.<ImmutableTriangle>of(new DefaultImmutableTriangle(0, 1, 99, -1, -1, -1, 0.0)),
            ImmutableList.of(),
            false);

    /** Both concrete meshers, reached through the modelled factory, honour the contract. */
    @TestFactory
    List<DynamicTest> nativeAndJavaHonourTheContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            TriangleMesherInput2 in = TriangleMesherInput2.from(s.input);
            tests.add(dynamicTest("native:" + s.name,
                    () -> assertValid(nativeMesher.mesh(in), s.input, s.name)));
            tests.add(dynamicTest("java:" + s.name,
                    () -> assertValid(java.mesh(in), s.input, s.name)));
        }
        return tests;
    }

    /** differential(java, native) over every scenario; strict mode throws if the
        primary ever violates the contract. */
    @TestFactory
    List<DynamicTest> scenariosAgreeUnderDifferential() {
        TriangleMesher2 differential = TriangleMeshers2.differential(java, nativeMesher);
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            TriangleMesherInput2 in = TriangleMesherInput2.from(s.input);
            tests.add(dynamicTest(s.name, () -> assertValid(differential.mesh(in), s.input, s.name)));
        }
        return tests;
    }

    @Test
    void capturingPassesThroughWhenDisabled() {
        TriangleMesher2 m = TriangleMeshers2.capturing(nativeMesher, "test");
        Scenario s = ScenarioFixtures.byName("pslg_square");
        assertValid(m.mesh(TriangleMesherInput2.from(s.input)), s.input, "capturing");
    }

    @Test
    void validatingPassesAValidMeshThrough() {
        TriangleMesher2 m = TriangleMeshers2.validating(nativeMesher);
        Scenario s = ScenarioFixtures.byName("pslg_square");
        assertThat(m.mesh(TriangleMesherInput2.from(s.input)).getTriangles()).isNotEmpty();
    }

    @Test
    void validatingThrowsOnAnInvalidMesh() {
        TriangleMesher2 m = TriangleMeshers2.validating(BAD);
        Scenario s = ScenarioFixtures.byName("pslg_square");
        assertThatThrownBy(() -> m.mesh(TriangleMesherInput2.from(s.input)))
                .isInstanceOf(MeshContractException.class);
    }

    @Test
    void differentialThrowsStrictlyWhenPrimaryDiverges() {
        TriangleMesher2 m = TriangleMeshers2.differential(BAD, nativeMesher);
        Scenario s = ScenarioFixtures.byName("pslg_square");
        assertThatThrownBy(() -> m.mesh(TriangleMesherInput2.from(s.input)))
                .isInstanceOf(MeshContractException.class);
    }

    @Test
    void differentialNotifiesHandlerAndReturnsPrimaryInShadowMode() {
        List<String> captured = new ArrayList<>();
        DivergenceHandler shadow =
                (in, primaryViolations, refViolations) -> captured.addAll(primaryViolations);
        TriangleMesher2 m = TriangleMeshers2.differential(BAD, nativeMesher, shadow);
        Scenario s = ScenarioFixtures.byName("pslg_square");

        TriangleMesherOutput2 out = m.mesh(TriangleMesherInput2.from(s.input));

        assertThat(captured).as("primary violations were reported").isNotEmpty();
        assertThat(out.getTriangles()).as("returns the primary (under-test) output").hasSize(1);
    }

    private static void assertValid(TriangleMesherOutput2 out, TriangleMesherInput flat, String name) {
        assertThat(MeshValidator.validate(out.toFlat(), flat))
                .as("%s via TriangleMeshers2", name)
                .isEmpty();
    }
}
