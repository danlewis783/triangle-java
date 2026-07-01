package com.acme.triangle;

import com.acme.triangle.impl.CapturingTriangleMesher;
import com.acme.triangle.impl.DifferentialTriangleMesher;
import com.acme.triangle.impl.JavaTriangleMesher;
import com.acme.triangle.impl.NativeTriangleMesher;
import com.acme.triangle.impl.ValidatingTriangleMesher;

/**
 * Factory for {@link TriangleMesher2} implementations and decorators - the
 * modelled-type counterpart to {@link TriangleMeshers}. Each mesher is the same
 * concrete implementation, exposed here through {@link TriangleMesher2}. The
 * decorators are the flat implementations adapted at their boundary via the
 * centralized {@link TriangleMesherInput2}/{@link TriangleMesherOutput2}
 * conversions, so a modelled delegate is bridged to the flat form they wrap.
 */
public final class TriangleMeshers2 {

    private TriangleMeshers2() {
    }

    /** The native (JNA) Triangle implementation. */
    public static TriangleMesher2 nativeMesher() {
        return new NativeTriangleMesher();
    }

    /** The pure-Java implementation (constrained Delaunay + Ruppert refinement). */
    public static TriangleMesher2 javaMesher() {
        return new JavaTriangleMesher();
    }

    /** Wrap a mesher so every input can optionally be captured as JSON. */
    public static TriangleMesher2 capturing(TriangleMesher2 delegate, String mesherName) {
        return new CapturingTriangleMesher(asFlat(delegate), mesherName);
    }

    /** Wrap a mesher so every output is checked against the structural contract. */
    public static TriangleMesher2 validating(TriangleMesher2 delegate) {
        return new ValidatingTriangleMesher(asFlat(delegate));
    }

    /**
     * Run {@code primary} against {@code reference}, throwing if the primary
     * mesh violates the contract (strict shadow mode).
     */
    public static TriangleMesher2 differential(TriangleMesher2 primary,
                                               TriangleMesher2 reference) {
        return differential(primary, reference, throwingDivergenceHandler());
    }

    /** Run {@code primary} against {@code reference} with a custom divergence handler. */
    public static TriangleMesher2 differential(TriangleMesher2 primary,
                                               TriangleMesher2 reference,
                                               DivergenceHandler handler) {
        return new DifferentialTriangleMesher(asFlat(primary), asFlat(reference), handler);
    }

    /** A {@link DivergenceHandler} that throws {@link MeshContractException}. */
    public static DivergenceHandler throwingDivergenceHandler() {
        return TriangleMeshers.throwingDivergenceHandler();
    }

    /** View a modelled mesher as the flat {@link TriangleMesher} the decorators
        wrap, converting at the boundary via the centralized DTO conversions. */
    private static TriangleMesher asFlat(TriangleMesher2 mesher) {
        return input -> mesher.mesh(TriangleMesherInput2.from(input)).toFlat();
    }
}
