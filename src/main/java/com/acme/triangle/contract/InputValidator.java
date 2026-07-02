package com.acme.triangle.contract;

import com.acme.triangle.MeshInputException;
import com.acme.triangle.TriangleMesherInput;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural validation of a {@link TriangleMesherInput} - the input-side
 * counterpart of {@link MeshValidator}, run <em>before</em> meshing so a
 * malformed DTO fails fast with a named violation instead of surfacing as deep
 * geometric confusion (or, with the native mesher, a process abort). Checks are
 * purely structural and cheap relative to meshing:
 *
 * <ol>
 *   <li>counts agree with array lengths, and no count is negative;</li>
 *   <li>every coordinate is finite;</li>
 *   <li>no two points share exactly the same coordinates;</li>
 *   <li>segment endpoints are in range, no segment joins a vertex to itself,
 *       and no two segments join the same pair;</li>
 *   <li>the quality bound, if any, is satisfiable (&lt; 60&deg;).</li>
 * </ol>
 *
 * Geometric well-formedness beyond this (e.g. how segments may touch) is the
 * mesher's job; the T-junction and crossing handling in the pipeline copes with
 * vertices on segments and crossing segments by design.
 */
public final class InputValidator {

    private InputValidator() {
    }

    /** @return the list of input-contract violations; empty if the input is valid. */
    public static List<String> validate(TriangleMesherInput in) {
        List<String> v = new ArrayList<>();
        checkCounts(in, v);
        if (!v.isEmpty()) {
            return v;              /* array/count structure is broken: stop here */
        }
        checkCoordinates(in, v);
        checkDuplicatePoints(in, v);
        checkSegments(in, v);
        checkQuality(in, v);
        return v;
    }

    /** Throw {@link MeshInputException} if {@code in} violates the input contract. */
    public static void requireValid(TriangleMesherInput in) {
        List<String> violations = validate(in);
        if (!violations.isEmpty()) {
            throw new MeshInputException("invalid mesher input", violations);
        }
    }

    private static void checkCounts(TriangleMesherInput in, List<String> v) {
        if (in.numberOfPoints < 3) {
            v.add("points: numberOfPoints is " + in.numberOfPoints
                    + "; at least 3 points are required");
        }
        if (in.pointList == null) {
            v.add("points: pointList is null");
        } else if (in.pointList.length < 2 * in.numberOfPoints) {
            v.add("points: pointList holds " + in.pointList.length
                    + " values but numberOfPoints " + in.numberOfPoints
                    + " requires " + 2 * in.numberOfPoints);
        }
        if (in.numberOfSegments < 0) {
            v.add("segments: numberOfSegments is negative: " + in.numberOfSegments);
        } else if (in.numberOfSegments > 0) {
            if (in.segmentList == null) {
                v.add("segments: numberOfSegments is " + in.numberOfSegments
                        + " but segmentList is null");
            } else if (in.segmentList.length < 2 * in.numberOfSegments) {
                v.add("segments: segmentList holds " + in.segmentList.length
                        + " values but numberOfSegments " + in.numberOfSegments
                        + " requires " + 2 * in.numberOfSegments);
            }
            if (in.segmentMarkerList != null
                    && in.segmentMarkerList.length < in.numberOfSegments) {
                v.add("segments: segmentMarkerList holds " + in.segmentMarkerList.length
                        + " values but numberOfSegments is " + in.numberOfSegments);
            }
        }
        if (in.numberOfHoles < 0) {
            v.add("holes: numberOfHoles is negative: " + in.numberOfHoles);
        } else if (in.numberOfHoles > 0) {
            if (in.holeList == null) {
                v.add("holes: numberOfHoles is " + in.numberOfHoles
                        + " but holeList is null");
            } else if (in.holeList.length < 2 * in.numberOfHoles) {
                v.add("holes: holeList holds " + in.holeList.length
                        + " values but numberOfHoles " + in.numberOfHoles
                        + " requires " + 2 * in.numberOfHoles);
            }
        }
        if (in.numberOfRegions < 0) {
            v.add("regions: numberOfRegions is negative: " + in.numberOfRegions);
        } else if (in.numberOfRegions > 0) {
            if (in.regionList == null) {
                v.add("regions: numberOfRegions is " + in.numberOfRegions
                        + " but regionList is null");
            } else if (in.regionList.length < 4 * in.numberOfRegions) {
                v.add("regions: regionList holds " + in.regionList.length
                        + " values but numberOfRegions " + in.numberOfRegions
                        + " requires " + 4 * in.numberOfRegions);
            }
        }
    }

