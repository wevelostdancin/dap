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
    void invalidDegreesOfFreedomAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Distributions.tCdf(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.tCdf(1.0, -3.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.chiSquareCdf(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fCdf(1.0, 0.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fCdf(1.0, 5.0, -1.0));
    }
}
