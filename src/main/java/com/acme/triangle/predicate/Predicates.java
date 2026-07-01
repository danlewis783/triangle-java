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
 * close to zero (the near-degenerate cases) does evaluation escalate through
 * Shewchuk's adaptive stages (triangle.c:2788 {@code counterclockwiseadapt},
 * :2929 {@code incircleadapt}): progressively more precise determinants built
 * from exact floating-point expansions, each with its own error bound, stopping
 * at the first stage whose sign is provably correct. For orient2d the final (D)
 * stage is fully exact; for incircle the port stops after the C stage, which
 * decides all but exactly-cocircular inputs, and falls back to the exact
 * {@link BigDecimal} determinant for those (where {@code new BigDecimal(double)}
 * is the exact value of the {@code double}, so the sign is exact for any
 * inputs).
 * <p>
 * Every stage computes the <em>same</em> determinant, so the returned sign is
 * identical to the always-exact version for every input; the stages only change
 * how fast we get there. All paths are validated against the predicate oracle
 * ({@code contract/predicates.txt}).
 * <p>
 * {@code strictfp} guarantees the exact IEEE-754 rounding the expansion
 * arithmetic requires, even on legacy 32-bit x87 JVMs (it is the default - and
 * the keyword a no-op - on Java 17+).
 */
