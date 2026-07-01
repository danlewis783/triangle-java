package com.acme.triangle;

import com.acme.triangle.impl.CapturingTriangleMesher;
import com.acme.triangle.impl.DifferentialTriangleMesher;
import com.acme.triangle.impl.JavaTriangleMesher;
import com.acme.triangle.impl.NativeTriangleMesher;
import com.acme.triangle.impl.ValidatingTriangleMesher;

/**
 * Factory for {@link TriangleMesher} implementations and decorators. The
 * consumer depends only on this and {@link TriangleMesher}; the concrete
 * implementations stay in {@code com.acme.triangle.impl}.
 */
public final class TriangleMeshers {

    private TriangleMeshers() {
    }

    /** The native (JNA) Triangle implementation. */
    public static TriangleMesher nativeMesher() {
        return new NativeTriangleMesher();
    }

    /** The pure-Java implementation (constrained Delaunay + Ruppert refinement). */
    public static TriangleMesher javaMesher() {
        return new JavaTriangleMesher();
    }

    /** Wrap a mesher so every input can optionally be captured as JSON. */
    public static TriangleMesher capturing(TriangleMesher delegate, String mesherName) {
        return new CapturingTriangleMesher(asModelled(delegate), mesherName);
    }

    /** Wrap a mesher so every output is checked against the structural contract. */
    public static TriangleMesher validating(TriangleMesher delegate) {
        return new ValidatingTriangleMesher(asModelled(delegate));
    }

    /**
     * Run {@code primary} against {@code reference}, throwing if the primary
     * mesh violates the contract (strict shadow mode).
     */
    public static TriangleMesher differential(TriangleMesher primary,
                                              TriangleMesher reference) {
        return differential(primary, reference, throwingDivergenceHandler());
    }

    /** Run {@code primary} against {@code reference} with a custom divergence handler. */
    public static TriangleMesher differential(TriangleMesher primary,
                                              TriangleMesher reference,
                                              DivergenceHandler handler) {
        return new DifferentialTriangleMesher(asModelled(primary), asModelled(reference), handler);
    }

    /** A {@link DivergenceHandler} that throws {@link MeshContractException}. */
    public static DivergenceHandler throwingDivergenceHandler() {
        return (input, primaryViolations, referenceViolations) -> {
            throw new MeshContractException(
                    "primary mesher diverged from the contract", primaryViolations);
        };
    }

    /** View a flat mesher as the modelled {@link TriangleMesher2} the decorators
        wrap, converting at the boundary via the centralized DTO conversions. The
        decorators are modelled-native, so only the flat {@code nativeMesher} truly
        runs on flat arrays; wrapping it here just defers that conversion to it. */
    private static TriangleMesher2 asModelled(TriangleMesher mesher) {
        return input -> TriangleMesherOutput2.from(mesher.mesh(input.toFlat()));
    }
}
