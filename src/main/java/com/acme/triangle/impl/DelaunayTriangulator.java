package com.acme.triangle.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.acme.triangle.Point;
import com.acme.triangle.Triangle;
import com.acme.triangle.predicate.Predicates;
import org.jspecify.annotations.Nullable;

/**
 * Delaunay triangulation of a point set by incremental Bowyer-Watson insertion
 * with maintained adjacency, walking point location, and Hilbert-curve insertion
 * order - O(n log n) expected rather than the naive O(n^2).
 * <p>
 * Phase 1 of the pure-Java port. Each point's cavity (the triangles whose
 * circumcircle contains it) is found by locating its containing triangle with a
 * remembering walk from the previously inserted triangle, then flooding outward
 * across maintained neighbour links - O(cavity), never a scan of the whole mesh
 * (the original simple version was O(n^2)). Inserting the points in Hilbert-curve
 * order keeps consecutive points spatially close, so each walk is short. The
 * robust {@link Geometry} predicates make the incircle and orientation tests
 * exact, so the result is a genuine Delaunay triangulation.
 * <p>
 * Triangles are returned counterclockwise as {@link Corners} - the
 * construction-phase corner triples the consumer
 * ({@link ConstrainedDelaunayTriangulator}) recovers constraints into. Adjacency
 * is maintained internally only to drive the local cavity walk; the result hands
 * back just the corners.
 */
public final class DelaunayTriangulator {

    private DelaunayTriangulator() {
    }

