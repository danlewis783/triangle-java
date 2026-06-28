package com.acme.triangle;

/**
 * An input constraint edge of the PSLG during constrained-Delaunay construction:
 * endpoints {@code (a, b)} as point indices plus a boundary {@code marker}.
 * Immutable - splitting a constraint (at a crossing or a T-junction) replaces it
 * with new ones. The lean, construction-phase counterpart to the refinement
 * {@code Segment}, which additionally tracks original-segment provenance.
 */
public final class Constraint {

    private final int a;
    private final int b;
    private final int marker;

    public Constraint(int a, int b, int marker) {
        this.a = a;
        this.b = b;
        this.marker = marker;
    }

    public int getA() {
        return a;
    }

    public int getB() {
        return b;
    }

    public int getMarker() {
        return marker;
    }
}
