package com.acme.triangle.contract;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.predicate.Predicates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a mesh satisfies the structural contract the consumer depends on
 * - not byte-for-byte identity with any particular triangulator. This is the
 * acceptance bar for any {@link com.acme.triangle.TriangleMesher}
 * implementation: produce <em>a</em> valid mesh meeting these invariants.
 *
 * <p>Invariants:
 * <ol>
 *   <li>topological validity - manifold mesh, non-degenerate triangles with
 *       consistent orientation, indices in range;</li>
 *   <li>neighbour-slot semantics - {@code neighbor[i][j]} is across the edge
 *       opposite corner {@code j}; adjacency symmetric;</li>
 *   <li>constrained Delaunay - every interior non-segment edge is locally
 *       Delaunay (empty circumcircle);</li>
 *   <li>segment recovery - every output segment is a real mesh edge;</li>
 *   <li>holes &amp; regions - no triangle covers an input hole point, and the
 *       region attribute is constant across every non-segment interior edge;</li>
 *   <li>quality - minimum triangle angle &ge; the requested bound.</li>
 * </ol>
 *
 * <p>Geometry uses the robust {@link Predicates}, so the checks themselves are
 * exact. Dual-use: the validating/differential decorators call this at runtime;
 * the tests call it as the acceptance oracle.
 */
public final class MeshValidator {

    private static final double ANGLE_TOLERANCE_DEG = 0.05;

    private MeshValidator() {
    }

    /** @return the list of contract violations; empty if the mesh is valid. */
    public static List<String> validate(TriangleMesherOutput o,
                                        TriangleMesherInput in) {
        List<String> v = new ArrayList<>();
        checkTopology(o, v);
        checkNeighbors(o, v);
        Set<Long> segments = segmentSet(o);
        checkDelaunay(o, segments, v);
        checkSegmentsAreEdges(o, v);
        checkHoles(o, in, v);
        checkRegions(o, segments, v);
        checkQuality(o, in, v);
        return v;
    }

    public static boolean isValid(TriangleMesherOutput o, TriangleMesherInput in) {
        return validate(o, in).isEmpty();
    }

    /* --- helpers ----------------------------------------------------------- */

    private static int corner(TriangleMesherOutput o, int tri, int k) {
        return o.triangleList[tri * 3 + k];
    }

