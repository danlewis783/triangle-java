package com.acme.triangle.predicate;

import java.math.BigDecimal;

/**
 * Robust geometric predicates: the exact sign of the orient2d and incircle
 * determinants.
 * <p>
 * Correctness of the <em>sign</em> on near-degenerate inputs is what the
 * mesh algorithm depends on; a wrong sign yields invalid topology, not merely a
 * different mesh.
 * <p>
 * Each predicate first evaluates the determinant in plain {@code double} and
 * compares its magnitude against a conservative forward-error bound (the
 * Shewchuk static "A" filter). When the estimate is comfortably away from zero
 * its sign is provably correct and is returned directly; only when it is too
 * close to zero (the near-degenerate cases) do we fall back to the exact
 * {@link BigDecimal} computation, where {@code new BigDecimal(double)} is the
 * exact value of the {@code double} so the sign is exact for any inputs.
 * <p>
 * The fast path and the exact path compute the <em>same</em> determinant, so
 * the returned sign is identical to the always-exact version for every input;
 * the filter only changes how fast we get there. Both paths are validated
 * against the predicate oracle ({@code contract/predicates.txt}).
 */
public final class Predicates {

    /**
     * Machine epsilon (2<sup>-53</sup>) and the resulting static error bounds,
     * computed the way Shewchuk's {@code exactinit} does so they match the host
     * floating-point rounding rather than being hard-coded.
     */
    private static final double EPSILON;
    private static final double CCW_ERRBOUND_A;
    private static final double ICC_ERRBOUND_A;

    static {
        double epsilon = 1.0;
        double half = 0.5;
        double check = 1.0;
        double lastCheck;
        do {
            lastCheck = check;
            epsilon *= half;
            check = 1.0 + epsilon;
        } while (check != 1.0 && check != lastCheck);
        EPSILON = epsilon;
        CCW_ERRBOUND_A = (3.0 + 16.0 * epsilon) * epsilon;
        ICC_ERRBOUND_A = (10.0 + 96.0 * epsilon) * epsilon;
    }

    private Predicates() {
    }

    /**
     * Orientation of the ordered triple (a, b, c).
     *
     * @return {@code +1} if a&rarr;b&rarr;c makes a left turn (counterclockwise),
     *         {@code -1} for a right turn (clockwise), {@code 0} if collinear
     */
    public static int orient2d(double ax, double ay,
                               double bx, double by,
                               double cx, double cy) {
        double detLeft = (ax - cx) * (by - cy);
        double detRight = (ay - cy) * (bx - cx);
        double det = detLeft - detRight;

        double detSum;
        if (detLeft > 0.0) {
            if (detRight <= 0.0) {
                return sign(det);          /* opposite signs: result is reliable */
            }
            detSum = detLeft + detRight;
        } else if (detLeft < 0.0) {
            if (detRight >= 0.0) {
                return sign(det);
            }
            detSum = -detLeft - detRight;
        } else {
            return sign(det);
        }

        double errBound = CCW_ERRBOUND_A * detSum;
        if (det >= errBound || -det >= errBound) {
            return sign(det);              /* far enough from zero: sign is exact */
        }
        return orient2dExact(ax, ay, bx, by, cx, cy);
    }

    /**
     * Position of d relative to the circle through (a, b, c), which must be in
     * counterclockwise order.
     *
     * @return {@code +1} if d is strictly inside the circle, {@code -1} if
     *         strictly outside, {@code 0} if the four points are cocircular
     */
    public static int incircle(double ax, double ay,
                               double bx, double by,
                               double cx, double cy,
                               double dx, double dy) {
        double adx = ax - dx, ady = ay - dy;
        double bdx = bx - dx, bdy = by - dy;
        double cdx = cx - dx, cdy = cy - dy;

        double bdxcdy = bdx * cdy, cdxbdy = cdx * bdy;
        double cdxady = cdx * ady, adxcdy = adx * cdy;
        double adxbdy = adx * bdy, bdxady = bdx * ady;

        double aLift = adx * adx + ady * ady;
        double bLift = bdx * bdx + bdy * bdy;
        double cLift = cdx * cdx + cdy * cdy;

        double det = aLift * (bdxcdy - cdxbdy)
                + bLift * (cdxady - adxcdy)
                + cLift * (adxbdy - bdxady);

        double permanent = (Math.abs(bdxcdy) + Math.abs(cdxbdy)) * aLift
                + (Math.abs(cdxady) + Math.abs(adxcdy)) * bLift
                + (Math.abs(adxbdy) + Math.abs(bdxady)) * cLift;
        double errBound = ICC_ERRBOUND_A * permanent;
        if (det > errBound || -det > errBound) {
            return sign(det);              /* far enough from zero: sign is exact */
        }
        return incircleExact(ax, ay, bx, by, cx, cy, dx, dy);
    }

    private static int sign(double d) {
        return d > 0.0 ? 1 : (d < 0.0 ? -1 : 0);
    }

    /** Exact orient2d sign via {@link BigDecimal}; the near-degenerate fallback. */
    private static int orient2dExact(double ax, double ay,
                                     double bx, double by,
                                     double cx, double cy) {
        BigDecimal acx = bd(ax).subtract(bd(cx));
        BigDecimal acy = bd(ay).subtract(bd(cy));
        BigDecimal bcx = bd(bx).subtract(bd(cx));
        BigDecimal bcy = bd(by).subtract(bd(cy));
        return acx.multiply(bcy).subtract(acy.multiply(bcx)).signum();
    }

    /** Exact incircle sign via {@link BigDecimal}; the near-degenerate fallback. */
    private static int incircleExact(double ax, double ay,
                                     double bx, double by,
                                     double cx, double cy,
                                     double dx, double dy) {
        BigDecimal adx = bd(ax).subtract(bd(dx));
        BigDecimal ady = bd(ay).subtract(bd(dy));
        BigDecimal bdx = bd(bx).subtract(bd(dx));
        BigDecimal bdy = bd(by).subtract(bd(dy));
        BigDecimal cdx = bd(cx).subtract(bd(dx));
        BigDecimal cdy = bd(cy).subtract(bd(dy));

        BigDecimal aLift = adx.multiply(adx).add(ady.multiply(ady));
        BigDecimal bLift = bdx.multiply(bdx).add(bdy.multiply(bdy));
        BigDecimal cLift = cdx.multiply(cdx).add(cdy.multiply(cdy));

        BigDecimal det = adx.multiply(bdy.multiply(cLift).subtract(cdy.multiply(bLift)))
                .subtract(ady.multiply(bdx.multiply(cLift).subtract(cdx.multiply(bLift))))
                .add(aLift.multiply(bdx.multiply(cdy).subtract(cdx.multiply(bdy))));
        return det.signum();
    }

    /** Exact BigDecimal value of a double (not the decimal-string rounding). */
    private static BigDecimal bd(double d) {
        return new BigDecimal(d);
    }
}
