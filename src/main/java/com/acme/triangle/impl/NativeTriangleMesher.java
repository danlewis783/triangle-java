package com.acme.triangle.impl;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TriangleMesher} backed by the native Triangle library via JNA.
 * <p>
 * Marshals the input arrays into native memory, calls {@code triangulate()}
 * with switches derived from the input, copies the output mesh back into a
 * {@link TriangleMesherOutput}, and frees the arrays Triangle allocated.
 * <p>
 * Calls are serialized: native Triangle uses process-global state (the exact
 * arithmetic constants and the random seed), so concurrent triangulation is not
 * safe. Note also that Triangle aborts the process on a fatal input error
 * rather than returning, so callers must supply valid geometry.
 */
public final class NativeTriangleMesher implements TriangleMesher {

    private static final Object LOCK = new Object();

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        TriangulateIO in = new TriangulateIO();
        TriangulateIO out = new TriangulateIO();
        List<Memory> owned = new ArrayList<>();
        try {
            in.pointlist = doubles(input.pointList, input.numberOfPoints * 2, owned);
            in.numberofpoints = input.numberOfPoints;
            if (input.numberOfSegments > 0) {
                in.segmentlist = ints(input.segmentList, input.numberOfSegments * 2, owned);
                in.numberofsegments = input.numberOfSegments;
                if (input.segmentMarkerList != null) {
                    in.segmentmarkerlist =
                            ints(input.segmentMarkerList, input.numberOfSegments, owned);
                }
            }
            if (input.numberOfHoles > 0) {
                in.holelist = doubles(input.holeList, input.numberOfHoles * 2, owned);
                in.numberofholes = input.numberOfHoles;
            }
            if (input.numberOfRegions > 0) {
                in.regionlist = doubles(input.regionList, input.numberOfRegions * 4, owned);
                in.numberofregions = input.numberOfRegions;
            }

            String switches = buildSwitches(input);
            synchronized (LOCK) {
                TriangleLibrary.INSTANCE.triangulate(switches, in, out, null);
            }

            TriangleMesherOutput result = readOutput(out);
            freeTriangleArrays(out);
            return result;
        } finally {
            for (Memory m : owned) {
                m.close();
            }
        }
    }

    private static String buildSwitches(TriangleMesherInput input) {
        StringBuilder sw = new StringBuilder("p");          /* PSLG */
        if (input.minAngleDegrees > 0) {
            sw.append('q').append(input.minAngleDegrees);   /* quality */
        }
        if (input.numberOfRegions > 0) {
            sw.append("Aa");        /* regional attributes + per-region max area */
        }
        sw.append('n');             /* neighbour list */
        sw.append('z');             /* zero-based indexing */
        if (input.quiet) {
            sw.append('Q');
        }
        return sw.toString();
    }

    private static TriangleMesherOutput readOutput(TriangulateIO out) {
        TriangleMesherOutput r = new TriangleMesherOutput();
        r.numberOfPoints = out.numberofpoints;
        r.numberOfTriangles = out.numberoftriangles;
        r.numberOfSegments = out.numberofsegments;

        r.pointList = out.pointlist != null
                ? out.pointlist.getDoubleArray(0, out.numberofpoints * 2)
                : new double[0];
        int corners = out.numberofcorners > 0 ? out.numberofcorners : 3;
        r.triangleList = out.trianglelist != null
                ? out.trianglelist.getIntArray(0, out.numberoftriangles * corners)
                : new int[0];

        if (out.numberoftriangleattributes > 0 && out.triangleattributelist != null) {
            int na = out.numberoftriangleattributes;
            double[] all = out.triangleattributelist
                    .getDoubleArray(0, out.numberoftriangles * na);
            double[] first = new double[out.numberoftriangles];
            for (int i = 0; i < out.numberoftriangles; i++) {
                first[i] = all[i * na];
            }
            r.triangleAttributeList = first;
        }
        if (out.neighborlist != null) {
            r.neighborList = out.neighborlist.getIntArray(0, out.numberoftriangles * 3);
        }
        if (out.segmentlist != null) {
            r.segmentList = out.segmentlist.getIntArray(0, out.numberofsegments * 2);
        }
        if (out.segmentmarkerlist != null) {
            r.segmentMarkerList = out.segmentmarkerlist.getIntArray(0, out.numberofsegments);
        }
        return r;
    }

    /** Free only the arrays Triangle allocated; holelist/regionlist alias input. */
    private static void freeTriangleArrays(TriangulateIO out) {
        Pointer[] allocated = {
                out.pointlist, out.pointattributelist, out.pointmarkerlist,
                out.trianglelist, out.triangleattributelist, out.neighborlist,
                out.segmentlist, out.segmentmarkerlist, out.edgelist, out.edgemarkerlist
        };
        for (Pointer p : allocated) {
            if (p != null) {
                TriangleLibrary.INSTANCE.trifree(p);
            }
        }
    }

    private static Memory doubles(double[] data, int count, List<Memory> owned) {
        Memory m = new Memory((long) count * Double.BYTES);
        m.write(0, data, 0, count);
        owned.add(m);
        return m;
    }

    private static Memory ints(int[] data, int count, List<Memory> owned) {
        Memory m = new Memory((long) count * Integer.BYTES);
        m.write(0, data, 0, count);
        owned.add(m);
        return m;
    }
}
