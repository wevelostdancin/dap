package com.dap.nativeengine.engine;

/**
 * Small dense linear algebra for the statistics nodes: Gauss-Jordan inversion with
 * partial pivoting, multiplication, transpose and solve.
 *
 * <p>The design matrices of the regression nodes are a handful of columns wide, so the
 * plain O(n^3) elimination is enough and no external linear algebra dependency is
 * needed. A pivot that is negligible relative to the largest entry of the matrix is
 * reported as a singular matrix instead of producing infinities.</p>
 */
public final class Matrix {
    /** Relative pivot threshold: below this fraction of the matrix scale the system is singular. */
    private static final double SINGULAR_TOLERANCE = 1e-14;

    private Matrix() {
    }

    /** n x n identity matrix. */
    public static double[][] identity(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("matrix: identity size must be positive, got " + size);
        }
        double[][] result = new double[size][size];
        for (int i = 0; i < size; i++) {
            result[i][i] = 1.0;
        }
        return result;
    }

    /** Transpose of a non-empty rectangular matrix. */
    public static double[][] transpose(double[][] matrix) {
        requireNonEmpty(matrix, "transpose");
        int rows = matrix.length;
        int columns = matrix[0].length;
        double[][] result = new double[columns][rows];
        for (int i = 0; i < rows; i++) {
            requireRowLength(matrix, i, columns, "transpose");
            for (int j = 0; j < columns; j++) {
                result[j][i] = matrix[i][j];
            }
        }
        return result;
    }

    /** Matrix product a x b. */
    public static double[][] multiply(double[][] a, double[][] b) {
        requireNonEmpty(a, "multiply");
        requireNonEmpty(b, "multiply");
        int rows = a.length;
        int inner = a[0].length;
        int columns = b[0].length;
        for (int i = 0; i < rows; i++) {
            requireRowLength(a, i, inner, "multiply");
        }
        if (b.length != inner) {
            throw new IllegalArgumentException("matrix: cannot multiply " + rows + "x" + inner
                    + " by " + b.length + "x" + columns);
        }
        for (int i = 0; i < b.length; i++) {
            requireRowLength(b, i, columns, "multiply");
        }
        double[][] result = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < inner; k++) {
                double left = a[i][k];
                if (left == 0.0) {
                    continue;
                }
                for (int j = 0; j < columns; j++) {
                    result[i][j] += left * b[k][j];
                }
            }
        }
        return result;
    }

    /** Inverse of a square matrix, or IllegalArgumentException when it is singular. */
    public static double[][] invert(double[][] matrix) {
        int size = squareSize(matrix, "invert");
        double[][] work = new double[size][2 * size];
        for (int i = 0; i < size; i++) {
            requireRowLength(matrix, i, size, "invert");
            System.arraycopy(matrix[i], 0, work[i], 0, size);
            work[i][size + i] = 1.0;
        }
        eliminate(work, size, "invert");
        double[][] result = new double[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(work[i], size, result[i], 0, size);
        }
        return result;
    }

    /** Solves a x = b for a square matrix a. */
    public static double[] solve(double[][] matrix, double[] rightHandSide) {
        int size = squareSize(matrix, "solve");
        if (rightHandSide == null || rightHandSide.length != size) {
            throw new IllegalArgumentException("matrix: solve needs a right-hand side of length " + size
                    + ", got " + (rightHandSide == null ? "null" : rightHandSide.length));
        }
        double[][] work = new double[size][size + 1];
        for (int i = 0; i < size; i++) {
            requireRowLength(matrix, i, size, "solve");
            System.arraycopy(matrix[i], 0, work[i], 0, size);
            work[i][size] = rightHandSide[i];
        }
        eliminate(work, size, "solve");
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = work[i][size];
        }
        return result;
    }

    /**
     * Gauss-Jordan elimination to reduced row echelon form on the first {@code size}
     * columns of an augmented matrix; the remaining columns become the inverse or the
     * solution.
     */
    private static void eliminate(double[][] work, int size, String operation) {
        // Scale from the coefficient block only: the identity/solution columns must not
        // decide whether the system counts as singular.
        double scale = 0.0;
        for (double[] row : work) {
            for (int j = 0; j < size; j++) {
                scale = Math.max(scale, Math.abs(row[j]));
            }
        }
        double tolerance = scale * SINGULAR_TOLERANCE;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][column]) <= tolerance) {
                throw new IllegalArgumentException("matrix: " + operation + " failed, the matrix is singular");
            }
            if (pivot != column) {
                double[] swap = work[column];
                work[column] = work[pivot];
                work[pivot] = swap;
            }
            double divisor = work[column][column];
            for (int j = column; j < work[column].length; j++) {
                work[column][j] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }
                double factor = work[row][column];
                if (factor == 0.0) {
                    continue;
                }
                for (int j = column; j < work[row].length; j++) {
                    work[row][j] -= factor * work[column][j];
                }
            }
        }
    }

    private static int squareSize(double[][] matrix, String operation) {
        requireNonEmpty(matrix, operation);
        int size = matrix.length;
        for (int i = 0; i < size; i++) {
            if (matrix[i] == null || matrix[i].length != size) {
                throw new IllegalArgumentException("matrix: " + operation + " needs a square matrix, row "
                        + i + " has " + (matrix[i] == null ? "no entries" : matrix[i].length + " entries")
                        + " and the matrix has " + size + " rows");
            }
        }
        return size;
    }

    private static void requireNonEmpty(double[][] matrix, String operation) {
        if (matrix == null || matrix.length == 0) {
            throw new IllegalArgumentException("matrix: " + operation + " needs a non-empty matrix");
        }
    }

    private static void requireRowLength(double[][] matrix, int row, int expected, String operation) {
        if (matrix[row] == null || matrix[row].length != expected) {
            throw new IllegalArgumentException("matrix: " + operation + " needs rectangular rows of length "
                    + expected + ", row " + row + " has "
                    + (matrix[row] == null ? "no entries" : matrix[row].length + " entries"));
        }
    }
}
