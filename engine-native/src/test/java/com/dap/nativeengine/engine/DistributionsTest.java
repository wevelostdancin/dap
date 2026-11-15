package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reference values from R (pt/pchisq/pf and the critical points qt/qchisq/qf); the
 * environment has no R, so the numbers were cross-checked against exact closed forms
 * (t with df=1,2, chi-square with df=2, F with df1=2) and against high-precision
 * numerical integration of the densities. Tolerance 1e-6 as the plan requires.
 */
class DistributionsTest {
    private static final double TOLERANCE = 1e-6;

    @Test
    void studentTCdfMatchesRReferenceValues() {
        // R: pt(1, 1) = 0.75, pt(2, 2) = 0.9082482904638631 (closed forms).
        assertEquals(0.75, Distributions.tCdf(1.0, 1), TOLERANCE);
        assertEquals(0.9082482904638631, Distributions.tCdf(2.0, 2), TOLERANCE);
        assertEquals(0.5, Distributions.tCdf(0.0, 5), TOLERANCE);
        // R: pt(1, 10) = 0.8295534, pt(-1.5, 7) = 0.08864924, qt(0.975, 10) = 2.228139.
        assertEquals(0.8295534338489695, Distributions.tCdf(1.0, 10), TOLERANCE);
        assertEquals(0.08864924349498426, Distributions.tCdf(-1.5, 7), TOLERANCE);
        assertEquals(0.975, Distributions.tCdf(2.2281388519649385, 10), TOLERANCE);
    }

    @Test
    void chiSquareCdfMatchesRReferenceValues() {
        // R: pchisq(3.841459, 1) = 0.95, pchisq(5.991465, 2) = 0.95 (critical points).
        assertEquals(0.95, Distributions.chiSquareCdf(3.841458820694124, 1), TOLERANCE);
        assertEquals(0.95, Distributions.chiSquareCdf(5.991464547107979, 2), TOLERANCE);
        // R: pchisq(10, 3) = 0.9814339; df=2 has the closed form 1 - exp(-x/2).
        assertEquals(0.9814338645369354, Distributions.chiSquareCdf(10.0, 3), TOLERANCE);
        assertEquals(1.0 - Math.exp(-5.991464547107979 / 2.0),
                Distributions.chiSquareCdf(5.991464547107979, 2), 1e-12);
        assertEquals(0.0, Distributions.chiSquareCdf(0.0, 5), 0.0);
    }

    @Test
    void fCdfMatchesRReferenceValues() {
        // R: qf(0.95, 2, 10) = 4.102821, qf(0.95, 3, 20) = 3.098391.
        assertEquals(0.95, Distributions.fCdf(4.102821015130742, 2, 10), TOLERANCE);
        assertEquals(0.95, Distributions.fCdf(3.0983914388017784, 3, 20), TOLERANCE);
        // F(d1, d2) at x = 1 is symmetric: pf(1, 5, 5) = 0.5.
        assertEquals(0.5, Distributions.fCdf(1.0, 5, 5), TOLERANCE);
        // df1 = 2 has the closed form 1 - (d2 / (d2 + d1 * x))^(d2 / 2).
        assertEquals(0.37907867694083985, Distributions.fCdf(0.5, 2, 10), TOLERANCE);
        assertEquals(0.0, Distributions.fCdf(0.0, 2, 10), 0.0);
    }

