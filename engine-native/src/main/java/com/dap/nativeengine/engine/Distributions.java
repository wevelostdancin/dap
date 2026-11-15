package com.dap.nativeengine.engine;

/**
 * Distribution functions needed by the statistics nodes: Student's t, chi-square and F.
 *
 * <p>DuckDB has no erf/beta, so the tails are computed here from the regularized
 * incomplete beta (continued fraction, Numerical Recipes 6.4) and the regularized
 * incomplete gamma (series + continued fraction, 6.2), with a Lanczos log-gamma.
 * No new Maven dependency; values are pinned against reference values in
 * {@code DistributionsTest}.</p>
 */
public final class Distributions {
    private static final double EPS = 1e-15;
    private static final double FPMIN = 1e-300;
    private static final int MAX_ITERATIONS = 500;
    /** Lanczos coefficients for g = 7, n = 9 (accuracy ~1e-15). */
    private static final double[] LANCZOS = {
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278686905,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7
    };

    private Distributions() {
    }

    /** P(T &lt;= t) for Student's t distribution with df degrees of freedom. */
    public static double tCdf(double t, double df) {
        requirePositive(df, "df");
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        if (t == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        if (t == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        // I_x(df/2, 1/2) with x = df / (df + t^2) is the two-sided tail.
        double x = df / (df + t * t);
        double tail = 0.5 * regularizedBeta(df / 2.0, 0.5, x);
        return t >= 0.0 ? 1.0 - tail : tail;
    }

    /** P(X &lt;= x) for the chi-square distribution with df degrees of freedom. */
    public static double chiSquareCdf(double x, double df) {
        requirePositive(df, "df");
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x <= 0.0) {
            return 0.0;
        }
        if (x == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        return regularizedGammaP(df / 2.0, x / 2.0);
    }

    /** P(F &lt;= x) for the F distribution with df1 and df2 degrees of freedom. */
    public static double fCdf(double x, double df1, double df2) {
        requirePositive(df1, "df1");
        requirePositive(df2, "df2");
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x <= 0.0) {
            return 0.0;
        }
        if (x == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        double z = df1 * x / (df1 * x + df2);
        return regularizedBeta(df1 / 2.0, df2 / 2.0, z);
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a positive finite number, got " + value);
        }
    }

    /** log(Gamma(x)) for x &gt; 0. */
    static double logGamma(double x) {
        if (x <= 0.0) {
            throw new IllegalArgumentException("logGamma requires a positive argument, got " + x);
        }
        if (x < 0.5) {
            // Reflection formula keeps the Lanczos series in its accurate range.
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }
        double z = x - 1.0;
        double sum = LANCZOS[0];
        for (int i = 1; i < LANCZOS.length; i++) {
            sum += LANCZOS[i] / (z + i);
        }
        double t = z + LANCZOS.length - 1.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    /** Regularized lower incomplete gamma P(a, x). */
    static double regularizedGammaP(double a, double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x < a + 1.0) {
            return gammaSeries(a, x);
        }
        return 1.0 - gammaContinuedFraction(a, x);
    }

    private static double gammaSeries(double a, double x) {
        double ap = a;
        double sum = 1.0 / a;
        double term = sum;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ap += 1.0;
            term *= x / ap;
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * EPS) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    private static double gammaContinuedFraction(double a, double x) {
        double b = x + 1.0 - a;
        double c = 1.0 / FPMIN;
        double d = 1.0 / b;
        double h = d;
        for (int i = 1; i <= MAX_ITERATIONS; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = b + an / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < EPS) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** Regularized incomplete beta I_x(a, b). */
    static double regularizedBeta(double a, double b, double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1.0 - x));
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(a, b, x) / a;
        }
        return 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b;
    }

    private static double betaContinuedFraction(double a, double b, double x) {
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < FPMIN) {
            d = FPMIN;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= MAX_ITERATIONS; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < FPMIN) {
                d = FPMIN;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < FPMIN) {
                c = FPMIN;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < EPS) {
                break;
            }
        }
        return h;
    }
}
