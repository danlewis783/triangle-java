package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesher2;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherInput2;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMesherOutput2;
import com.acme.triangle.io.TriangleJson;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that captures mesher inputs as JSON when enabled by system property.
 * <p>
 * Enable with `-Dtriangle.captureCases=true`. The capture directory is taken
 * from `-Dtriangle.captureDir=...`; if absent, a directory under
 * `java.io.tmpdir` is used.
 */
public final class CapturingTriangleMesher implements TriangleMesher, TriangleMesher2 {

    static final String CAPTURE_CASES_PROPERTY = "triangle.captureCases";
    static final String CAPTURE_DIR_PROPERTY = "triangle.captureDir";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final TriangleMesher2 delegate;
    private final boolean enabled;
    private final Path captureDir;
    private final String mesherName;

    public CapturingTriangleMesher(TriangleMesher2 delegate, String mesherName) {
        this.delegate = delegate;
        this.enabled = Boolean.parseBoolean(System.getProperty(CAPTURE_CASES_PROPERTY, "false"));
        this.captureDir = resolveCaptureDir();
        this.mesherName = mesherName;
    }

    @Override
    public TriangleMesherOutput2 mesh(TriangleMesherInput2 input) {
        if (enabled) {
            captureInput(input.toFlat());       /* the JSON capture format is the flat DTO */
        }
        return delegate.mesh(input);
    }

    /** Flat entry point: repack to the modelled form this decorator works in, then
        marshal the result back - the conversion lives on the DTOs. */
    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        return mesh(TriangleMesherInput2.from(input)).toFlat();
    }

    private void captureInput(TriangleMesherInput input) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        long sequence = SEQUENCE.incrementAndGet();
        String baseName = timestamp + "-" + pad(sequence) + "-" + mesherName;
        Path path = captureDir.resolve(baseName + "-input.json");
        TriangleJson.writeInput(path, input, baseName);
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