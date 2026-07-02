package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesher2;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherInput2;
import com.acme.triangle.TriangleMeshers2;
import java.nio.file.Paths;

/**
 * Profiling workload: loops the pure-Java {@link TriangleMesher2} over one
 * scenario so a profiler (JFR via the {@code profile} Gradle task, or any
 * attached sampler) sees nothing but the Java mesher - no native runs, no
 * harness noise. Scenario names match {@link MesherJmh}'s.
 * <p>
 * Usage: {@code ./gradlew profile --args="ref-rings-q33 30"} (scenario,
 * seconds; both optional - defaults below). The recording lands in
 * {@code build/jfr/<scenario>.jfr}.
 */
public final class ProfileDriver {

    private ProfileDriver() {
    }

    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "ref-rings-q33";
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 30;

        TriangleMesher2 mesher = TriangleMeshers2.javaMesher();
        TriangleMesherInput2 input = TriangleMesherInput2.from(build(scenario));

        long warmupEnd = System.nanoTime() + 5_000_000_000L;   /* 5 s JIT warmup */
        int meshes = 0;
        while (System.nanoTime() < warmupEnd) {
            mesher.mesh(input);
            meshes++;
        }
        System.out.println("warmup done: " + meshes + " meshes; profiling " + scenario
                + " for " + seconds + " s");

        long end = System.nanoTime() + seconds * 1_000_000_000L;
        meshes = 0;
        int triangles = 0;
        while (System.nanoTime() < end) {
            triangles = mesher.mesh(input).getTriangles().size();
            meshes++;
        }
        System.out.println("profiled: " + meshes + " meshes, " + triangles + " triangles each");
    }

    private static TriangleMesherInput build(String scenario) {
        switch (scenario) {
            case "cdt-1k":       return BenchmarkInputs.square(1_000, 0);
            case "cdt-10k":      return BenchmarkInputs.square(10_000, 0);
            case "cdt-50k":      return BenchmarkInputs.square(50_000, 0);
            case "cdt-grid-50k": return BenchmarkInputs.grid(224);
            case "qual-a1e-2":   return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-2);
            case "qual-a1e-3":   return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-3);
            case "qual-a1e-4":   return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-4);
            case "ref-hole-q33":
                return BenchmarkInputs.fromJson(Paths.get(
                        "src/bench/resources/inputs/regression/rectangle-solid-with-hole.json"));
            case "ref-circle-q33":
                return BenchmarkInputs.fromJson(Paths.get(
                        "src/bench/resources/inputs/regression/circle-256gon-r1.json"));
            case "ref-rings-q33":
                return BenchmarkInputs.fromJson(Paths.get(
                        "src/bench/resources/inputs/regression/circles-r0p51-r0p5.json"));
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }
}
