package com.acme.triangle.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public static List<Corners> triangulate(Points points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("need at least 3 points");
        }
        return new Builder(points).build();
    }

    /**
     * The incremental triangulation in progress: a triangle arena ({@link
     * Triangle} slots carrying their own neighbour links, dead slots nulled and
     * reused) over a working {@link Points} that is the input plus three
     * super-triangle vertices enclosing it. The {@code attr} field of each
     * {@link Triangle} is unused here (phase 1 has no regions).
     */
    private static final class Builder {

        private static final int HILBERT_BITS = 16;

        private final Points pts;                         /* input points + super-triangle */
        private final int n;                              /* number of input points */
        private final List<@Nullable Triangle> tris = new ArrayList<>();
        private final Deque<Integer> free = new ArrayDeque<>();
        private int[] cavityGen;                          /* gen-stamped cavity membership */
        private int gen;
        private int recent;                               /* a live triangle to start walks from */

        Builder(Points input) {
            n = input.size();
            pts = new Points(input.toArray(), n);         /* growable copy; never mutate the input */
            cavityGen = new int[16];
            recent = initSuperTriangle();
        }

        /** Append three super-triangle vertices enclosing the input and seed the
            arena with that single triangle; returns its slot id. */
        private int initSuperTriangle() {
            double minx = pts.x(0), miny = pts.y(0), maxx = minx, maxy = miny;
            for (int i = 1; i < n; i++) {
                minx = Math.min(minx, pts.x(i));
                maxx = Math.max(maxx, pts.x(i));
                miny = Math.min(miny, pts.y(i));
                maxy = Math.max(maxy, pts.y(i));
            }
            double d = Math.max(maxx - minx, maxy - miny);
            if (d <= 0) {
                d = 1;
            }
            double m = 1000 * d, cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
            int sa = pts.add(cx - m, cy - m);
            int sb = pts.add(cx + m, cy - m);
            int sc = pts.add(cx, cy + m);                 /* (sa, sb, sc) is CCW */
            return allocSlot(new Triangle(sa, sb, sc, -1, -1, -1, 0.0));
        }

        List<Corners> build() {
            int[] order = hilbertOrder();
            for (int k = 0; k < n; k++) {
                insert(order[k]);
            }
            /* Keep the triangles not incident to a super-triangle vertex. */
            List<Corners> kept = new ArrayList<>();
            for (Triangle t : tris) {
                if (t != null && t.a < n && t.b < n && t.c < n) {
                    kept.add(new Corners(t.a, t.b, t.c));
                }
            }
            return kept;
        }

        /** Insert input vertex {@code p}: locate its containing triangle, gather
            the cavity by flooding across neighbour links, and re-fan it around p. */
        private void insert(int p) {
            Point pt = pts.at(p);
            int start = locate(pt);
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
                    if (nb >= 0 && inCircle(tris.get(nb), pt)) {
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
            Map<Integer, Integer> asU = new HashMap<>();
            Map<Integer, Integer> asW = new HashMap<>();
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
                Integer mu = asW.get(u);
                if (mu != null) {                         /* shares the (p,u) edge */
                    tris.get(id).n1 = mu;
                    tris.get(mu).n0 = id;
                }
                asU.put(u, id);
                Integer mw = asU.get(w);
                if (mw != null) {                         /* shares the (w,p) edge */
                    tris.get(id).n0 = mw;
                    tris.get(mw).n1 = id;
                }
                asW.put(w, id);
            }
            return last;
        }

        /**
         * The triangle containing {@code pt}, by a remembering walk from {@link
         * #recent}: step across the edge {@code pt} lies outside of, never
         * immediately back the way we came. Falls back to a scan if the walk
         * stalls (defensive against a degenerate cycle).
         */
        private int locate(Point pt) {
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
                    if (orient(u, w, pt) < 0) {           /* pt is outside edge (u,w) */
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
            return locateByScan(pt);
        }

        private int locateByScan(Point pt) {
            for (int i = 0; i < tris.size(); i++) {
                Triangle t = tris.get(i);
                if (t != null && orient(t.a, t.b, pt) >= 0
                        && orient(t.b, t.c, pt) >= 0 && orient(t.c, t.a, pt) >= 0) {
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

        private boolean inCircle(Triangle t, Point p) {
            return Geometry.inCircle(pts, t.a, t.b, t.c, p);
        }

        private int orient(int a, int b, Point p) {
            return Geometry.orient2d(pts, a, b, p.x, p.y);
        }

        /* --- Hilbert-curve insertion order ----------------------------------- */

        /** The input point indices ordered along a Hilbert curve, so consecutive
            insertions are spatially close and each location walk is short. */
        private int[] hilbertOrder() {
            double minx = pts.x(0), miny = pts.y(0), maxx = minx, maxy = miny;
            for (int i = 1; i < n; i++) {
                minx = Math.min(minx, pts.x(i));
                maxx = Math.max(maxx, pts.x(i));
                miny = Math.min(miny, pts.y(i));
                maxy = Math.max(maxy, pts.y(i));
            }
            int side = 1 << HILBERT_BITS;
            double rx = maxx > minx ? (side - 1) / (maxx - minx) : 0.0;
            double ry = maxy > miny ? (side - 1) / (maxy - miny) : 0.0;
            /* Pack each point's Hilbert distance (the multiple-of-n high part) with
               its index (the low part) into one long, so a primitive sort orders by
               curve position and the index is recovered modulo n. */
            long[] packed = new long[n];
            for (int i = 0; i < n; i++) {
                int qx = (int) ((pts.x(i) - minx) * rx);
                int qy = (int) ((pts.y(i) - miny) * ry);
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
