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
    /** Acklam's inverse normal approximation coefficients (|error| &lt; 1.15e-9 before refinement). */
    private static final double[] ACKLAM_A = {
            -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00
    };
    private static final double[] ACKLAM_B = {
            -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01
    };
    private static final double[] ACKLAM_C = {
            -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00
    };
    private static final double[] ACKLAM_D = {
            7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00
    };
    /** Quadrature for the studentized range: outer over z, inner over the range density. */
    private static final double RANGE_Z_LIMIT = 12.0;
    private static final double INNER_LIMIT = 12.0;
    private static final double[][] RANGE_NODES = gaussLegendre(96);
    private static final double[][] INNER_NODES = gaussLegendre(128);

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

    /** P(Z &lt;= x) for the standard normal distribution. */
    public static double normalCdf(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (x == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        if (x == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        // erf(z) = P(1/2, z^2), so the normal tail reuses the incomplete gamma.
        double z = x / Math.sqrt(2.0);
        double erf = regularizedGammaP(0.5, z * z);
        return x >= 0.0 ? 0.5 * (1.0 + erf) : 0.5 * (1.0 - erf);
    }

    /**
     * Inverse of {@link #normalCdf}: the p-quantile of the standard normal distribution.
     * Acklam's rational approximation followed by one Halley refinement step.
     */
    public static double normalQuantile(double p) {
        if (!(p > 0.0) || !(p < 1.0)) {
            throw new IllegalArgumentException("normalQuantile: p must be in (0, 1), got " + p);
        }
        if (p == 0.5) {
            return 0.0;
        }
        double x;
        if (p < 0.02425) {
            double q = Math.sqrt(-2.0 * Math.log(p));
            x = (((((ACKLAM_C[0] * q + ACKLAM_C[1]) * q + ACKLAM_C[2]) * q + ACKLAM_C[3]) * q
                    + ACKLAM_C[4]) * q + ACKLAM_C[5])
                    / ((((ACKLAM_D[0] * q + ACKLAM_D[1]) * q + ACKLAM_D[2]) * q + ACKLAM_D[3]) * q + 1.0);
        } else if (p > 1.0 - 0.02425) {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            x = -(((((ACKLAM_C[0] * q + ACKLAM_C[1]) * q + ACKLAM_C[2]) * q + ACKLAM_C[3]) * q
                    + ACKLAM_C[4]) * q + ACKLAM_C[5])
                    / ((((ACKLAM_D[0] * q + ACKLAM_D[1]) * q + ACKLAM_D[2]) * q + ACKLAM_D[3]) * q + 1.0);
        } else {
            double q = p - 0.5;
            double r = q * q;
            x = (((((ACKLAM_A[0] * r + ACKLAM_A[1]) * r + ACKLAM_A[2]) * r + ACKLAM_A[3]) * r
                    + ACKLAM_A[4]) * r + ACKLAM_A[5]) * q
                    / (((((ACKLAM_B[0] * r + ACKLAM_B[1]) * r + ACKLAM_B[2]) * r + ACKLAM_B[3]) * r
                    + ACKLAM_B[4]) * r + 1.0);
        }
        // One Halley step; skipped in the far tails where the CDF underflows.
        double cdf = normalCdf(x);
        if (cdf > 1e-15 && cdf < 1.0 - 1e-15) {
            double error = cdf - p;
            double u = error * Math.sqrt(2.0 * Math.PI) * Math.exp(0.5 * x * x);
            x -= u / (1.0 + 0.5 * x * u);
        }
        return x;
    }

    /** Inverse of {@link #tCdf}: the p-quantile of Student's t with df degrees of freedom. */
    public static double tQuantile(double p, double df) {
        if (!(p > 0.0) || !(p < 1.0)) {
            throw new IllegalArgumentException("tQuantile: p must be in (0, 1), got " + p);
        }
        requirePositive(df, "df");
        if (p == 0.5) {
            return 0.0;
        }
        if (p < 0.5) {
            return -tQuantile(1.0 - p, df);
        }
        double high = 1.0;
        while (tCdf(high, df) < p) {
            high *= 2.0;
            if (high > 1e300) {
                return Double.POSITIVE_INFINITY;
            }
        }
        double low = 0.0;
        for (int i = 0; i < 200; i++) {
            double middle = 0.5 * (low + high);
            if (tCdf(middle, df) < p) {
                low = middle;
            } else {
                high = middle;
            }
            if (high - low <= 1e-13 * Math.max(1.0, high)) {
                break;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * P(Q &lt;= q) for the studentized range distribution with k means and df degrees of
     * freedom: the CDF behind Tukey's HSD. This is a port of R's {@code ptukey}:
     *
     * <pre>P(Q &lt;= q) = E_s[ P(range of k standard normals &lt;= q * s) ],
     * s = sqrt(chi^2_df / df)</pre>
     *
     * <p>Both integrals are Gauss-Legendre quadrature. The outer one runs over the normal
     * deviate z with u = Phi(z) as the chi-square probability, which keeps the integrand
     * analytic and exponentially decaying instead of singular at the interval ends.</p>
     */
    public static double studentizedRangeCdf(double q, int k, double df) {
        if (k < 2) {
            throw new IllegalArgumentException("studentizedRangeCdf: k must be at least 2, got " + k);
        }
        requirePositive(df, "df");
        if (Double.isNaN(q)) {
            return Double.NaN;
        }
        if (q <= 0.0) {
            return 0.0;
        }
        if (q == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        double sum = 0.0;
        for (int i = 0; i < RANGE_NODES[0].length; i++) {
            double z = RANGE_Z_LIMIT * RANGE_NODES[0][i];
            double weight = RANGE_Z_LIMIT * RANGE_NODES[1][i];
            double probability = normalCdf(z);
            if (probability <= 0.0) {
                continue;
            }
            probability = Math.min(probability, 1.0 - 1e-16);
            double x = chiSquareQuantile(probability, df);
            double s = Math.sqrt(x / df);
            sum += weight * normalDensity(z) * rangeCdf(q * s, k);
        }
        return clamp(sum);
    }

    /** CDF of the range of k independent standard normals: k * int phi(z) [Phi(z)-Phi(z-w)]^(k-1) dz. */
    private static double rangeCdf(double w, int k) {
        if (!(w > 0.0)) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < INNER_NODES[0].length; i++) {
            double z = INNER_LIMIT * INNER_NODES[0][i];
            double weight = INNER_LIMIT * INNER_NODES[1][i];
            double bracket = normalCdf(z) - normalCdf(z - w);
            if (bracket <= 0.0) {
                continue;
            }
            double powered = 1.0;
            for (int j = 1; j < k; j++) {
                powered *= bracket;
                if (powered < 1e-300) {
                    break;
                }
            }
            sum += weight * normalDensity(z) * powered;
        }
        return clamp(k * sum);
    }

    /** Quantile of the chi-square distribution: bisection over {@link #chiSquareCdf}. */
    private static double chiSquareQuantile(double p, double df) {
        double high = df + 1.0;
        while (chiSquareCdf(high, df) < p) {
            high *= 2.0;
            if (high > 1e300) {
                return Double.POSITIVE_INFINITY;
            }
        }
        double low = 0.0;
        for (int i = 0; i < 100; i++) {
            double middle = 0.5 * (low + high);
            if (chiSquareCdf(middle, df) < p) {
                low = middle;
            } else {
                high = middle;
            }
            if (high - low <= 1e-13 * Math.max(1.0, high)) {
                break;
            }
        }
        return 0.5 * (low + high);
    }

    private static double normalDensity(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI);
    }

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    /** Gauss-Legendre nodes and weights on [-1, 1] (Newton iteration on the Legendre polynomial). */
    private static double[][] gaussLegendre(int nodes) {
        double[] abscissae = new double[nodes];
        double[] weights = new double[nodes];
        int half = (nodes + 1) / 2;
        for (int i = 0; i < half; i++) {
            double z = Math.cos(Math.PI * (i + 0.75) / (nodes + 0.5));
            double previous;
            double derivative;
            do {
                double p1 = 1.0;
                double p2 = 0.0;
                for (int j = 0; j < nodes; j++) {
                    double p3 = p2;
                    p2 = p1;
                    p1 = ((2.0 * j + 1.0) * z * p2 - j * p3) / (j + 1.0);
                }
                derivative = nodes * (z * p1 - p2) / (z * z - 1.0);
                previous = z;
                z = previous - p1 / derivative;
            } while (Math.abs(z - previous) > 1e-15);
            abscissae[i] = -z;
            abscissae[nodes - 1 - i] = z;
            weights[i] = 2.0 / ((1.0 - z * z) * derivative * derivative);
            weights[nodes - 1 - i] = weights[i];
        }
        return new double[][]{abscissae, weights};
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