    @Test
    void normalCdfMatchesRReferenceValues() {
        // R: pnorm(0) = 0.5, pnorm(1.96) = 0.9750021048517795, pnorm(-1) = 0.15865525393145707.
        assertEquals(0.5, Distributions.normalCdf(0.0), TOLERANCE);
        assertEquals(0.975, Distributions.normalCdf(1.959963984540054), TOLERANCE);
        assertEquals(0.9750021048517795, Distributions.normalCdf(1.96), TOLERANCE);
        assertEquals(0.15865525393145707, Distributions.normalCdf(-1.0), TOLERANCE);
        assertEquals(0.02275013194817921, Distributions.normalCdf(-2.0), TOLERANCE);
        assertEquals(0.0, Distributions.normalCdf(Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(1.0, Distributions.normalCdf(Double.POSITIVE_INFINITY), 0.0);
    }

    @Test
    void normalQuantileInvertsTheCdf() {
        // R: qnorm(0.975) = 1.959963984540054, qnorm(0.001) = -3.090232306167814.
        assertEquals(0.0, Distributions.normalQuantile(0.5), TOLERANCE);
        assertEquals(1.959963984540054, Distributions.normalQuantile(0.975), TOLERANCE);
        assertEquals(-3.090232306167814, Distributions.normalQuantile(0.001), TOLERANCE);
        assertEquals(2.3263478740408408, Distributions.normalQuantile(0.99), TOLERANCE);
        for (double p : new double[]{0.001, 0.01, 0.25, 0.5, 0.9, 0.975, 0.999}) {
            assertEquals(p, Distributions.normalCdf(Distributions.normalQuantile(p)), 1e-10,
                    "round trip at " + p);
        }
    }

    @Test
    void tQuantileInvertsTheCdf() {
        // R: qt(0.975, 10) = 2.2281388519649385, qt(0.95, 5) = 2.015048372669157.
        assertEquals(2.2281388519649385, Distributions.tQuantile(0.975, 10), TOLERANCE);
        assertEquals(2.015048372669157, Distributions.tQuantile(0.95, 5), TOLERANCE);
        assertEquals(12.706204736432095, Distributions.tQuantile(0.975, 1), 1e-9);
        assertEquals(0.0, Distributions.tQuantile(0.5, 7), TOLERANCE);
        assertEquals(-2.2281388519649385, Distributions.tQuantile(0.025, 10), TOLERANCE);
        assertEquals(2.570581835636314, Distributions.tQuantile(0.975, 5), 1e-9);
        for (double p : new double[]{0.01, 0.1, 0.5, 0.9, 0.975, 0.999}) {
            assertEquals(p, Distributions.tCdf(Distributions.tQuantile(p, 12), 12), 1e-10,
                    "round trip at " + p);
        }
    }

    @Test
    void studentizedRangeMatchesTheKnownCriticalValues() {
        // R: qtukey(0.95, 3, 10) = 3.877, qtukey(0.95, 5, 20) = 4.232.
        assertEquals(0.95, Distributions.studentizedRangeCdf(3.877, 3, 10), 0.005);
        assertEquals(0.95, Distributions.studentizedRangeCdf(4.232, 5, 20), 0.005);
        // Two means: the studentized range is sqrt(2) * |t|, an exact identity.
        assertEquals(0.95, Distributions.studentizedRangeCdf(
                Math.sqrt(2.0) * Distributions.tQuantile(0.975, 15), 2, 15), 1e-6);
        // Monotone in q, and 0 / 1 at the ends.
        assertEquals(0.0, Distributions.studentizedRangeCdf(0.0, 3, 10), 0.0);
        assertEquals(1.0, Distributions.studentizedRangeCdf(Double.POSITIVE_INFINITY, 3, 10), 0.0);
        assertEquals(true, Distributions.studentizedRangeCdf(3.0, 3, 10)
                < Distributions.studentizedRangeCdf(4.0, 3, 10));
    }

    @Test
    void studentizedRangeRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> Distributions.studentizedRangeCdf(3.0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> Distributions.studentizedRangeCdf(3.0, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.normalQuantile(-0.1));
        assertThrows(IllegalArgumentException.class, () -> Distributions.normalQuantile(1.5));
        assertThrows(IllegalArgumentException.class, () -> Distributions.tQuantile(0.0, 10));
        assertThrows(IllegalArgumentException.class, () -> Distributions.tQuantile(0.5, 0));
    }

    @Test
    void invalidDegreesOfFreedomAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Distributions.tCdf(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.tCdf(1.0, -3.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.chiSquareCdf(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fCdf(1.0, 0.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fCdf(1.0, 5.0, -1.0));
    }
}
