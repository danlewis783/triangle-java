package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.io.TriangleJson;
import com.acme.triangle.io.TriangleMesherInputDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Loads benchmark mesher inputs from JSON files.
 * <p>
 * Each file is expected to be a versioned `triangle-mesher-input` document.
 * The benchmark name comes from the document `name` when present, otherwise
 * from the file name without the `.json` suffix.
 */
public final class BenchmarkInputs {

    private static final long SYNTHETIC_SEED = 1L;

    private BenchmarkInputs() {
    }

    /**
     * A unit square (4 boundary corners joined by segments) with {@code interior}
     * pseudo-random interior points and optional angle bound {@code q}. Seeded, so
     * the point cloud is reproducible across runs and builds.
     */
    public static TriangleMesherInput square(int interior, double q) {
        Random rng = new Random(SYNTHETIC_SEED);
        int n = 4 + interior;
        double[] pts = new double[2 * n];
        pts[0] = 0;
        pts[1] = 0;
        pts[2] = 1;
        pts[3] = 0;
        pts[4] = 1;
        pts[5] = 1;
        pts[6] = 0;
        pts[7] = 1;

        for (int i = 4; i < n; i++) {
            pts[2 * i] = 0.1 + 0.8 * rng.nextDouble();
            pts[2 * i + 1] = 0.1 + 0.8 * rng.nextDouble();
        }

        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = n;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.minAngleDegrees = q;
        in.quiet = true;
        return in;
    }

    /**
     * {@link #square} plus a single centre region seed carrying a maximum-area
     * constraint - the simplest way to force significantly more refinement than
     * the point-only synthetic cases (smaller {@code maxArea} => more triangles).
     */
    public static TriangleMesherInput squareWithRegionArea(int interior, double q, double maxArea) {
        TriangleMesherInput in = square(interior, q);
        in.regionList = new double[]{0.5, 0.5, 1.0, maxArea};
        in.numberOfRegions = 1;
        return in;
    }

    /**
     * A {@code side x side} integer lattice of points (plus its four corner
     * segments as the boundary), no refinement. Grid points make collinear and
     * cocircular predicate ties the common case rather than the exception, so
     * this is the construction-time stress case for the adaptive/exact predicate
     * stages - the path random point clouds almost never take.
     */
    public static TriangleMesherInput grid(int side) {
        int n = side * side;
        double[] pts = new double[2 * n];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                int p = i * side + j;
                pts[2 * p] = j;
                pts[2 * p + 1] = i;
            }
        }
        int c00 = 0;
        int c01 = side - 1;
        int c10 = (side - 1) * side;
        int c11 = side * side - 1;

        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = n;
        in.segmentList = new int[]{c00, c01, c01, c11, c11, c10, c10, c00};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.minAngleDegrees = 0;
        in.quiet = true;
        return in;
    }

    /** One captured benchmark input loaded from a JSON document. */
    public static TriangleMesherInput fromJson(Path path) {
        return TriangleJson.readInput(path);
    }

    public static List<NamedInput> loadDirectory(Path dir) {
        try {
            List<NamedInput> inputs = new ArrayList<>();
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> inputs.add(loadOne(p)));
            }
            return inputs;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load benchmark inputs from " + dir, e);
        }
    }

    private static NamedInput loadOne(Path path) {
        TriangleMesherInputDocument doc = TriangleJson.readInputDocument(path);
        String fileName = path.getFileName().toString();
        String name = doc.name != null && !doc.name.trim().isEmpty()
                ? doc.name.trim()
                : stripJsonSuffix(fileName);
        TriangleMesherInput input = doc.input;
        return new NamedInput(name, input, path);
    }

    private static String stripJsonSuffix(String fileName) {
        return fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length())
                : fileName;
    }

    public static final class NamedInput {
        public final String name;
        public final TriangleMesherInput input;
        public final Path source;

        public NamedInput(String name, TriangleMesherInput input, Path source) {
            this.name = name;
            this.input = input;
            this.source = source;
        }
    }
}