package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMeshers;
import java.util.Random;

/**
 * Micro-benchmark comparing the pure-Java mesher with the native (JNA) one.
 *
 * <p>Lives in the {@code bench} source set so it is excluded from the published
 * jar and from the test run. Run it with:
 *
 * <pre>./gradlew bench</pre>
 *
 * <p>Each case meshes a unit square (4 boundary segments) with a number of
 * random interior points, with and without a quality bound, and reports the
 * mean wall-clock time per call for each implementation and their ratio. This
 * is a rough wall-clock comparison, not a statistically rigorous benchmark
 * (no JMH); the native time at small sizes is dominated by JNA marshalling, not
 * Triangle's compute.
 */
public final class MesherBenchmark {

    private MesherBenchmark() {
    }

    public static void main(String[] args) {
        TriangleMesher java = TriangleMeshers.javaMesher();
        TriangleMesher nat = TriangleMeshers.nativeMesher();

        System.out.printf("%-8s %6s %4s %12s %12s %9s%n",
                "mode", "pts", "q", "java_ms", "native_ms", "ratio");

        int[][] cdt = {{10, 50}, {50, 10}, {100, 5}, {200, 3}};
        for (int[] c : cdt) {
            row(java, nat, c[0], 0, c[1]);
        }
        int[][] quality = {{10, 5}, {20, 2}};
        for (int[] c : quality) {
            row(java, nat, c[0], 20, c[1]);
        }
    }

    private static void row(TriangleMesher java, TriangleMesher nat,
                            int interior, double q, int reps) {
        TriangleMesherInput in = square(interior, q);
        for (int i = 0; i < 2; i++) {                 /* warm-up / JIT */
            java.mesh(in);
            nat.mesh(in);
        }
        long jt = 0, nt = 0;
        for (int i = 0; i < reps; i++) {
            long a = System.nanoTime();
            java.mesh(in);
            jt += System.nanoTime() - a;
            long b = System.nanoTime();
            nat.mesh(in);
            nt += System.nanoTime() - b;
        }
        double jm = jt / 1e6 / reps, nm = nt / 1e6 / reps;
        System.out.printf("%-8s %6d %4.0f %12.3f %12.3f %9.1f%n",
                q > 0 ? "quality" : "cdt", interior, q, jm, nm, jm / nm);
    }

    private static TriangleMesherInput square(int interior, double q) {
        Random rng = new Random(1);
        int n = 4 + interior;
        double[] pts = new double[2 * n];
        pts[0] = 0; pts[1] = 0; pts[2] = 1; pts[3] = 0;
        pts[4] = 1; pts[5] = 1; pts[6] = 0; pts[7] = 1;
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
}
