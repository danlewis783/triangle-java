package com.example.placeholder.triangle.impl;

import com.example.placeholder.triangle.TriangleMesher;
import com.example.placeholder.triangle.TriangleMesherInput;
import com.example.placeholder.triangle.TriangleMesherOutput;
import com.example.placeholder.triangle.io.TriangleJson;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that captures mesher inputs and outputs as JSON when enabled by
 * system property.
 * <p>
 * Enable with `-Dtriangle.captureCases=true`. The capture directory is taken
 * from `-Dtriangle.captureDir=...`; if absent, a directory under
 * `java.io.tmpdir` is used. Each call writes a base-name-matched pair: the
 * input before meshing (so a crash still leaves the repro on disk) and the
 * output once the delegate returns, so a failed mesh leaves an input dump
 * with no output counterpart.
 */
public final class CapturingTriangleMesher implements TriangleMesher {

    static final String CAPTURE_CASES_PROPERTY = "triangle.captureCases";
    static final String CAPTURE_DIR_PROPERTY = "triangle.captureDir";

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final TriangleMesher delegate;
    private final boolean enabled;
    private final Path captureDir;
    private final String mesherName;

    public CapturingTriangleMesher(TriangleMesher delegate, String mesherName) {
        this.delegate = delegate;
        this.enabled = Boolean.parseBoolean(System.getProperty(CAPTURE_CASES_PROPERTY, "false"));
        this.captureDir = resolveCaptureDir();
        this.mesherName = mesherName;
    }

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        if (!enabled) {
            return delegate.mesh(input);
        }
        String baseName = nextBaseName();
        TriangleJson.writeInput(captureDir.resolve(baseName + "-input.json"), input, baseName);
        TriangleMesherOutput output = delegate.mesh(input);
        TriangleJson.writeOutput(captureDir.resolve(baseName + "-output.json"), output, baseName);
        return output;
    }

    private String nextBaseName() {
        String timestamp = TIMESTAMP.format(LocalDateTime.now(ZoneId.systemDefault()));
        return timestamp + "-" + pad(SEQUENCE.incrementAndGet()) + "-" + mesherName;
    }

    private static Path resolveCaptureDir() {
        String configured = System.getProperty(CAPTURE_DIR_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        String tmp = System.getProperty("java.io.tmpdir");
        return Paths.get(tmp, "triangle-captures");
    }

    private static String pad(long value) {
        String s = Long.toString(value);
        if (s.length() >= 6) {
            return s;
        }
        StringBuilder b = new StringBuilder(6);
        for (int i = s.length(); i < 6; i++) {
            b.append('0');
        }
        b.append(s);
        return b.toString();
    }
}
