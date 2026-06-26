package com.acme.triangle.impl;

/**
 * A constraint subsegment in the refinement mesh ({@link IncrementalCdt}): its
 * current endpoints {@code (a, b)}, the boundary {@code marker}, and the
 * endpoints of the original input segment it descends from ({@code origOrg},
 * {@code origDest}). The original endpoints are carried as provenance across
 * midpoint splits, so the small-feature refinement rules can tell which input
 * feature a subsegment belongs to - in particular, recognize two subsegments
 * that meet at an original join vertex. Immutable: splitting a segment replaces
 * it with two new ones rather than editing it.
 */
final class Segment {

    final int a, b, marker, origOrg, origDest;

    Segment(int a, int b, int marker, int origOrg, int origDest) {
        this.a = a;
        this.b = b;
        this.marker = marker;
        this.origOrg = origOrg;
        this.origDest = origDest;
    }
}
