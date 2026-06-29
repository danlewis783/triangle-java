package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmark of the pure-Java mesher against the native (JNA) one across a
 * size sweep, with proper warmup, JVM forks and variance - the statistically
 * rigorous companion to the rough wall-clock {@link MesherBenchmark}.
 * <p>
 * The {@code scenario} sweep spans two paths at growing sizes so the cost shows
 * as a curve rather than a single small-mesh point:
 * <ul>
 *   <li>{@code cdt-*}: construction only (Delaunay + constrained recovery, no
 *       refinement) at growing input point counts;</li>
 *   <li>{@code qual-*}: the Ruppert refinement kernel at growing triangle counts
 *       (smaller max-area =&gt; more Steiner points). This path runs the hot
 *       coordinate-access loop the most, so it is where a vertex-store
 *       representation change is most visible.</li>
 * </ul>
 * Run all of it with {@code ./gradlew jmh}, or filter and tune, e.g.
 * {@code ./gradlew jmh --args="qual -f 1 -prof gc"}. The forked JVM is Java 8
 * (the artifact target); see the {@code jmh} task to measure on a newer one.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MesherJmh {

    @Param({"cdt-1k", "cdt-10k", "cdt-50k", "qual-a1e-2", "qual-a1e-3", "qual-a1e-4"})
    public String scenario;

    private TriangleMesher java;
    private TriangleMesher nat;
    private TriangleMesherInput input;

    @Setup(Level.Trial)
    public void setUp() {
        java = TriangleMeshers.javaMesher();
        nat = TriangleMeshers.nativeMesher();
        input = build(scenario);
    }

    private static TriangleMesherInput build(String scenario) {
        switch (scenario) {
            case "cdt-1k":     return BenchmarkInputs.square(1_000, 0);
            case "cdt-10k":    return BenchmarkInputs.square(10_000, 0);
            case "cdt-50k":    return BenchmarkInputs.square(50_000, 0);
            case "qual-a1e-2": return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-2);
            case "qual-a1e-3": return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-3);
            case "qual-a1e-4": return BenchmarkInputs.squareWithRegionArea(0, 20, 1e-4);
            default:           throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    @Benchmark
    public TriangleMesherOutput java() {
        return java.mesh(input);
    }

    @Benchmark
    public TriangleMesherOutput nativeMesher() {
        return nat.mesh(input);
    }
}
