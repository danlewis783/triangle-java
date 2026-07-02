package com.acme.triangle.bench;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMeshers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

        row(java, nat, "cdt_50", BenchmarkInputs.square(50, 0), 10);
        row(java, nat, "cdt_100", BenchmarkInputs.square(100, 0), 5);
        row(java, nat, "quality_10", BenchmarkInputs.square(10, 20), 5);
        row(java, nat, "quality_20", BenchmarkInputs.square(20, 20), 2);
        row(java, nat, "area_0.010_q20", BenchmarkInputs.squareWithRegionArea(0, 20, 0.010), 3);
        row(java, nat, "area_0.0075_q20", BenchmarkInputs.squareWithRegionArea(0, 20, 0.0075), 2);
    }

    private static void runHeavySyntheticCases(TriangleMesher java, TriangleMesher nat) {
        System.out.println("mode: heavy");
        printHeader();

        row(java, nat, "cdt_200", BenchmarkInputs.square(200, 0), 3);
        row(java, nat, "quality_20", BenchmarkInputs.square(20, 20), 2);
        row(java, nat, "area_0.010_q20", BenchmarkInputs.squareWithRegionArea(0, 20, 0.010), 3);
        row(java, nat, "area_0.0075_q20", BenchmarkInputs.squareWithRegionArea(0, 20, 0.0075), 2);
        row(java, nat, "area_0.005_q20", BenchmarkInputs.squareWithRegionArea(0, 20, 0.005), 2);
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
        System.out.printf("%-24s %8s %8s %8s %4s %12s %12s %9s%n",
                "case", "points", "tri", "nat_tri", "q", "java_ms", "native_ms", "ratio");
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
        int triangles;
        try {
            triangles = java.mesh(in).numberOfTriangles;
        } catch (MeshContractException e) {
            /* The Java mesher's vertex-cap backstop fired - an aggressive bound it
               could not reach on this input. Report (with native's count for
               contrast) and keep benchmarking the rest instead of aborting. */
            System.out.printf("%-24s %8d %8s %8d %4.0f   java did not converge: %s%n",
                    truncate(label, 24), in.numberOfPoints, "-",
                    nat.mesh(in).numberOfTriangles, in.minAngleDegrees, e.getMessage());
            return;
        }
        int nativeTriangles = nat.mesh(in).numberOfTriangles;

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

        System.out.printf("%-24s %8d %8d %8d %4.0f %12.3f %12.3f %9.1f%n",
                truncate(label, 24), in.numberOfPoints, triangles, nativeTriangles,
                in.minAngleDegrees, jm, nm, ratio);
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

        TriangleMesherInput.Builder b = TriangleMesherInput.builder();
        int p0 = b.point(0, 0);
        int p1 = b.point(2, 0);
        int p2 = b.point(2, 1);
        int p3 = b.point(0, 1);
        b.segment(p0, p1, 1).segment(p1, p2, 1).segment(p2, p3, 1).segment(p3, p0, 1);

        double cx = 1.0;
        double cy = 0.5;
        double radius = 0.22;
        int firstHolePoint = -1;
        int prev = -1;
        for (int i = 0; i < holeSides; i++) {
            double angle = 2.0 * Math.PI * i / holeSides;
            int p = b.point(cx + radius * Math.cos(angle), cy + radius * Math.sin(angle));
            if (prev >= 0) {
                b.segment(prev, p, 2);
            } else {
                firstHolePoint = p;
            }
            prev = p;
        }
        b.segment(prev, firstHolePoint, 2);

        return b.hole(cx, cy)
                .region(1.65, 0.75, 1.0, maxArea)
                .minAngleDegrees(q)
                .build();
    }
}
