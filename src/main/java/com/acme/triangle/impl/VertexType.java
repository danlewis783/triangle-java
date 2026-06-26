package com.acme.triangle.impl;

/**
 * What a mesh vertex was created from - its provenance kind, needed by the
 * small-feature refinement rules. Carried (with the original segment, where
 * applicable) by {@link Provenance}.
 */
enum VertexType {
    /** An original input vertex (segment joins). */
    INPUT,
    /** Created by splitting a segment (on its interior). */
    SEGMENT,
    /** An interior Steiner point. */
    FREE
}
