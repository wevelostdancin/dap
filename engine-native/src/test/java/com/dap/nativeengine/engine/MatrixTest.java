package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Small dense linear algebra used by the regression and PCA nodes. */
class MatrixTest {
    private static final double TOLERANCE = 1e-12;

    private static void assertMatrix(double[][] expected, double[][] actual, double tolerance) {
        assertEquals(expected.length, actual.length, "rows");
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], tolerance, "row " + i);
        }
    }

    @Test
    void identityIsTheMultiplicativeUnit() {
        double[][] matrix = {{1, 2, 3}, {4, 5, 6}, {7, 8, 10}};
        assertMatrix(matrix, Matrix.multiply(Matrix.identity(3), matrix), 0.0);
        assertMatrix(matrix, Matrix.multiply(matrix, Matrix.identity(3)), 0.0);
    }

    @Test
    void multiplyUsesRowByColumnProducts() {
        assertMatrix(new double[][]{{19, 22}, {43, 50}},
                Matrix.multiply(new double[][]{{1, 2}, {3, 4}},
                        new double[][]{{5, 6}, {7, 8}}), 0.0);
        assertMatrix(new double[][]{{5, 10, 15}, {10, 20, 30}, {15, 30, 45}},
                Matrix.multiply(new double[][]{{1}, {2}, {3}},
                        new double[][]{{5, 10, 15}}), 0.0);
    }

    @Test
    void transposeSwapsRowsAndColumns() {
        assertMatrix(new double[][]{{1, 4}, {2, 5}, {3, 6}},
                Matrix.transpose(new double[][]{{1, 2, 3}, {4, 5, 6}}), 0.0);
    }

    @Test
    void invertProducesTheInverse() {
        double[][] inverse = Matrix.invert(new double[][]{{4, 7}, {2, 6}});
        assertMatrix(new double[][]{{0.6, -0.7}, {-0.2, 0.4}}, inverse, TOLERANCE);

        double[][] matrix = {{2, -1, 0}, {-1, 2, -1}, {0, -1, 2}};
        assertMatrix(Matrix.identity(3), Matrix.multiply(matrix, Matrix.invert(matrix)), 1e-12);
    }

    @Test
    void invertRejectsSingularAndNonSquareMatrices() {
        IllegalArgumentException singular = assertThrows(IllegalArgumentException.class,
                () -> Matrix.invert(new double[][]{{1, 2}, {2, 4}}));
        assertEquals(true, singular.getMessage().contains("singular"), singular.getMessage());

        IllegalArgumentException nonSquare = assertThrows(IllegalArgumentException.class,
                () -> Matrix.invert(new double[][]{{1, 2, 3}, {4, 5, 6}}));
        assertEquals(true, nonSquare.getMessage().contains("square"), nonSquare.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Matrix.invert(new double[0][0]));
        assertEquals(true, empty.getMessage().contains("empty"), empty.getMessage());
    }

    @Test
    void solveSolvesLinearSystems() {
        double[] solution = Matrix.solve(new double[][]{{2, 3}, {1, -1}}, new double[]{8, -1});
        assertArrayEquals(new double[]{1, 2}, solution, TOLERANCE);

        double[] three = Matrix.solve(
                new double[][]{{1, 1, 1}, {0, 2, 5}, {2, 5, -1}},
                new double[]{6, -4, 27});
        assertArrayEquals(new double[]{5, 3, -2}, three, TOLERANCE);
    }

    @Test
    void solveRejectsSingularAndMismatchedSystems() {
        assertThrows(IllegalArgumentException.class,
                () -> Matrix.solve(new double[][]{{1, 2}, {2, 4}}, new double[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> Matrix.solve(new double[][]{{1, 2}, {3, 4}}, new double[]{1}));
    }

    @Test
    void symmetricEigenDecomposesAKnownMatrix() {
        Matrix.Eigen eigen = Matrix.symmetricEigen(new double[][]{{2, 1}, {1, 2}});
        assertArrayEquals(new double[]{3.0, 1.0}, eigen.values(), TOLERANCE);
        double[][] vectors = eigen.vectors();
        assertEquals(1.0, vectors[0][0] * vectors[0][0] + vectors[0][1] * vectors[0][1], TOLERANCE);
        assertEquals(1.0, vectors[1][0] * vectors[1][0] + vectors[1][1] * vectors[1][1], TOLERANCE);
        assertEquals(0.0, vectors[0][0] * vectors[1][0] + vectors[0][1] * vectors[1][1], TOLERANCE);
        double[][] reconstructed = new double[2][2];
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                for (int component = 0; component < 2; component++) {
                    reconstructed[row][column] += vectors[component][row] * eigen.values()[component]
                            * vectors[component][column];
                }
            }
        }
        assertMatrix(new double[][]{{2, 1}, {1, 2}}, reconstructed, TOLERANCE);
    }

    @Test
    void symmetricEigenSortsDescendingAndRejectsAsymmetricMatrices() {
        Matrix.Eigen diagonal = Matrix.symmetricEigen(new double[][]{
                {1, 0, 0}, {0, 3, 0}, {0, 0, 2}});
        assertArrayEquals(new double[]{3.0, 2.0, 1.0}, diagonal.values(), TOLERANCE);
        assertThrows(IllegalArgumentException.class,
                () -> Matrix.symmetricEigen(new double[][]{{1, 2}, {3, 4}}));
    }
}
