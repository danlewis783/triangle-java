package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.MeshInputException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import com.acme.triangle.io.TriangleJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Failure-triggered capture: when meshing throws, the input lands on disk and
 * the exception message says where - the post-mortem story the consumer relies
 * on. The suite runs with capture globally disabled (see the test task); these
 * tests re-enable it against a temp directory and restore the properties after.
 */
class FailureCaptureTest {

    @TempDir
    Path captureDir;

    private String oldEnabled;
    private String oldDir;

    @BeforeEach
    void enableCaptureIntoTempDir() {
        oldEnabled = System.setProperty("triangle.captureFailures", "true");
        oldDir = System.setProperty("triangle.captureDir", captureDir.toString());
    }

    @AfterEach
    void restoreProperties() {
        restore("triangle.captureFailures", oldEnabled);
        restore("triangle.captureDir", oldDir);
    }

    private static void restore(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }

    /** A mesher that returns a structurally invalid mesh (out-of-range corner). */
    private static final TriangleMesher BAD = input -> {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = 3;
        o.pointList = new double[]{0, 0, 1, 0, 0, 1};
        o.numberOfTriangles = 1;
        o.triangleList = new int[]{0, 1, 99};
        o.neighborList = new int[]{-1, -1, -1};
        o.numberOfSegments = 0;
        o.segmentList = new int[0];
        return o;
    };

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
    void contractViolationCapturesTheInputAndSaysWhere() {
        TriangleMesher m = TriangleMeshers.validating(BAD);

        MeshContractException e =
                catchThrowableOfType(() -> m.mesh(square()), MeshContractException.class);

        assertThat(e.getMessage()).contains("[input captured: ");
        assertThat(e.capturePath()).isNotNull();
        Path dump = Paths.get(e.capturePath());
        assertThat(dump).exists();
        /* The dump is a readable repro: it round-trips through the JSON reader. */
        TriangleMesherInput repro = TriangleJson.readInput(dump);
        assertThat(repro.numberOfPoints).isEqualTo(4);
    }

    @Test
    void invalidInputCapturesTheInputAndSaysWhere() {
        TriangleMesherInput in = square();
        in.segmentList[1] = 99;                      /* out-of-range endpoint */

        MeshInputException e = catchThrowableOfType(
                () -> TriangleMeshers.javaMesher().mesh(in), MeshInputException.class);

        assertThat(e.getMessage()).contains("[input captured: ");
        assertThat(Paths.get(e.capturePath())).exists();
    }

    @Test
    void disablingCaptureLeavesTheExceptionClean() throws Exception {
        System.setProperty("triangle.captureFailures", "false");
        TriangleMesher m = TriangleMeshers.validating(BAD);

        assertThatThrownBy(() -> m.mesh(square()))
                .isInstanceOf(MeshContractException.class)
                .hasMessageNotContaining("[input captured: ");
        try (java.util.stream.Stream<Path> files = Files.list(captureDir)) {
            assertThat(files).as("no dump written when disabled").isEmpty();
        }
    }
}