    private static void checkCoordinates(TriangleMesherInput in, List<String> v) {
        for (int i = 0; i < in.numberOfPoints; i++) {
            double x = in.pointList[2 * i];
            double y = in.pointList[2 * i + 1];
            if (!isFinite(x) || !isFinite(y)) {
                v.add("points: point " + i + " is (" + x + ", " + y
                        + "); coordinates must be finite");
            }
        }
        double[] holes = in.holeList;
        if (holes != null) {
            for (int i = 0; i < in.numberOfHoles; i++) {
                double x = holes[2 * i];
                double y = holes[2 * i + 1];
                if (!isFinite(x) || !isFinite(y)) {
                    v.add("holes: hole " + i + " is (" + x + ", " + y
                            + "); coordinates must be finite");
                }
            }
        }
        double[] regions = in.regionList;
        if (regions != null) {
            for (int i = 0; i < in.numberOfRegions; i++) {
                for (int j = 0; j < 4; j++) {
                    if (Double.isNaN(regions[4 * i + j])) {
                        v.add("regions: region " + i + " has NaN in (x, y, attribute,"
                                + " maxArea) = (" + regions[4 * i] + ", " + regions[4 * i + 1]
                                + ", " + regions[4 * i + 2] + ", " + regions[4 * i + 3] + ")");
                        break;
                    }
                }
            }
        }
    }

    private static void checkDuplicatePoints(TriangleMesherInput in, List<String> v) {
        /* Exactly-equal coordinates only: a duplicated vertex makes insertion
           degenerate (native Triangle silently merges; this library rejects).
           Detected by sorting the point indices by coordinates. */
        Integer[] order = new Integer[in.numberOfPoints];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> {
            int cx = Double.compare(in.pointList[2 * a], in.pointList[2 * b]);
            return cx != 0 ? cx : Double.compare(in.pointList[2 * a + 1], in.pointList[2 * b + 1]);
        });
        for (int k = 1; k < order.length; k++) {
            int i = order[k - 1];
            int j = order[k];
            if (in.pointList[2 * i] == in.pointList[2 * j]
                    && in.pointList[2 * i + 1] == in.pointList[2 * j + 1]) {
                v.add("points: point " + Math.max(i, j) + " duplicates point "
                        + Math.min(i, j) + " at (" + in.pointList[2 * i] + ", "
                        + in.pointList[2 * i + 1] + ")");
            }
        }
    }

    private static void checkSegments(TriangleMesherInput in, List<String> v) {
        int[] segs = in.segmentList;
        if (segs == null || in.numberOfSegments == 0) {
            return;
        }
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap(2 * in.numberOfSegments);
        seen.defaultReturnValue(-1);
        for (int s = 0; s < in.numberOfSegments; s++) {
            int a = segs[2 * s];
            int b = segs[2 * s + 1];
            if (a < 0 || a >= in.numberOfPoints || b < 0 || b >= in.numberOfPoints) {
                v.add("segments: segment " + s + " is (" + a + ", " + b
                        + ") but point indices must be in [0, " + in.numberOfPoints + ")");
                continue;
            }
            if (a == b) {
                v.add("segments: segment " + s + " joins vertex " + a + " to itself");
                continue;
            }
            long key = ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
            int first = seen.putIfAbsent(key, s);
            if (first >= 0) {
                v.add("segments: segment " + s + " duplicates segment " + first
                        + " (" + a + ", " + b + ")");
            }
        }
    }

    private static void checkQuality(TriangleMesherInput in, List<String> v) {
        double q = in.minAngleDegrees;
        if (Double.isNaN(q)) {
            v.add("quality: minAngleDegrees is NaN");
        } else if (q >= 60.0) {
            v.add("quality: minAngleDegrees " + q + " is unsatisfiable"
                    + " (no triangulation has minimum angle above 60 degrees)");
        }
    }

    private static boolean isFinite(double d) {
        return Double.isFinite(d);
    }
}
