package com.acme.triangle.impl;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.jspecify.annotations.Nullable;

/**
 * JNA mapping of C {@code struct triangulateio} (triangle.h). The field order
 * and set must match the C struct exactly, including fields this port does not
 * use ({@code normlist}, point/triangle attribute lists), so the memory layout
 * lines up. Array fields are {@link Pointer}s into native memory holding
 * {@code double} or {@code int} elements.
 */
@Structure.FieldOrder({
        "pointlist", "pointattributelist", "pointmarkerlist",
        "numberofpoints", "numberofpointattributes",
        "trianglelist", "triangleattributelist", "trianglearealist",
        "neighborlist", "numberoftriangles", "numberofcorners",
        "numberoftriangleattributes",
        "segmentlist", "segmentmarkerlist", "numberofsegments",
        "holelist", "numberofholes",
        "regionlist", "numberofregions",
        "edgelist", "edgemarkerlist", "normlist", "numberofedges"
})
public class TriangulateIO extends Structure {

    /* Array fields are native pointers JNA fills from the C struct; any of them
       may be null (absent input, unrequested output), so all are @Nullable. */
    public @Nullable Pointer pointlist;              /* in / out: REAL[2*N] */
    public @Nullable Pointer pointattributelist;     /* in / out: REAL[] */
    public @Nullable Pointer pointmarkerlist;        /* in / out: int[N] */
    public int numberofpoints;
    public int numberofpointattributes;

    public @Nullable Pointer trianglelist;           /* in / out: int[corners*T] */
    public @Nullable Pointer triangleattributelist;  /* in / out: REAL[] */
    public @Nullable Pointer trianglearealist;       /* in only:  REAL[T] */
    public @Nullable Pointer neighborlist;           /* out only: int[3*T] */
    public int numberoftriangles;
    public int numberofcorners;
    public int numberoftriangleattributes;

    public @Nullable Pointer segmentlist;            /* in / out: int[2*S] */
    public @Nullable Pointer segmentmarkerlist;      /* in / out: int[S] */
    public int numberofsegments;

    public @Nullable Pointer holelist;               /* in / copied out: REAL[2*H] */
    public int numberofholes;

    public @Nullable Pointer regionlist;             /* in / copied out: REAL[4*R] */
    public int numberofregions;

    public @Nullable Pointer edgelist;               /* out only */
    public @Nullable Pointer edgemarkerlist;         /* out only */
    public @Nullable Pointer normlist;               /* Voronoi only (unused) */
    public int numberofedges;
}
