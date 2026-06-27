package com.acme.triangle.impl;

/**
 * A mesh vertex's provenance, needed by the small-feature refinement rules: its
 * {@link VertexType kind} and, for a vertex created by splitting a segment, the
 * endpoints {@code (origOrg, origDest)} of the original input segment it lies on
 * - so the rules can tell whether two subsegment vertices descend from the same
 * input feature or from two features meeting at a join. {@code origOrg} and
 * {@code origDest} are {@code -1} for non-segment vertices.
 * <p>
 * A vertex's <em>position</em> is deliberately not modelled here. Coordinates are
 * a shared geometric primitive: the same {@code double[]} pair form serves
 * transient points (circumcentres, off-centres) that are locations, not
 * vertices, and is fed to the robust predicates as raw doubles. Folding it in
 * would conflate location with identity and force a copy on the hot path, so the
 * parallel {@code points} store keeps the coordinates and this type carries only
 * the identity attributes. Immutable.
 */
final class Provenance {

    final VertexType type;
    final int origOrg, origDest;

    Provenance(VertexType type, int origOrg, int origDest) {
        this.type = type;
        this.origOrg = origOrg;
        this.origDest = origDest;
    }
}
