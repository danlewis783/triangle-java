package com.acme.triangle.impl;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

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

    public Pointer pointlist;              /* in / out: REAL[2*N] */
    public Pointer pointattributelist;     /* in / out: REAL[] */
    public Pointer pointmarkerlist;        /* in / out: int[N] */
    public int numberofpoints;
    public int numberofpointattributes;

    public Pointer trianglelist;           /* in / out: int[corners*T] */
    public Pointer triangleattributelist;  /* in / out: REAL[] */
    public Pointer trianglearealist;       /* in only:  REAL[T] */
    public Pointer neighborlist;           /* out only: int[3*T] */
    public int numberoftriangles;
    public int numberofcorners;
    public int numberoftriangleattributes;

    public Pointer segmentlist;            /* in / out: int[2*S] */
    public Pointer segmentmarkerlist;      /* in / out: int[S] */
    public int numberofsegments;

    public Pointer holelist;               /* in / copied out: REAL[2*H] */
    public int numberofholes;

    public Pointer regionlist;             /* in / copied out: REAL[4*R] */
    public int numberofregions;

    public Pointer edgelist;               /* out only */
    public Pointer edgemarkerlist;         /* out only */
    public Pointer normlist;               /* Voronoi only (unused) */
    public int numberofedges;
}