public final strictfp class Predicates {

    /**
     * Machine epsilon (2<sup>-53</sup>), the multiplier that splits a double
     * into half-width factors for exact multiplication, and the resulting
     * static/adaptive error bounds, computed the way Shewchuk's
     * {@code exactinit} (triangle.c:2549) does so they match the host
     * floating-point rounding rather than being hard-coded.
     */
    private static final double EPSILON;
    private static final double SPLITTER;
    private static final double RESULT_ERRBOUND;
    private static final double CCW_ERRBOUND_A;
    private static final double CCW_ERRBOUND_B;
    private static final double CCW_ERRBOUND_C;
    private static final double ICC_ERRBOUND_A;
    private static final double ICC_ERRBOUND_B;
    private static final double ICC_ERRBOUND_C;

    static {
        double epsilon = 1.0;
        double splitter = 1.0;
        boolean everyOther = true;
        double half = 0.5;
        double check = 1.0;
        double lastCheck;
        do {
            lastCheck = check;
            epsilon *= half;
            if (everyOther) {
                splitter *= 2.0;
            }
            everyOther = !everyOther;
            check = 1.0 + epsilon;
        } while (check != 1.0 && check != lastCheck);
        EPSILON = epsilon;
        SPLITTER = splitter + 1.0;
        RESULT_ERRBOUND = (3.0 + 8.0 * epsilon) * epsilon;
        CCW_ERRBOUND_A = (3.0 + 16.0 * epsilon) * epsilon;
        CCW_ERRBOUND_B = (2.0 + 12.0 * epsilon) * epsilon;
        CCW_ERRBOUND_C = (9.0 + 64.0 * epsilon) * epsilon * epsilon;
        ICC_ERRBOUND_A = (10.0 + 96.0 * epsilon) * epsilon;
        ICC_ERRBOUND_B = (4.0 + 48.0 * epsilon) * epsilon;
        ICC_ERRBOUND_C = (44.0 + 576.0 * epsilon) * epsilon * epsilon;
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
        return orient2dAdapt(ax, ay, bx, by, cx, cy, detSum);
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
        return incircleAdapt(ax, ay, bx, by, cx, cy, dx, dy, permanent);
    }

    private static int sign(double d) {
        return d > 0.0 ? 1 : (d < 0.0 ? -1 : 0);
    }

    /* --- adaptive stages (Shewchuk's expansion arithmetic) -------------------

       A non-overlapping expansion represents a value exactly as a sum of
       doubles; the two/one-word primitives below produce the roundoff "tail" of
       an operation so nothing is lost. Direct ports of triangle.c's macros. */

    /** The roundoff of {@code x = a * b} (Two_Product_Tail). */
    private static double twoProductTail(double a, double b, double x) {
        double c = SPLITTER * a;
        double aBig = c - a;
        double aHi = c - aBig;
        double aLo = a - aHi;
        double c2 = SPLITTER * b;
        double bBig = c2 - b;
        double bHi = c2 - bBig;
        double bLo = b - bHi;
        double err = x - aHi * bHi;
        err -= aLo * bHi;
        err -= aHi * bLo;
        return aLo * bLo - err;
    }

    /** The roundoff of {@code x = a * b} where {@code b} is pre-split
        (Two_Product_Presplit). */
    private static double twoProductPresplitTail(double a, double b,
                                                 double bHi, double bLo, double x) {
        double c = SPLITTER * a;
        double aBig = c - a;
        double aHi = c - aBig;
        double aLo = a - aHi;
        double err = x - aHi * bHi;
        err -= aLo * bHi;
        err -= aHi * bLo;
        return aLo * bLo - err;
    }

    /** The roundoff of {@code x = a + b} (Two_Sum_Tail). */
    private static double twoSumTail(double a, double b, double x) {
        double bVirt = x - a;
        double aVirt = x - bVirt;
        double bRound = b - bVirt;
        double aRound = a - aVirt;
        return aRound + bRound;
    }

    /** The roundoff of {@code x = a + b} when {@code |a| >= |b|}
        (Fast_Two_Sum_Tail). */
    private static double fastTwoSumTail(double a, double b, double x) {
        double bVirt = x - a;
        return b - bVirt;
    }

    /** The roundoff of {@code x = a - b} (Two_Diff_Tail). */
    private static double twoDiffTail(double a, double b, double x) {
        double bVirt = a - x;
        double aVirt = x + bVirt;
        double bRound = bVirt - b;
        double aRound = a - aVirt;
        return aRound + bRound;
    }

    /** {@code h[0..3]} = (a1, a0) - (b1, b0) as a four-component expansion
        (Two_Two_Diff). */
    private static void twoTwoDiff(double a1, double a0, double b1, double b0,
                                   double[] h) {
        double i1 = a0 - b0;
        double x0 = twoDiffTail(a0, b0, i1);
        double j1 = a1 + i1;
        double t0 = twoSumTail(a1, i1, j1);
        double i2 = t0 - b1;
        double x1 = twoDiffTail(t0, b1, i2);
        double x3 = j1 + i2;
        double x2 = twoSumTail(j1, i2, x3);
        h[0] = x0;
        h[1] = x1;
        h[2] = x2;
        h[3] = x3;
    }

    /** {@code h} = e + f with zero components dropped; returns the length of
        {@code h} (fast_expansion_sum_zeroelim, triangle.c:2622). */
    private static int fastExpansionSumZeroelim(int elen, double[] e,
                                                int flen, double[] f, double[] h) {
        int eindex = 0;
        int findex = 0;
        double enow = e[0];
        double fnow = f[0];
        double q;
        if ((fnow > enow) == (fnow > -enow)) {
            q = enow;
            enow = ++eindex < elen ? e[eindex] : 0.0;
        } else {
            q = fnow;
            fnow = ++findex < flen ? f[findex] : 0.0;
        }
        int hindex = 0;
        double qnew;
        double hh;
        if (eindex < elen && findex < flen) {
            if ((fnow > enow) == (fnow > -enow)) {
                qnew = enow + q;
                hh = fastTwoSumTail(enow, q, qnew);
                enow = ++eindex < elen ? e[eindex] : 0.0;
            } else {
                qnew = fnow + q;
                hh = fastTwoSumTail(fnow, q, qnew);
                fnow = ++findex < flen ? f[findex] : 0.0;
            }
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
            while (eindex < elen && findex < flen) {
                if ((fnow > enow) == (fnow > -enow)) {
                    qnew = q + enow;
                    hh = twoSumTail(q, enow, qnew);
                    enow = ++eindex < elen ? e[eindex] : 0.0;
                } else {
                    qnew = q + fnow;
                    hh = twoSumTail(q, fnow, qnew);
                    fnow = ++findex < flen ? f[findex] : 0.0;
                }
                q = qnew;
                if (hh != 0.0) {
                    h[hindex++] = hh;
                }
            }
        }
        while (eindex < elen) {
            qnew = q + enow;
            hh = twoSumTail(q, enow, qnew);
            enow = ++eindex < elen ? e[eindex] : 0.0;
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        while (findex < flen) {
            qnew = q + fnow;
            hh = twoSumTail(q, fnow, qnew);
            fnow = ++findex < flen ? f[findex] : 0.0;
            q = qnew;
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        if (q != 0.0 || hindex == 0) {
            h[hindex++] = q;
        }
        return hindex;
    }

    /** {@code h} = b &middot; e with zero components dropped; returns the length
        of {@code h} (scale_expansion_zeroelim, triangle.c:2707). */
    private static int scaleExpansionZeroelim(int elen, double[] e, double b,
                                              double[] h) {
        double c = SPLITTER * b;
        double bBig = c - b;
        double bHi = c - bBig;
        double bLo = b - bHi;
        double q = e[0] * b;
        double hh = twoProductPresplitTail(e[0], b, bHi, bLo, q);
        int hindex = 0;
        if (hh != 0.0) {
            h[hindex++] = hh;
        }
        for (int eindex = 1; eindex < elen; eindex++) {
            double enow = e[eindex];
            double product1 = enow * b;
            double product0 = twoProductPresplitTail(enow, b, bHi, bLo, product1);
            double sum = q + product0;
            hh = twoSumTail(q, product0, sum);
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
            q = product1 + sum;
            hh = fastTwoSumTail(product1, sum, q);
            if (hh != 0.0) {
                h[hindex++] = hh;
            }
        }
        if (q != 0.0 || hindex == 0) {
            h[hindex++] = q;
        }
        return hindex;
    }

    /** A one-word estimate of the expansion's value (estimate, triangle.c:2755). */
    private static double estimate(int elen, double[] e) {
        double q = e[0];
        for (int eindex = 1; eindex < elen; eindex++) {
            q += e[eindex];
        }
        return q;
    }

    /**
     * The adaptive orient2d tail (counterclockwiseadapt, triangle.c:2788),
     * entered when the A filter cannot certify the plain-double sign: stage B
     * (exact expansion of the rounded differences), stage C (tail correction),
     * then the exact stage D. Every return's sign is provably correct, D's
     * unconditionally - orient2d never needs a non-double exact fallback.
     */
    private static int orient2dAdapt(double ax, double ay, double bx, double by,
                                     double cx, double cy, double detSum) {
        double acx = ax - cx;
        double bcx = bx - cx;
        double acy = ay - cy;
        double bcy = by - cy;

        double detLeft = acx * bcy;
        double detLeftTail = twoProductTail(acx, bcy, detLeft);
        double detRight = acy * bcx;
        double detRightTail = twoProductTail(acy, bcx, detRight);

        double[] bExp = new double[4];
        twoTwoDiff(detLeft, detLeftTail, detRight, detRightTail, bExp);
        double det = estimate(4, bExp);
        double errBound = CCW_ERRBOUND_B * detSum;
        if (det >= errBound || -det >= errBound) {
            return sign(det);
        }

        double acxTail = twoDiffTail(ax, cx, acx);
        double bcxTail = twoDiffTail(bx, cx, bcx);
        double acyTail = twoDiffTail(ay, cy, acy);
        double bcyTail = twoDiffTail(by, cy, bcy);
        if (acxTail == 0.0 && acyTail == 0.0 && bcxTail == 0.0 && bcyTail == 0.0) {
            return sign(det);                  /* the differences were exact */
        }

        errBound = CCW_ERRBOUND_C * detSum + RESULT_ERRBOUND * Math.abs(det);
        det += (acx * bcyTail + bcy * acxTail) - (acy * bcxTail + bcx * acyTail);
        if (det >= errBound || -det >= errBound) {
            return sign(det);
        }

        double[] u = new double[4];
        double s1 = acxTail * bcy;
        double s0 = twoProductTail(acxTail, bcy, s1);
        double t1 = acyTail * bcx;
        double t0 = twoProductTail(acyTail, bcx, t1);
        twoTwoDiff(s1, s0, t1, t0, u);
        double[] c1 = new double[8];
        int c1len = fastExpansionSumZeroelim(4, bExp, 4, u, c1);

        s1 = acx * bcyTail;
        s0 = twoProductTail(acx, bcyTail, s1);
        t1 = acy * bcxTail;
        t0 = twoProductTail(acy, bcxTail, t1);
        twoTwoDiff(s1, s0, t1, t0, u);
        double[] c2 = new double[12];
        int c2len = fastExpansionSumZeroelim(c1len, c1, 4, u, c2);

        s1 = acxTail * bcyTail;
        s0 = twoProductTail(acxTail, bcyTail, s1);
        t1 = acyTail * bcxTail;
        t0 = twoProductTail(acyTail, bcxTail, t1);
        twoTwoDiff(s1, s0, t1, t0, u);
        double[] d = new double[16];
        int dlen = fastExpansionSumZeroelim(c2len, c2, 4, u, d);

        return sign(d[dlen - 1]);              /* exact: the leading component */
    }

    /**
     * The adaptive incircle tail (incircleadapt, triangle.c:2929) through its B
     * and C stages: an exact expansion of the determinant over the rounded
     * differences, then a tail-corrected estimate. Together they decide every
     * input whose points are not exactly (or almost unrepresentably nearly)
     * cocircular; the remainder falls through to the exact {@link BigDecimal}
     * evaluation, so the returned sign is exact for all inputs.
     */
    private static int incircleAdapt(double ax, double ay, double bx, double by,
                                     double cx, double cy, double dx, double dy,
                                     double permanent) {
        double adx = ax - dx;
        double bdx = bx - dx;
        double cdx = cx - dx;
        double ady = ay - dy;
        double bdy = by - dy;
        double cdy = cy - dy;

        /* Stage B: det = adx^2+ady^2 lifted over (bc), plus the b and c terms,
           each as an exact expansion over the rounded differences. */
        double[] t4 = new double[4];
        double[] t8a = new double[8];
        double[] t16 = new double[16];

        double bdxcdy1 = bdx * cdy;
        double bdxcdy0 = twoProductTail(bdx, cdy, bdxcdy1);
        double cdxbdy1 = cdx * bdy;
        double cdxbdy0 = twoProductTail(cdx, bdy, cdxbdy1);
        twoTwoDiff(bdxcdy1, bdxcdy0, cdxbdy1, cdxbdy0, t4);
        double[] adet = new double[32];
        int alen = liftedTerm(t4, adx, ady, t8a, t16, adet);

        double cdxady1 = cdx * ady;
        double cdxady0 = twoProductTail(cdx, ady, cdxady1);
        double adxcdy1 = adx * cdy;
        double adxcdy0 = twoProductTail(adx, cdy, adxcdy1);
        twoTwoDiff(cdxady1, cdxady0, adxcdy1, adxcdy0, t4);
        double[] bdet = new double[32];
        int blen = liftedTerm(t4, bdx, bdy, t8a, t16, bdet);

        double adxbdy1 = adx * bdy;
        double adxbdy0 = twoProductTail(adx, bdy, adxbdy1);
        double bdxady1 = bdx * ady;
        double bdxady0 = twoProductTail(bdx, ady, bdxady1);
        twoTwoDiff(adxbdy1, adxbdy0, bdxady1, bdxady0, t4);
        double[] cdet = new double[32];
        int clen = liftedTerm(t4, cdx, cdy, t8a, t16, cdet);

        double[] abdet = new double[64];
        int ablen = fastExpansionSumZeroelim(alen, adet, blen, bdet, abdet);
        double[] fin = new double[96];
        int finlength = fastExpansionSumZeroelim(ablen, abdet, clen, cdet, fin);

        double det = estimate(finlength, fin);
        double errBound = ICC_ERRBOUND_B * permanent;
        if (det >= errBound || -det >= errBound) {
            return sign(det);
        }

        /* Stage C: correct with the first-order terms of the difference tails. */
        double adxTail = twoDiffTail(ax, dx, adx);
        double adyTail = twoDiffTail(ay, dy, ady);
        double bdxTail = twoDiffTail(bx, dx, bdx);
        double bdyTail = twoDiffTail(by, dy, bdy);
        double cdxTail = twoDiffTail(cx, dx, cdx);
        double cdyTail = twoDiffTail(cy, dy, cdy);
        if (adxTail == 0.0 && bdxTail == 0.0 && cdxTail == 0.0
                && adyTail == 0.0 && bdyTail == 0.0 && cdyTail == 0.0) {
            return sign(det);                  /* the differences were exact */
        }

        errBound = ICC_ERRBOUND_C * permanent + RESULT_ERRBOUND * Math.abs(det);
        det += ((adx * adx + ady * ady) * ((bdx * cdyTail + cdy * bdxTail)
                                           - (bdy * cdxTail + cdx * bdyTail))
                + 2.0 * (adx * adxTail + ady * adyTail) * (bdx * cdy - bdy * cdx))
             + ((bdx * bdx + bdy * bdy) * ((cdx * adyTail + ady * cdxTail)
                                           - (cdy * adxTail + adx * cdyTail))
                + 2.0 * (bdx * bdxTail + bdy * bdyTail) * (cdx * ady - cdy * adx))
             + ((cdx * cdx + cdy * cdy) * ((adx * bdyTail + bdy * adxTail)
                                           - (ady * bdxTail + bdx * adyTail))
                + 2.0 * (cdx * cdxTail + cdy * cdyTail) * (adx * bdy - ady * bdx));
        if (det >= errBound || -det >= errBound) {
            return sign(det);
        }

        return incircleExact(ax, ay, bx, by, cx, cy, dx, dy);
    }

    /** One lifted incircle term: {@code (x^2 + y^2) * cross}, where {@code cross}
        is a four-component expansion; returns the length of {@code out}. The
        {@code s8}/{@code s16} scratch is overwritten. */
    private static int liftedTerm(double[] cross, double x, double y,
                                  double[] s8, double[] s16, double[] out) {
        int xlen = scaleExpansionZeroelim(4, cross, x, s8);
        double[] xx = new double[16];
        int xxlen = scaleExpansionZeroelim(xlen, s8, x, xx);
        int ylen = scaleExpansionZeroelim(4, cross, y, s8);
        int yylen = scaleExpansionZeroelim(ylen, s8, y, s16);
        return fastExpansionSumZeroelim(xxlen, xx, yylen, s16, out);
    }

    /** Exact incircle sign via {@link BigDecimal}; the last-resort fallback for
        (nearly) exactly cocircular inputs the adaptive stages cannot separate. */
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