    private static long edgeKey(int u, int v) {
        int lo = Math.min(u, v), hi = Math.max(u, v);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static boolean triHas(TriangleMesherOutput o, int tri, int v) {
        return corner(o, tri, 0) == v || corner(o, tri, 1) == v
                || corner(o, tri, 2) == v;
    }

    private static int apexOf(TriangleMesherOutput o, int tri, int u, int v) {
        for (int k = 0; k < 3; k++) {
            int c = corner(o, tri, k);
            if (c != u && c != v) {
                return c;
            }
        }
        return -1;
    }

    private static Set<Long> segmentSet(TriangleMesherOutput o) {
        Set<Long> s = new HashSet<>();
        if (o.segmentList != null) {
            for (int i = 0; i < o.numberOfSegments; i++) {
                s.add(edgeKey(o.segmentList[2 * i], o.segmentList[2 * i + 1]));
            }
        }
        return s;
    }

    /* --- 1. topology ------------------------------------------------------- */

    private static void checkTopology(TriangleMesherOutput o, List<String> v) {
        int nt = o.numberOfTriangles, np = o.numberOfPoints;
        int orient = 0;
        Map<Long, Integer> edgeCount = new HashMap<>();
        for (int i = 0; i < nt; i++) {
            int a = corner(o, i, 0), b = corner(o, i, 1), c = corner(o, i, 2);
            if (outOfRange(a, np) || outOfRange(b, np) || outOfRange(c, np)) {
                v.add("topology: tri " + i + " has an out-of-range corner");
                continue;
            }
            if (a == b || b == c || a == c) {
                v.add("topology: tri " + i + " has a repeated corner");
                continue;
            }
            int s = Predicates.orient2d(x(o, a), y(o, a), x(o, b), y(o, b),
                    x(o, c), y(o, c));
            if (s == 0) {
                v.add("topology: tri " + i + " is degenerate");
            } else if (orient == 0) {
                orient = s;
            } else if (s != orient) {
                v.add("topology: tri " + i + " has inconsistent orientation");
            }
            for (int k = 0; k < 3; k++) {
                long e = edgeKey(corner(o, i, k), corner(o, i, (k + 1) % 3));
                edgeCount.merge(e, 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> e : edgeCount.entrySet()) {
            int count = e.getValue();
            if (count != 1 && count != 2) {
                v.add("topology: edge (" + (int) (e.getKey() >> 32) + ","
                        + (int) (long) e.getKey() + ") shared by " + count
                        + " triangles");
            }
        }
    }

    /* --- 2. neighbour-slot semantics --------------------------------------- */

    private static void checkNeighbors(TriangleMesherOutput o, List<String> v) {
        if (o.neighborList == null) {
            v.add("neighbors: no neighbour list produced");
            return;
        }
        int nt = o.numberOfTriangles;
        for (int i = 0; i < nt; i++) {
            for (int j = 0; j < 3; j++) {
                int n = o.neighborList[i * 3 + j];
                if (n == -1) {
                    continue;
                }
                if (n < 0 || n >= nt) {
                    v.add("neighbors: tri " + i + " slot " + j + " -> " + n
                            + " out of range");
                    continue;
                }
                int u = corner(o, i, (j + 1) % 3);
                int w = corner(o, i, (j + 2) % 3);
                if (!triHas(o, n, u) || !triHas(o, n, w)) {
                    v.add("neighbors: tri " + i + " slot " + j + " -> " + n
                            + " not opposite corner " + j);
                    continue;
                }
                int found = 0;
                for (int k = 0; k < 3; k++) {
                    if (o.neighborList[n * 3 + k] == i) {
                        int u2 = corner(o, n, (k + 1) % 3);
                        int w2 = corner(o, n, (k + 2) % 3);
                        found++;
                        if (!((u2 == u && w2 == w) || (u2 == w && w2 == u))) {
                            v.add("neighbors: tri " + i + "<->" + n
                                    + " edge mismatch");
                        }
                    }
                }
                if (found != 1) {
                    v.add("neighbors: tri " + i + " -> " + n
                            + " not reciprocated (" + found + ")");
                }
            }
        }
    }

    /* --- 3. constrained Delaunay ------------------------------------------- */

    private static void checkDelaunay(TriangleMesherOutput o, Set<Long> segments,
                                      List<String> v) {
        if (o.neighborList == null) {
            return;
        }
        int nt = o.numberOfTriangles;
        for (int i = 0; i < nt; i++) {
            for (int j = 0; j < 3; j++) {
                int n = o.neighborList[i * 3 + j];
                if (n == -1 || n < i) {
                    continue;
                }
                int u = corner(o, i, (j + 1) % 3);
                int w = corner(o, i, (j + 2) % 3);
                if (segments.contains(edgeKey(u, w))) {
                    continue;
                }
                int ap = apexOf(o, n, u, w);
                if (ap < 0) {
                    continue;
                }
                int a = corner(o, i, 0), b = corner(o, i, 1), c = corner(o, i, 2);
                if (Predicates.incircle(x(o, a), y(o, a), x(o, b), y(o, b),
                        x(o, c), y(o, c), x(o, ap), y(o, ap)) > 0) {
                    v.add("delaunay: edge (" + u + "," + w
                            + ") not locally Delaunay (apex " + ap + " inside tri "
                            + i + ")");
                }
            }
        }
    }

    /* --- 4. segment recovery ----------------------------------------------- */

    private static void checkSegmentsAreEdges(TriangleMesherOutput o,
                                              List<String> v) {
        if (o.segmentList == null) {
            return;
        }
        Set<Long> meshEdges = new HashSet<>();
        for (int i = 0; i < o.numberOfTriangles; i++) {
            for (int k = 0; k < 3; k++) {
                meshEdges.add(edgeKey(corner(o, i, k), corner(o, i, (k + 1) % 3)));
            }
        }
        for (int i = 0; i < o.numberOfSegments; i++) {
            int u = o.segmentList[2 * i], w = o.segmentList[2 * i + 1];
            if (!meshEdges.contains(edgeKey(u, w))) {
                v.add("segments: segment (" + u + "," + w + ") is not a mesh edge");
            }
        }
    }

    /* --- 5a. holes --------------------------------------------------------- */

    private static void checkHoles(TriangleMesherOutput o, TriangleMesherInput in,
                                   List<String> v) {
        if (in == null || in.holeList == null) {
            return;
        }
        for (int h = 0; h < in.numberOfHoles; h++) {
            double hx = in.holeList[2 * h], hy = in.holeList[2 * h + 1];
            for (int i = 0; i < o.numberOfTriangles; i++) {
                int a = corner(o, i, 0), b = corner(o, i, 1), c = corner(o, i, 2);
                int s1 = Predicates.orient2d(x(o, a), y(o, a), x(o, b), y(o, b), hx, hy);
                int s2 = Predicates.orient2d(x(o, b), y(o, b), x(o, c), y(o, c), hx, hy);
                int s3 = Predicates.orient2d(x(o, c), y(o, c), x(o, a), y(o, a), hx, hy);
                if (s1 != 0 && s1 == s2 && s2 == s3) {
                    v.add("holes: hole point (" + hx + "," + hy + ") is inside tri "
                            + i);
                }
            }
        }
    }

    /* --- 5b. regions ------------------------------------------------------- */

    private static void checkRegions(TriangleMesherOutput o, Set<Long> segments,
                                     List<String> v) {
        if (o.triangleAttributeList == null || o.triangleAttributeList.length == 0
                || o.neighborList == null) {
            return;
        }
        int nt = o.numberOfTriangles;
        for (int i = 0; i < nt; i++) {
            for (int j = 0; j < 3; j++) {
                int n = o.neighborList[i * 3 + j];
                if (n == -1 || n < i) {
                    continue;
                }
                int u = corner(o, i, (j + 1) % 3);
                int w = corner(o, i, (j + 2) % 3);
                if (segments.contains(edgeKey(u, w))) {
                    continue;
                }
                if (o.triangleAttributeList[i] != o.triangleAttributeList[n]) {
                    v.add("regions: attribute differs across non-segment edge (tri "
                            + i + " vs " + n + ")");
                }
            }
        }
    }

    /* --- 6. quality -------------------------------------------------------- */

    private static void checkQuality(TriangleMesherOutput o, TriangleMesherInput in,
                                     List<String> v) {
        if (in == null || in.minAngleDegrees <= 0) {
            return;
        }
        double bound = in.minAngleDegrees;
        for (int i = 0; i < o.numberOfTriangles; i++) {
            int a = corner(o, i, 0), b = corner(o, i, 1), c = corner(o, i, 2);
            double minAngle = minAngleDeg(x(o, a), y(o, a), x(o, b), y(o, b),
                    x(o, c), y(o, c));
            if (minAngle < bound - ANGLE_TOLERANCE_DEG) {
                v.add("quality: tri " + i + " min angle " + minAngle + " < " + bound);
            }
        }
    }

    private static double minAngleDeg(double ax, double ay, double bx, double by,
                                      double cx, double cy) {
        double[] px = {ax, bx, cx}, py = {ay, by, cy};
        double best = 180.0;
        for (int k = 0; k < 3; k++) {
            int q = (k + 1) % 3, r = (k + 2) % 3;
            double ux = px[q] - px[k], uy = py[q] - py[k];
            double wx = px[r] - px[k], wy = py[r] - py[k];
            double lu = Math.hypot(ux, uy), lw = Math.hypot(wx, wy);
            if (lu == 0 || lw == 0) {
                return 0;
            }
            double cs = (ux * wx + uy * wy) / (lu * lw);
            cs = Math.max(-1.0, Math.min(1.0, cs));
            best = Math.min(best, Math.toDegrees(Math.acos(cs)));
        }
        return best;
    }

    private static boolean outOfRange(int idx, int n) {
        return idx < 0 || idx >= n;
    }

    private static double x(TriangleMesherOutput o, int v) {
        return o.pointList[2 * v];
    }

    private static double y(TriangleMesherOutput o, int v) {
        return o.pointList[2 * v + 1];
    }
}