    /**
     * @param points the input vertex store; indices {@code 0..size-1} address it
     * @return the Delaunay triangles as CCW {@link Corners} over the input
     *         points (a fresh, mutable list the caller may refine in place)
     */
    public static List<Corners> triangulate(List<Point> points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("need at least 3 points");
        }
        return new Builder(points).build();
    }

    /**
     * The incremental triangulation in progress: a triangle arena ({@link
     * Triangle} slots carrying their own neighbour links, dead slots nulled and
     * reused) over a working {@code List<Point>} that is the input plus three
     * super-triangle vertices enclosing it. The {@code attr} field of each
     * {@link Triangle} is unused here (phase 1 has no regions).
     */
    private static final class Builder {

        private static final int HILBERT_BITS = 16;

        private double[] xy;                              /* interleaved coords: vertex i at (xy[2i], xy[2i+1]); input points + super-triangle */
        private int size;                                 /* live vertex count in xy */
        private final int n;                              /* number of input points (before super-triangle) */
        private final List<@Nullable Triangle> tris = new ArrayList<>();
        private final Deque<Integer> free = new ArrayDeque<>();
        private int[] cavityGen;                          /* gen-stamped cavity membership */
        private int gen;
        private int recent;                               /* a live triangle to start walks from */

        /* Generation-stamped fan-linking scratch (vertex -> the new fan triangle
           seeing it as u/w) - the vertex count is fixed at n+3, so these are
           allocated once; replaces two boxed HashMaps per insertion. */
        private final int[] fanAsU;
        private final int[] fanAsUGen;
        private final int[] fanAsW;
        private final int[] fanAsWGen;

        Builder(List<Point> pts) {
            this.n = pts.size();                     /* input count, before the super-triangle vertices */
            /* Read the input coordinates once into a flat interleaved array. The hot
               inCircle/orient tests then load a primitive array (xy[2i]) rather than
               chasing a List<Point> reference and two field loads per corner. Three
               super-triangle vertices are appended below and no others are ever added
               (the initial Delaunay inserts only existing input points), so this is
               sized exactly for n+3 and never grows. Internal only: the triangulate()
               contract still speaks List<Point>. */
            this.xy = new double[2 * (n + 3)];
            for (int i = 0; i < n; i++) {
                Point p = pts.get(i);
                xy[2 * i] = p.getX();
                xy[2 * i + 1] = p.getY();
            }
            this.size = n;
            cavityGen = new int[16];
            fanAsU = new int[n + 3];
            fanAsUGen = new int[n + 3];
            fanAsW = new int[n + 3];
            fanAsWGen = new int[n + 3];
            recent = initSuperTriangle();
        }

        private double x(int i) {
            return xy[2 * i];
        }

        private double y(int i) {
            return xy[2 * i + 1];
        }

        /**
         * Append three super-triangle vertices enclosing the input and seed the
         * arena with that single triangle.
         *
         * @return slot id
         */
        private int initSuperTriangle() {
            double minx = x(0);
            double miny = y(0);
            double maxx = minx;
            double maxy = miny;
            for (int i = 1; i < n; i++) {
                double ix = x(i);
                double iy = y(i);
                minx = Math.min(minx, ix);
                maxx = Math.max(maxx, ix);
                miny = Math.min(miny, iy);
                maxy = Math.max(maxy, iy);
            }
            double d = Math.max(maxx - minx, maxy - miny);
            if (d <= 0) {
                d = 1;
            }
            double m = 1000 * d;
            double cx = (minx + maxx) / 2;
            double cy = (miny + maxy) / 2;

            /* (sa, sb, sc) is CCW */
            int sa = add(cx - m, cy - m);
            int sb = add(cx + m, cy - m);
            int sc = add(cx, cy + m);

            Triangle ccw = new Triangle(sa, sb, sc, -1, -1, -1, 0.0);

            //noinspection UnnecessaryLocalVariable
            int result = allocSlot(ccw);

            return result;
        }

        /** Append a vertex, returning its index. */
        private int add(double px, double py) {
            int i = size++;
            xy[2 * i] = px;
            xy[2 * i + 1] = py;
            return i;
        }

        List<Corners> build() {
            int[] order = hilbertOrder();
            for (int k = 0; k < n; k++) {
                insert(order[k]);
            }
            /* Keep the triangles not incident to a super-triangle vertex. */
            List<Corners> kept = new ArrayList<>();
            for (Triangle t : tris) {
                if (t != null && t.getA() < n && t.getB() < n && t.getC() < n) {
                    kept.add(new Corners(t.getA(), t.getB(), t.getC()));
                }
            }
            return kept;
        }

        /** Insert input vertex {@code p}: locate its containing triangle, gather
            the cavity by flooding across neighbour links, and re-fan it around p. */
        private void insert(int p) {
            double px = x(p), py = y(p);
            int start = locate(px, py);
            gen++;
            List<Integer> cavity = new ArrayList<>();
            List<int[]> fan = new ArrayList<>();          /* boundary edges {u, w, outerNeighbour} */
            Deque<Integer> stack = new ArrayDeque<>();
            cavityGen[start] = gen;
            stack.push(start);
            while (!stack.isEmpty()) {
                int t = stack.pop();
                cavity.add(t);
                Triangle tc = tris.get(t);
                for (int j = 0; j < 3; j++) {
                    int nb = tc.neighbor(j);
                    int u = tc.corner((j + 1) % 3), w = tc.corner((j + 2) % 3);
                    if (nb >= 0 && cavityGen[nb] == gen) {
                        continue;                         /* shared interior cavity edge */
                    }
                    if (nb >= 0 && inCircle(tris.get(nb), px, py)) {
                        cavityGen[nb] = gen;
                        stack.push(nb);                   /* nb joins the cavity */
                    } else {
                        fan.add(new int[]{u, w, nb});     /* (u,w) is a cavity-boundary edge */
                    }
                }
            }
            for (int t : cavity) {
                freeSlot(t);
            }
            recent = refan(p, fan);
        }

        /**
         * Re-fan the cavity boundary {@code fan} around new vertex {@code p},
         * relinking adjacency locally; returns one of the new triangle slots. Each
         * new triangle is {@code {u, w, p}} with {@code p} at corner 2, so the
         * boundary edge {@code (u,w)} is opposite corner 2 (slot 2 -&gt; the outer
         * neighbour) and the interior fan edges are {@code (p,u)} at slot 1 and
         * {@code (w,p)} at slot 0; adjacent fan triangles share a boundary vertex,
         * once as a {@code u} and once as a {@code w}, and are linked then.
         */
        private int refan(int p, List<int[]> fan) {
            int last = -1;
            for (int[] f : fan) {
                int u = f[0], w = f[1], nb = f[2];
                int id = allocSlot(new Triangle(u, w, p, -1, -1, nb, 0.0));
                last = id;
                if (nb >= 0) {                            /* repoint the outer ring */
                    Triangle nc = tris.get(nb);
                    for (int k = 0; k < 3; k++) {
                        if (nc.corner(k) != u && nc.corner(k) != w) {
                            nc.setNeighbor(k, id);
                            break;
                        }
                    }
                }
                if (fanAsWGen[u] == gen) {                /* shares the (p,u) edge */
                    int mu = fanAsW[u];
                    tris.get(id).setN1(mu);
                    tris.get(mu).setN0(id);
                }
                fanAsU[u] = id;
                fanAsUGen[u] = gen;
                if (fanAsUGen[w] == gen) {                /* shares the (w,p) edge */
                    int mw = fanAsU[w];
                    tris.get(id).setN0(mw);
                    tris.get(mw).setN1(id);
                }
                fanAsW[w] = id;
                fanAsWGen[w] = gen;
            }
            return last;
        }

        /**
         * The triangle containing {@code pt}, by a remembering walk from {@link
         * #recent}: step across the edge {@code pt} lies outside of, never
         * immediately back the way we came. Falls back to a scan if the walk
         * stalls (defensive against a degenerate cycle).
         */
        private int locate(double px, double py) {
            int t = recent, prev = -1;
            int steps = 0, limit = tris.size() + 8;
            while (steps++ <= limit) {
                Triangle tc = tris.get(t);
                int next = -1;
                for (int j = 0; j < 3; j++) {
                    int nb = tc.neighbor(j);
                    if (nb < 0 || nb == prev) {
                        continue;
                    }
                    int u = tc.corner((j + 1) % 3), w = tc.corner((j + 2) % 3);
                    if (orient(u, w, px, py) < 0) {       /* pt is outside edge (u,w) */
                        next = nb;
                        break;
                    }
                }
                if (next < 0) {
                    return t;                             /* pt is inside t */
                }
                prev = t;
                t = next;
            }
            return locateByScan(px, py);
        }

        private int locateByScan(double px, double py) {
            for (int i = 0; i < tris.size(); i++) {
                Triangle t = tris.get(i);
                if (t != null && orient(t.getA(), t.getB(), px, py) >= 0
                        && orient(t.getB(), t.getC(), px, py) >= 0 && orient(t.getC(), t.getA(), px, py) >= 0) {
                    return i;
                }
            }
            return recent;                                /* unreachable for a point inside the hull */
        }

        /* --- triangle arena -------------------------------------------------- */

        private int allocSlot(Triangle t) {
            int id;
            if (!free.isEmpty()) {
                id = free.pop();
                tris.set(id, t);
            } else {
                id = tris.size();
                tris.add(t);
                ensureCavityGen(id + 1);
            }
            return id;
        }

        private void freeSlot(int id) {
            tris.set(id, null);
            free.push(id);
        }

        private void ensureCavityGen(int size) {
            if (cavityGen.length < size) {
                cavityGen = Arrays.copyOf(cavityGen, Math.max(size, cavityGen.length * 2));
            }
        }

        /* Predicates read the flat coordinate array directly (no List<Point> access,
           no per-call Point allocation for the loose insertion point), routing to the
           same robust kernel Geometry uses. */
        private boolean inCircle(Triangle t, double px, double py) {
            int a = t.getA(), b = t.getB(), c = t.getC();
            return Predicates.incircle(
                    xy[2 * a], xy[2 * a + 1],
                    xy[2 * b], xy[2 * b + 1],
                    xy[2 * c], xy[2 * c + 1],
                    px, py) > 0;
        }

        private int orient(int a, int b, double px, double py) {
            return Predicates.orient2d(xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1], px, py);
        }

        /* --- Hilbert-curve insertion order ----------------------------------- */

        /** The input point indices ordered along a Hilbert curve, so consecutive
            insertions are spatially close and each location walk is short. */
        private int[] hilbertOrder() {
            double minx = x(0);
            double miny = y(0);
            double maxx = minx;
            double maxy = miny;

            for (int i = 1; i < n; i++) {
                double ix = x(i);
                double iy = y(i);
                minx = Math.min(minx, ix);
                maxx = Math.max(maxx, ix);
                miny = Math.min(miny, iy);
                maxy = Math.max(maxy, iy);
            }
            int side = 1 << HILBERT_BITS;
            double rx = maxx > minx ? (side - 1) / (maxx - minx) : 0.0;
            double ry = maxy > miny ? (side - 1) / (maxy - miny) : 0.0;
            /* Pack each point's Hilbert distance (the multiple-of-n high part) with
               its index (the low part) into one long, so a primitive sort orders by
               curve position and the index is recovered modulo n. */
            long[] packed = new long[n];
            for (int i = 0; i < n; i++) {
                int qx = (int) ((x(i) - minx) * rx);
                int qy = (int) ((y(i) - miny) * ry);
                packed[i] = hilbertDistance(side, qx, qy) * (long) n + i;
            }
            Arrays.sort(packed);
            int[] order = new int[n];
            for (int i = 0; i < n; i++) {
                order[i] = (int) (packed[i] % n);
            }
            return order;
        }

        /** Hilbert-curve distance of grid cell {@code (x, y)} on a {@code side x
            side} grid (the standard xy-&gt;d mapping). */
        private static long hilbertDistance(int side, int x, int y) {
            long d = 0;
            for (int s = side >> 1; s > 0; s >>= 1) {
                int rx = (x & s) > 0 ? 1 : 0;
                int ry = (y & s) > 0 ? 1 : 0;
                d += (long) s * s * ((3 * rx) ^ ry);
                if (ry == 0) {                            /* rotate the quadrant */
                    if (rx == 1) {
                        x = side - 1 - x;
                        y = side - 1 - y;
                    }
                    int t = x;
                    x = y;
                    y = t;
                }
            }
            return d;
        }
    }
}
