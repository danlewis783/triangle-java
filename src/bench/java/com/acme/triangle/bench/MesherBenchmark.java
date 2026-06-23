package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;

/**
 * Micro-benchmark comparing the pure-Java mesher with the native (JNA) one.
 * <p>
 * Lives in the {@code bench} source set so it is excluded from the published
 * jar and from the test run.
 * <p>
 * Run with no arguments to benchmark the default light synthetic suite:
 * <pre>./gradlew bench</pre>
 * <p>
 * Pass {@code heavy} to run a heavier synthetic suite:
 * <pre>./gradlew bench --args="heavy"</pre>
 * <p>
 * Pass a directory containing JSON mesher-input documents to benchmark
 * real-world cases:
 * <pre>./gradlew bench --args="src/bench/resources/inputs"</pre>
 * <p>
 * Input capture can be enabled with:
 * <pre>
 * ./gradlew bench ^
 *   -Dtriangle.captureCases=true ^
 *   -Dtriangle.captureDir=C:\temp\triangle-captures
 * </pre>
 * <p>
 * This is a rough wall-clock comparison, not a statistically rigorous
 * benchmark (no JMH). The native time at small sizes is dominated by JNA
 * marshalling, not Triangle's compute.
 */
public final class MesherBenchmark {

    private static final long SYNTHETIC_SEED = 1L;

    private MesherBenchmark() {
    }

    public static void main(String[] args) {
        TriangleMesher java = TriangleMeshers.capturing(TriangleMeshers.javaMesher(), "java");
        TriangleMesher nat = TriangleMeshers.capturing(TriangleMeshers.nativeMesher(), "native");

        if (args.length == 0) {
            runLightSyntheticCases(java, nat);
            return;
        }

        if (args.length == 1) {
            if ("light".equalsIgnoreCase(args[0])) {
                runLightSyntheticCases(java, nat);
                return;
            }
            if ("heavy".equalsIgnoreCase(args[0])) {
                runHeavySyntheticCases(java, nat);
                return;
            }

            Path path = Paths.get(args[0]);
            if (Files.isDirectory(path)) {
                runJsonCases(java, nat, path);
                return;
            }

            System.err.println("argument is neither a mode nor a directory: " + args[0]);
            printUsageAndExit();
            return;
        }

        printUsageAndExit();
    }

    private static void runLightSyntheticCases(TriangleMesher java, TriangleMesher nat) {
        System.out.println("mode: light");
        printHeader();

        row(java, nat, "cdt_50", square(50, 0), 10);
        row(java, nat, "cdt_100", square(100, 0), 5);
        row(java, nat, "quality_10", square(10, 20), 5);
        row(java, nat, "quality_20", square(20, 20), 2);
        row(java, nat, "area_0.010_q20", squareWithRegionArea(0, 20, 0.010), 3);
        row(java, nat, "area_0.0075_q20", squareWithRegionArea(0, 20, 0.0075), 2);
    }

    private static void runHeavySyntheticCases(TriangleMesher java, TriangleMesher nat) {
        System.out.println("mode: heavy");
        printHeader();

        row(java, nat, "cdt_200", square(200, 0), 3);
        row(java, nat, "quality_20", square(20, 20), 2);
        row(java, nat, "area_0.010_q20", squareWithRegionArea(0, 20, 0.010), 3);
        row(java, nat, "area_0.0075_q20", squareWithRegionArea(0, 20, 0.0075), 2);
        row(java, nat, "area_0.005_q20", squareWithRegionArea(0, 20, 0.005), 2);
        row(java, nat, "hole12_q20_a0.010", rectangleWithPolygonHole(12, 20, 0.010), 2);
    }

    private static void runJsonCases(TriangleMesher java, TriangleMesher nat, Path dir) {
        List<BenchmarkInputs.NamedInput> cases = BenchmarkInputs.loadDirectory(dir);
        if (cases.isEmpty()) {
            System.err.println("no benchmark input JSON files found in " + dir);
            System.exit(1);
        }

        System.out.println("mode: json");
        System.out.println("input-dir: " + dir);
        printHeader();

        for (BenchmarkInputs.NamedInput c : cases) {
            row(java, nat, c.name, c.input, repetitionsFor(c.input));
        }
    }

    private static void printHeader() {
        System.out.printf("%-24s %8s %8s %4s %12s %12s %9s%n",
                "case", "points", "tri", "q", "java_ms", "native_ms", "ratio");
    }

    private static void printUsageAndExit() {
        System.err.println("usage:");
        System.err.println("  bench                 # default light synthetic suite");
        System.err.println("  bench light           # explicit light synthetic suite");
        System.err.println("  bench heavy           # heavier synthetic suite");
        System.err.println("  bench <input-dir>     # JSON benchmark cases");
        System.exit(2);
    }

