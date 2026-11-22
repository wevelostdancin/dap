package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("x", "Float64"),
            ColumnSpec.of("y", "Float64"));

    private static LazyPlan cluster(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows,
                                    String config) throws Exception {
        return new ClusterExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    /** Three well separated blobs of ten points each around (0,0), (10,0) and (0,10). */
    private static List<Object[]> threeBlobs() {
        double[][] centers = {{0.0, 0.0}, {10.0, 0.0}, {0.0, 10.0}};
        List<Object[]> rows = new ArrayList<>();
        long id = 1;
        for (double[] center : centers) {
            for (int index = 0; index < 10; index++) {
                rows.add(new Object[]{id++, center[0] + 0.05 * index, center[1] + 0.03 * index});
            }
        }
        return rows;
    }

    /** One isolated point (id 1) and a group of three; shared with tests/reference/silhouette.py. */
    private static List<Object[]> singletonAndGroup() {
        return List.of(
                new Object[]{1L, 0.0, 0.0},
                new Object[]{2L, 10.0, 0.0},
                new Object[]{3L, 10.0, 1.0},
                new Object[]{4L, 11.0, 0.0});
    }

    /** Three well separated groups of three; shared with tests/reference/silhouette.py. */
    private static List<Object[]> threeGroups() {
        return List.of(
                new Object[]{1L, 0.0, 0.0},
                new Object[]{2L, 0.0, 1.0},
                new Object[]{3L, 1.0, 0.0},
                new Object[]{4L, 10.0, 0.0},
                new Object[]{5L, 10.0, 1.0},
                new Object[]{6L, 11.0, 0.0},
                new Object[]{7L, 0.0, 10.0},
                new Object[]{8L, 1.0, 10.0},
                new Object[]{9L, 0.0, 11.0});
    }

    private static List<String> row(TableData data, String id) {
        return data.data().stream()
                .filter(row -> id.equals(row.get(0)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + id + " in " + data.data()));
    }

    private static Map<String, Integer> clusterCounts(TableData data) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> row : data.data()) {
            counts.merge(row.get(3), 1, Integer::sum);
        }
        return counts;
    }

    private static double meanSilhouette(TableData data) {
        double sum = 0.0;
        int count = 0;
        for (List<String> row : data.data()) {
            if (row.get(4) != null) {
                sum += Double.parseDouble(row.get(4));
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    @Test
    void kmeansGroupsThreeBlobsIntoThreeClusters() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = cluster(db, COLUMNS, threeBlobs(), """
                    {"columns":["x","y"],"method":"kmeans","k":3,"standardize":false,"seed":42}
                    """);

            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("cluster", "Int64"),
                    ColumnSpec.of("silhouette", "Float64")), plan.columns());
            TableData data = plan.preview(40);
            assertEquals(30L, data.rows());
            assertEquals(Map.of("0", 10, "1", 10, "2", 10), clusterCounts(data));
            for (List<String> row : data.data()) {
                double silhouette = Double.parseDouble(row.get(4));
                assertTrue(silhouette >= -1.0 && silhouette <= 1.0, "silhouette " + silhouette);
            }
            assertTrue(meanSilhouette(data) > 0.8, "mean silhouette " + meanSilhouette(data));
        }
    }

    @Test
    void kmeansIsDeterministicForTheSameSeed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            String config = """
                    {"columns":["x","y"],"method":"kmeans","k":3,"standardize":true,"seed":11}
                    """;
            TableData first = cluster(db, COLUMNS, threeBlobs(), config).preview(40);
            TableData second = cluster(db, COLUMNS, threeBlobs(), config).preview(40);
            assertEquals(first.data(), second.data());
        }
    }

    @Test
    void singletonPointGetsZeroSilhouette() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = cluster(db, COLUMNS, singletonAndGroup(), """
                    {"columns":["x","y"],"method":"kmeans","k":2,"standardize":false,"seed":42}
                    """).preview(10);

            assertEquals(4L, data.rows());
            // sklearn sets s = 0 for a singleton cluster (Rousseeuw); before the fix it was 1.0.
            assertEquals(0.0, Double.parseDouble(row(data, "1").get(4)), 0.0);
            assertEquals(0.9, Double.parseDouble(row(data, "2").get(4)), 1e-9);
            assertEquals(0.8798883860164713, Double.parseDouble(row(data, "3").get(4)), 1e-9);
            assertEquals(0.890263019892132, Double.parseDouble(row(data, "4").get(4)), 1e-9);
            // The singleton is included in the mean as a zero, not dropped and not 1.0.
            assertEquals(0.6675378514771508, meanSilhouette(data), 1e-9);
        }
    }

    @Test
    void silhouetteMatchesSklearnReferenceForThreeClusters() throws Exception {
        // Frozen from tests/reference/silhouette.py (sklearn 1.9.1), indexed by id - 1.
        double[] expected = {
                0.9033812554804784, 0.8709224512253457, 0.8709224512253457,
                0.8967293340898564, 0.8755784951211024, 0.8869939289615936,
                0.8967293340898564, 0.8755784951211024, 0.8869939289615936};
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = cluster(db, COLUMNS, threeGroups(), """
                    {"columns":["x","y"],"method":"kmeans","k":3,"standardize":false,"seed":42}
                    """).preview(20);

            assertEquals(9L, data.rows());
            Map<String, Integer> counts = clusterCounts(data);
            assertEquals(3, counts.size(), counts.toString());
            assertTrue(counts.values().stream().allMatch(size -> size == 3), counts.toString());
            for (int id = 1; id <= expected.length; id++) {
                assertEquals(expected[id - 1], Double.parseDouble(row(data, String.valueOf(id)).get(4)),
                        1e-9, "id=" + id);
            }
            assertEquals(0.8848699638084749, meanSilhouette(data), 1e-9);
        }
    }

    @Test
    void kOneLeavesSilhouetteNull() throws Exception {
        // Undefined: sklearn raises "Number of labels is 1" (reference script); DAP emits NULL.
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = cluster(db, COLUMNS, threeBlobs(), """
                    {"columns":["x","y"],"method":"kmeans","k":1,"standardize":false}
                    """).preview(40);
            assertEquals(30L, data.rows());
            assertTrue(data.data().stream().allMatch(row -> "0".equals(row.get(3))), data.data().toString());
            assertTrue(data.data().stream().allMatch(row -> row.get(4) == null), data.data().toString());
        }
    }

    @Test
    void dbscanMarksNoiseWithMinusOneAndNullSilhouette() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = threeBlobs();
            rows.add(new Object[]{31L, 100.0, 100.0});

            TableData data = cluster(db, COLUMNS, rows, """
                    {"columns":["x","y"],"method":"dbscan","eps":1.0,"min_points":3,"standardize":false}
                    """).preview(40);

            assertEquals(31L, data.rows());
            assertEquals("-1", row(data, "31").get(3));
            assertNull(row(data, "31").get(4));
            Map<String, Integer> counts = clusterCounts(data);
            assertEquals(4, counts.size(), counts.toString());
            assertEquals(1, counts.get("-1").intValue());
            assertEquals(10, counts.get("0").intValue());
            assertEquals(10, counts.get("1").intValue());
            assertEquals(10, counts.get("2").intValue());
            assertNotNull(row(data, "1").get(4));
        }
    }

    @Test
    void hierarchicalWardGroupsThreeBlobs() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = cluster(db, COLUMNS, threeBlobs(), """
                    {"columns":["x","y"],"method":"hierarchical","k":3,"linkage":"ward","standardize":false}
                    """).preview(40);

            assertEquals(30L, data.rows());
            assertEquals(Map.of("0", 10, "1", 10, "2", 10), clusterCounts(data));
            assertTrue(meanSilhouette(data) > 0.8, "mean silhouette " + meanSilhouette(data));
        }
    }

    @Test
    void nullFeaturesGetNullClusterAndSilhouette() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = threeBlobs();
            rows.add(new Object[]{31L, null, 0.0});

            TableData data = cluster(db, COLUMNS, rows, """
                    {"columns":["x","y"],"method":"kmeans","k":3,"standardize":false}
                    """).preview(40);

            assertEquals(31L, data.rows());
            assertNull(row(data, "31").get(3));
            assertNull(row(data, "31").get(4));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = threeBlobs();

            IllegalArgumentException kTooLarge = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, rows, """
                            {"columns":["x","y"],"method":"kmeans","k":50}
                            """));
            assertTrue(kTooLarge.getMessage().contains("k=50"), kTooLarge.getMessage());
            assertTrue(kTooLarge.getMessage().contains("30"), kTooLarge.getMessage());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, rows, """
                            {"columns":["nope"],"method":"kmeans"}
                            """));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, List.of(ColumnSpec.of("s", "Utf8")),
                            List.<Object[]>of(new Object[]{"x"}), """
                            {"columns":["s"],"method":"kmeans"}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, rows, """
                            {"columns":["x","y"],"method":"spectral"}
                            """));
            assertTrue(method.getMessage().contains("kmeans"), method.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, rows, """
                            {"columns":[],"method":"kmeans"}
                            """));
            assertTrue(empty.getMessage().contains("non-empty"), empty.getMessage());

            IllegalArgumentException linkage = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, rows, """
                            {"columns":["x","y"],"method":"hierarchical","linkage":"single"}
                            """));
            assertTrue(linkage.getMessage().contains("linkage"), linkage.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64"),
                                    ColumnSpec.of("cluster", "Int64")),
                            List.<Object[]>of(new Object[]{1.0, 2.0, 0L}), """
                            {"columns":["x","y"],"method":"kmeans"}
                            """));
            assertTrue(duplicate.getMessage().contains("cluster"), duplicate.getMessage());
        }
    }

    @Test
    void rowCapFailsWithAHint() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> cluster(db, COLUMNS, threeBlobs(), """
                            {"columns":["x","y"],"method":"kmeans","max_rows":10}
                            """));
            assertTrue(error.getMessage().contains("max_rows"), error.getMessage());
            assertTrue(error.getMessage().contains("pre-filter"), error.getMessage());
        }
    }
}
