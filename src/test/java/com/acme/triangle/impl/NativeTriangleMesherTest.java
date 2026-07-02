package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import com.acme.triangle.io.TriangleJson;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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

    @TestFactory
    List<DynamicTest> capturedRegressionFixturesProduceContractValidMeshes()
            throws URISyntaxException, IOException {
        /* The same captured-consumer fixtures the Java mesher is pinned on
           (JavaTriangleMesherTest): the fixtures' README treats native as the
           trusted baseline, so record that claim as a test. */
        NativeTriangleMesher mesher = new NativeTriangleMesher();
        URL dir = getClass().getResource("/regression");
        assertThat(dir).as("regression fixture directory on the test classpath").isNotNull();
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(Paths.get(dir.toURI()))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> tests.add(dynamicTest(p.getFileName().toString(), () -> {
                        TriangleMesherInput in = TriangleJson.readInput(p);
                        TriangleMesherOutput o = mesher.mesh(in);
                        assertThat(MeshValidator.validate(o, in))
                                .as("contract violations for %s", p.getFileName())
                                .isEmpty();
                    })));
        }
        assertThat(tests).as("regression fixtures found").isNotEmpty();
        return tests;
    }
}
