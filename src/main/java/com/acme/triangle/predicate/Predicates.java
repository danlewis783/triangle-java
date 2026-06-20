package com.acme.triangle.predicate;

import java.math.BigDecimal;

/**
 * Robust geometric predicates: the exact sign of the orient2d and incircle
 * determinants.
 *
 * <p>Correctness of the <em>sign</em> on near-degenerate inputs is what the
 * mesh algorithm depends on; a wrong sign yields invalid topology, not merely a
 * different mesh. This implementation computes the determinant exactly with
 * {@link BigDecimal}: {@code new BigDecimal(double)} is the exact value of the
 * {@code double}, so the sign is exact for any inputs.
 *
 * <p>It is deliberately the simplest correct version. A fast filtered path
 * (evaluate in {@code double} with an error bound, fall back to exact only when
 * the estimate is too close to zero) can be added later and validated against
 * the same predicate oracle ({@code contract/predicates.txt}).
 */
public final class Predicates {

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
        BigDecimal acx = bd(ax).subtract(bd(cx));
        BigDecimal acy = bd(ay).subtract(bd(cy));
        BigDecimal bcx = bd(bx).subtract(bd(cx));
        BigDecimal bcy = bd(by).subtract(bd(cy));
        return acx.multiply(bcy).subtract(acy.multiply(bcx)).signum();
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
