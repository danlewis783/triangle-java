package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * End-to-end test of the JNA native adapter: drive each scenario's input
 * through {@link NativeTriangleMesher} (which calls the native Triangle library)
 * and confirm the resulting mesh satisfies the full structural contract via
 * {@link MeshValidator}. This exercises the complete pipeline DTO in -> native
 * triangulate() -> DTO out -> contract validation.
 */
class NativeTriangleMesherTest {

    @TestFactory
    List<DynamicTest> nativeMeshesSatisfyTheContract() {
        NativeTriangleMesher mesher = new NativeTriangleMesher();
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput o = mesher.mesh(s.input);
                assertThat(o.numberOfTriangles)
                        .as("%s produced a non-empty mesh", s.name)
                        .isGreaterThan(0);
                assertThat(MeshValidator.validate(o, s.input))
                        .as("contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }
}
