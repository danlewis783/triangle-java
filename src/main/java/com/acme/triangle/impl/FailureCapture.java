package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.MeshInputException;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.io.TriangleJson;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post-mortem capture of failing inputs: when meshing throws, the input is
 * dumped as JSON and the dump path is attached to the exception message, so a
 * consumer-side failure arrives with its repro case already on disk. This is
 * the failure-triggered counterpart of {@link CapturingTriangleMesher} (which
 * captures <em>every</em> input, opt-in); failure capture is <b>on by
 * default</b> and disabled with {@code -Dtriangle.captureFailures=false}. The
 * capture directory is shared with the capture-all decorator
 * ({@code -Dtriangle.captureDir}, defaulting under {@code java.io.tmpdir}).
 * <p>
 * A capture failure (unwritable directory, disk full) never masks the original
 * exception: the note then records why the capture is missing instead.
 */
public final class FailureCapture {

    static final String ENABLED_PROPERTY = "triangle.captureFailures";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private FailureCapture() {
    }

    /**
     * Dump {@code input} and annotate {@code failure} with the dump location:
     * the typed exceptions ({@link MeshInputException},
     * {@link MeshContractException}) get the path attached to their message and
     * are returned as-is; any other exception is wrapped so its message can
     * carry the path. Idempotent - an already-annotated exception passes
     * through - and a no-op when disabled.
     *
     * @return the exception to throw
     */
    public static RuntimeException annotate(TriangleMesherInput input, RuntimeException failure) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return failure;
        }
        if (failure instanceof MeshInputException) {
            MeshInputException e = (MeshInputException) failure;
            if (e.capturePath() == null) {
                e.attachCapture(capture(input));
            }
            return e;
        }
        if (failure instanceof MeshContractException) {
            MeshContractException e = (MeshContractException) failure;
            if (e.capturePath() == null) {
                e.attachCapture(capture(input));
            }
            return e;
        }
        return new RuntimeException(
                "meshing failed [input captured: " + capture(input) + "]", failure);
    }

    /** Write the input dump; returns its absolute path, or a note explaining why
        the capture is missing (never throws). */
    private static String capture(TriangleMesherInput input) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            String baseName = timestamp + "-" + SEQUENCE.incrementAndGet() + "-failure";
            Path path = captureDir().resolve(baseName + "-input.json");
            TriangleJson.writeInput(path, input, baseName);
            return path.toAbsolutePath().toString();
        } catch (RuntimeException e) {
            return "capture failed: " + e;
        }
    }

    private static Path captureDir() {
        String configured = System.getProperty(CapturingTriangleMesher.CAPTURE_DIR_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "triangle-captures");
    }
}
