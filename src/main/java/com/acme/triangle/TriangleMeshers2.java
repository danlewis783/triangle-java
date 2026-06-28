package com.acme.triangle;

import com.acme.triangle.impl.JavaTriangleMesher;

/**
 * Factory for {@link TriangleMesher2} implementations and decorators.
 */
public final class TriangleMeshers2 {

    private TriangleMeshers2() {
    }

    /**
     * The pure-Java implementation (constrained Delaunay + Ruppert refinement).
     */
    public static TriangleMesher2 javaMesher() {
        return new JavaTriangleMesher();
    }
}
