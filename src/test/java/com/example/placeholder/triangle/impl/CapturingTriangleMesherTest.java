package com.example.placeholder.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.placeholder.triangle.TriangleMesher;
import com.example.placeholder.triangle.TriangleMesherInput;
import com.example.placeholder.triangle.TriangleMesherOutput;
import com.example.placeholder.triangle.TriangleMeshers;
import com.example.placeholder.triangle.io.TriangleJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in capture-everything decorator: with {@code triangle.captureCases} set,
 * each mesh() call dumps a base-name-matched JSON pair - the input before
 * meshing, the output after it succeeds. These tests enable capture against a
 * temp directory and restore the properties after.
 */
class CapturingTriangleMesherTest {

    @TempDir
    Path captureDir;

    private String oldEnabled;
    private String oldDir;

    @BeforeEach
    void enableCaptureIntoTempDir() {
        oldEnabled = System.setProperty("triangle.captureCases", "true");
        oldDir = System.setProperty("triangle.captureDir", captureDir.toString());
    }

    @AfterEach
    void restoreProperties() {
        restore("triangle.captureCases", oldEnabled);
        restore("triangle.captureDir", oldDir);
    }

    private static void restore(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }

    private static TriangleMesherInput square() {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 1, 0, 1, 1, 0, 1};
        in.numberOfPoints = 4;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.quiet = true;
        return in;
    }

    @Test
    void capturesInputAndOutputAsAMatchedPair() throws IOException {
        TriangleMesher m = TriangleMeshers.capturing(TriangleMeshers.javaMesher(), "java");

        TriangleMesherOutput returned = m.mesh(square());

        List<Path> files = listCaptures();
        assertThat(files).hasSize(2);
        Path inputDump = only(files, "-input.json");
        Path outputDump = only(files, "-output.json");
        assertThat(baseName(inputDump, "-input.json"))
                .as("input and output dumps share the base name")
                .isEqualTo(baseName(outputDump, "-output.json"));

        /* Both dumps round-trip through the JSON reader. */
        assertThat(TriangleJson.readInput(inputDump).numberOfPoints).isEqualTo(4);
        TriangleMesherOutput replayed = TriangleJson.readOutput(outputDump);
        assertThat(replayed.numberOfTriangles).isEqualTo(returned.numberOfTriangles);
        assertThat(replayed.triangleList).isEqualTo(returned.triangleList);
        assertThat(replayed.pointList).isEqualTo(returned.pointList);
    }

    @Test
    void aFailedMeshLeavesTheInputDumpWithNoOutputCounterpart() throws IOException {
        TriangleMesher m = TriangleMeshers.capturing(
                input -> { throw new IllegalStateException("boom"); }, "java");

        assertThatThrownBy(() -> m.mesh(square())).hasMessage("boom");

        List<Path> files = listCaptures();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString()).endsWith("-input.json");
    }

    @Test
    void disabledCaptureWritesNothing() throws IOException {
        System.setProperty("triangle.captureCases", "false");
        TriangleMesher m = TriangleMeshers.capturing(TriangleMeshers.javaMesher(), "java");

        m.mesh(square());

        assertThat(listCaptures()).isEmpty();
    }

    private List<Path> listCaptures() throws IOException {
        try (Stream<Path> files = Files.list(captureDir)) {
            List<Path> result = new ArrayList<>();
            files.forEach(result::add);
            return result;
        }
    }

    private static Path only(List<Path> files, String suffix) {
        List<Path> matches = new ArrayList<>();
        for (Path p : files) {
            if (p.getFileName().toString().endsWith(suffix)) {
                matches.add(p);
            }
        }
        assertThat(matches).as("exactly one *%s dump", suffix).hasSize(1);
        return matches.get(0);
    }

    private static String baseName(Path path, String suffix) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - suffix.length());
    }
}
