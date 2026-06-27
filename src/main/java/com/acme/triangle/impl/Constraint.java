package com.acme.triangle.impl;

/**
 * An input constraint edge of the PSLG during constrained-Delaunay construction:
 * endpoints {@code (a, b)} as point indices plus a boundary {@code marker}.
 * Immutable - splitting a constraint (at a crossing or a T-junction) replaces it
 * with new ones. The lean, construction-phase counterpart to the refinement
 * {@link Segment}, which additionally tracks original-segment provenance.
 */
final class Constraint {

    final int a, b, marker;

    Constraint(int a, int b, int marker) {
        this.a = a;
        this.b = b;
        this.marker = marker;
    }
}
