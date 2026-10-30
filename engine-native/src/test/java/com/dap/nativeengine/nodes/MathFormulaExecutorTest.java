package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.ProfileColumn;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathFormulaExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("score", "Float64"),
            ColumnSpec.of("note", "Utf8"));

    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, List.of(
                new Object[]{1L, 1.5, "a"},
                new Object[]{2L, 2.5, "b"},
                new Object[]{4L, null, "c"}));
    }

    private static LazyPlan execute(DuckDbSession db, LazyPlan input, String config) throws Exception {
        NodeContext context = NodeTestSupport.context(db, config);
        return new MathFormulaExecutor().execute(context, List.of(input));
    }

    @Test
    void irisPetalAreaFromCorpusWorkflowAddsFloatColumnWithGoldenValues() throws Exception {
        Path iris = Path.of("..", "tests", "corpus", "data", "iris.csv").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(iris), "corpus fixture missing: " + iris);
        String readerConfig = "{\"file\":\"" + NodeTestSupport.path(iris) + "\"}";

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan source = new CsvReaderExecutor().execute(NodeTestSupport.context(db, job, readerConfig), List.of());
            NodeContext context = NodeTestSupport.context(db, """
                    {"outputs":[{"name":"petal_area","expression":"petal_length * petal_width"}]}
                    """);

            LazyPlan plan = new MathFormulaExecutor().execute(context, List.of(source));

            List<ColumnSpec> expected = new ArrayList<>(source.columns());
            expected.add(ColumnSpec.of("petal_area", "Float64"));
            assertEquals(expected, plan.columns());
            assertEquals("Number (Float)", plan.columns().get(5).knimeType());

            TableData data = plan.preview(10);
            assertEquals(150, data.rows());
            assertEquals(10, data.preview());
            assertEquals(6, data.data().get(0).size());
            assertEquals(List.of("5.1", "3.5", "1.4", "0.2", "setosa"),
                    data.data().get(0).subList(0, 5));
            // petal_area values computed independently from tests/corpus/data/iris.csv
            assertEquals(0.28, Double.parseDouble(data.data().get(0).get(5)), 1e-12);
            assertEquals(0.28, Double.parseDouble(data.data().get(1).get(5)), 1e-12);
            assertEquals(0.26, Double.parseDouble(data.data().get(2).get(5)), 1e-12);
            assertEquals(0.30, Double.parseDouble(data.data().get(3).get(5)), 1e-12);
            assertEquals(0.28, Double.parseDouble(data.data().get(4).get(5)), 1e-12);

            ProfileColumn profile = plan.profile().columns().get(5);
            assertEquals("petal_area", profile.name());
            assertEquals("Number (Float)", profile.type());
            assertEquals(0, profile.missing());
            assertEquals(101, profile.distinct());
            assertEquals(0.11, profile.min(), 1e-12);
            assertEquals(15.87, profile.max(), 1e-12);
            assertEquals(5.7931333333333335, profile.mean(), 1e-12);
        }
    }

    @Test
    void integerArithmeticStaysInt64() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = execute(db, input(db), """
                    {"outputs":[{"name":"sum","expression":"id + 1"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("score", "Float64"),
                    ColumnSpec.of("note", "Utf8"), ColumnSpec.of("sum", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals("Number (Integer)", plan.profile().columns().get(3).type());
            assertEquals("2", data.data().get(0).get(3));
            assertEquals("3", data.data().get(1).get(3));
            assertEquals("5", data.data().get(2).get(3));
        }
    }

    @Test
    void twoOutputsAppendInConfigOrderWithQuotedNamesAndInferredTypes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = execute(db, input(db), """
                    {"outputs":[
                      {"name":"product","expression":"id * score"},
                      {"name":"id doubled","expression":"id * 2"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("score", "Float64"),
                    ColumnSpec.of("note", "Utf8"), ColumnSpec.of("product", "Float64"),
                    ColumnSpec.of("id doubled", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "1.5", "a", "1.5", "2"), data.data().get(0));
            assertEquals(List.of("2", "2.5", "b", "5.0", "4"), data.data().get(1));
            assertEquals("Number (Float)", plan.columns().get(3).knimeType());
        }
    }

    @Test
    void nullInputsProduceNullOutputsAtTheSameRow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = execute(db, input(db), """
                    {"outputs":[{"name":"twice","expression":"id * 2"}]}
                    """);

            TableData data = plan.preview(10);
            assertEquals("2", data.data().get(0).get(3));
            assertEquals("4", data.data().get(1).get(3));
            assertEquals("8", data.data().get(2).get(3));

            LazyPlan withNull = execute(db, input(db), """
                    {"outputs":[{"name":"shifted","expression":"score + 1"}]}
                    """);
            TableData shifted = withNull.preview(10);
            assertEquals("2.5", shifted.data().get(0).get(3));
            assertEquals("3.5", shifted.data().get(1).get(3));
            assertNull(shifted.data().get(2).get(3));
        }
    }

    @Test
    void unknownColumnAndSyntaxErrorsNameTheOutput() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input(db), """
                            {"outputs":[{"name":"broken","expression":"nope + 1"}]}
                            """));
            assertTrue(unknownColumn.getMessage().contains("broken"), unknownColumn.getMessage());
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException syntax = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input(db), """
                            {"outputs":[{"name":"oops","expression":"id +"}]}
                            """));
            assertTrue(syntax.getMessage().contains("oops"), syntax.getMessage());

            IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input(db), """
                            {"outputs":[{"name":"blobby","expression":"CAST(id AS BLOB)"}]}
                            """));
            assertTrue(unsupported.getMessage().contains("blobby"), unsupported.getMessage());
            assertTrue(unsupported.getMessage().contains("BLOB"), unsupported.getMessage());
        }
    }

    @Test
    void expressionsCannotChainOnOtherOutputsOfTheSameNode() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input(db), """
                            {"outputs":[
                              {"name":"derived","expression":"id + 1"},
                              {"name":"chained","expression":"derived * 2"}]}
                            """));
            assertTrue(failure.getMessage().contains("chained"), failure.getMessage());
            assertTrue(failure.getMessage().contains("derived"), failure.getMessage());
        }
    }

    @Test
    void outputsMustBeANonEmptyArrayWithNamedNonBlankEntriesAndUniqueNames() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, "{}"));
            assertTrue(missing.getMessage().contains("outputs"), missing.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":[]}
                            """));
            assertTrue(empty.getMessage().contains("outputs"), empty.getMessage());

            IllegalArgumentException notArray = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":{"name":"x","expression":"id + 1"}}
                            """));
            assertTrue(notArray.getMessage().contains("outputs"), notArray.getMessage());

            IllegalArgumentException blankName = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":[{"name":"  ","expression":"id + 1"}]}
                            """));
            assertTrue(blankName.getMessage().contains("output 1"), blankName.getMessage());
            assertTrue(blankName.getMessage().contains("name"), blankName.getMessage());

            IllegalArgumentException blankExpression = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":[{"name":"oops","expression":"   "}]}
                            """));
            assertTrue(blankExpression.getMessage().contains("oops"), blankExpression.getMessage());
            assertTrue(blankExpression.getMessage().contains("expression"), blankExpression.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":[
                              {"name":"dup","expression":"id + 1"},
                              {"name":"dup","expression":"id + 2"}]}
                            """));
            assertTrue(duplicate.getMessage().contains("dup"), duplicate.getMessage());

            IllegalArgumentException inputCollision = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, input, """
                            {"outputs":[{"name":"id","expression":"id + 1"}]}
                            """));
            assertTrue(inputCollision.getMessage().contains("id"), inputCollision.getMessage());
            assertTrue(inputCollision.getMessage().contains("input"), inputCollision.getMessage());
        }
    }
}

