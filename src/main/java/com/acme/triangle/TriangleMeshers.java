package com.acme.triangle;

import com.acme.triangle.impl.DifferentialTriangleMesher;
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

    /** Wrap a mesher so every output is checked against the structural contract. */
    public static TriangleMesher validating(TriangleMesher delegate) {
        return new ValidatingTriangleMesher(delegate);
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
        return new DifferentialTriangleMesher(primary, reference, handler);
    }

    /** A {@link DivergenceHandler} that throws {@link MeshContractException}. */
    public static DivergenceHandler throwingDivergenceHandler() {
        return (input, primaryViolations, referenceViolations) -> {
            throw new MeshContractException(
                    "primary mesher diverged from the contract", primaryViolations);
        };
    }
}