    private static void row(TriangleMesher java, TriangleMesher nat,
                            String label, TriangleMesherInput in, int reps) {
        TriangleMesherOutput javaSample = java.mesh(in);
        int triangles = javaSample.numberOfTriangles;

        for (int i = 0; i < 2; i++) {                 /* warm-up / JIT */
            java.mesh(in);
            nat.mesh(in);
        }

        long jt = 0;
        long nt = 0;
        for (int i = 0; i < reps; i++) {
            long a = System.nanoTime();
            java.mesh(in);
            jt += System.nanoTime() - a;

            long b = System.nanoTime();
            nat.mesh(in);
            nt += System.nanoTime() - b;
        }

        double jm = jt / 1e6 / reps;
        double nm = nt / 1e6 / reps;
        double ratio = nm == 0.0 ? Double.NaN : jm / nm;

        System.out.printf("%-24s %8d %8d %4.0f %12.3f %12.3f %9.1f%n",
                truncate(label, 24), in.numberOfPoints, triangles, in.minAngleDegrees, jm, nm, ratio);
    }

    private static int repetitionsFor(TriangleMesherInput in) {
        boolean hasAreaConstraint = hasPositiveRegionMaxArea(in);
        boolean hasHole = in.numberOfHoles > 0;

        if (hasHole && hasAreaConstraint) {
            return 2;
        }

        if (hasAreaConstraint) {
            if (in.minAngleDegrees >= 28) {
                return 2;
            }
            if (in.numberOfPoints <= 10) {
                return 3;
            }
            return 2;
        }

        if (in.minAngleDegrees > 0) {
            if (in.numberOfPoints <= 20) {
                return 5;
            }
            return 2;
        }

        if (in.numberOfPoints <= 20) {
            return 50;
        }
        if (in.numberOfPoints <= 50) {
            return 10;
        }
        if (in.numberOfPoints <= 100) {
            return 5;
        }
        return 3;
    }

    private static boolean hasPositiveRegionMaxArea(TriangleMesherInput in) {
        if (in.regionList == null) {
            return false;
        }
        for (int r = 0; r < in.numberOfRegions; r++) {
            if (in.regionList[4 * r + 3] > 0) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int width) {
        if (s.length() <= width) {
            return s;
        }
        if (width <= 3) {
            return s.substring(0, width);
        }
        return s.substring(0, width - 3) + "...";
    }

    private static TriangleMesherInput square(int interior, double q) {
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
     * Synthetic square case with one region-wide maximum-area constraint.
     * <p>
     * This is the simplest way to force significantly more refinement than the
     * original point-only synthetic cases. The single region seed at the centre
     * applies a positive max area across the meshed domain, and the optional
     * angle bound adds shape refinement on top.
     */
    private static TriangleMesherInput squareWithRegionArea(int interior, double q,
                                                            double maxArea) {
        TriangleMesherInput in = square(interior, q);
        in.regionList = new double[]{0.5, 0.5, 1.0, maxArea};
        in.numberOfRegions = 1;
        return in;
    }

    /**
     * Rectangle-with-hole synthetic case for medium-heavy development work.
     * <p>
     * The outer domain is a simple rectangle, and the hole is a regular polygon
     * centred inside it. This gives a controllable amount of boundary and hole
     * complexity without the hundreds of points in the captured real-world case.
     * <p>
     * A single region seed in the solid applies the area constraint to the
     * remaining meshed domain outside the hole.
     */
    private static TriangleMesherInput rectangleWithPolygonHole(int holeSides,
                                                                double q,
                                                                double maxArea) {
        if (holeSides < 3) {
            throw new IllegalArgumentException("holeSides must be at least 3");
        }

        int outerPoints = 4;
        int totalPoints = outerPoints + holeSides;
        double[] pts = new double[2 * totalPoints];

        pts[0] = 0.0;
        pts[1] = 0.0;
        pts[2] = 2.0;
        pts[3] = 0.0;
        pts[4] = 2.0;
        pts[5] = 1.0;
        pts[6] = 0.0;
        pts[7] = 1.0;

        double cx = 1.0;
        double cy = 0.5;
        double radius = 0.22;
        for (int i = 0; i < holeSides; i++) {
            double angle = 2.0 * Math.PI * i / holeSides;
            int p = outerPoints + i;
            pts[2 * p] = cx + radius * Math.cos(angle);
            pts[2 * p + 1] = cy + radius * Math.sin(angle);
        }

        int segments = 4 + holeSides;
        int[] segs = new int[2 * segments];
        int[] markers = new int[segments];

        segs[0] = 0;
        segs[1] = 1;
        segs[2] = 1;
        segs[3] = 2;
        segs[4] = 2;
        segs[5] = 3;
        segs[6] = 3;
        segs[7] = 0;
        markers[0] = 1;
        markers[1] = 1;
        markers[2] = 1;
        markers[3] = 1;

        for (int i = 0; i < holeSides; i++) {
            int s = 4 + i;
            int a = outerPoints + i;
            int b = outerPoints + ((i + 1) % holeSides);
            segs[2 * s] = a;
            segs[2 * s + 1] = b;
            markers[s] = 2;
        }

        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = totalPoints;
        in.segmentList = segs;
        in.segmentMarkerList = markers;
        in.numberOfSegments = segments;
        in.holeList = new double[]{cx, cy};
        in.numberOfHoles = 1;
        in.regionList = new double[]{1.65, 0.75, 1.0, maxArea};
        in.numberOfRegions = 1;
        in.minAngleDegrees = q;
        in.quiet = true;
        return in;
    }
}
