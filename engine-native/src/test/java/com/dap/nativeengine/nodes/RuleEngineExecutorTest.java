package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineExecutorTest {
    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("score", "Int64"), ColumnSpec.of("name", "Utf8")),
                List.of(
                        new Object[]{95L, "a"},
                        new Object[]{60L, "b"},
                        new Object[]{10L, "a"}));
    }

    @Test
    void firstMatchingRuleWinsAndElseValueFillsTheRest() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                    {"output":"grade","rules":[
                      {"condition":"score >= 90","value":3},
                      {"condition":"score >= 50","value":2}],
                     "elseValue":1}
                    """), List.of(input(db)));

            assertEquals(List.of(ColumnSpec.of("score", "Int64"), ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("grade", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("95", "a", "3"), data.data().get(0));
            assertEquals(List.of("60", "b", "2"), data.data().get(1));
            assertEquals(List.of("10", "a", "1"), data.data().get(2));
        }
    }

    @Test
    void textRulesInferUtf8AndMissingElseValueMeansNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                    {"output":"label","rules":[{"condition":"name = 'a'","value":"A"}]}
                    """), List.of(input(db)));

            assertEquals(ColumnSpec.of("label", "Utf8"), plan.columns().get(2));
            TableData data = plan.preview(10);
            assertEquals("A", data.data().get(0).get(2));
            assertNull(data.data().get(1).get(2));
            assertEquals("A", data.data().get(2).get(2));
        }
    }

    @Test
    void fractionalValuesInferFloat64() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                    {"output":"weight","rules":[
                      {"condition":"score >= 50","value":1.5}],
                     "elseValue":0.25}
                    """), List.of(input(db)));

            assertEquals(ColumnSpec.of("weight", "Float64"), plan.columns().get(2));
            TableData data = plan.preview(10);
            assertEquals("1.5", data.data().get(0).get(2));
            assertEquals("0.25", data.data().get(2).get(2));
        }
    }

    @Test
    void invalidRulesAndNonBooleanConditionsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                            {"output":"x","rules":[]}
                            """), List.of(input)));
            assertTrue(empty.getMessage().contains("rules"), empty.getMessage());

            IllegalArgumentException missingValue = assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                            {"output":"x","rules":[{"condition":"score > 1"}]}
                            """), List.of(input)));
            assertTrue(missingValue.getMessage().contains("value"), missingValue.getMessage());

            IllegalArgumentException notBoolean = assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                            {"output":"x","rules":[{"condition":"score + 1","value":1}]}
                            """), List.of(input)));
            assertTrue(notBoolean.getMessage().contains("boolean"), notBoolean.getMessage());

            IllegalArgumentException array = assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                            {"output":"x","rules":[{"condition":"score > 1","value":[1,2]}]}
                            """), List.of(input)));
            assertTrue(array.getMessage().contains("scalar"), array.getMessage());
        }
    }

    @Test
    void outputNameMustNotDuplicateAnInputColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(NodeTestSupport.context(db, """
                            {"output":"score","rules":[{"condition":"score > 1","value":1}]}
                            """), List.of(input(db))));
            assertTrue(failure.getMessage().contains("score"), failure.getMessage());
        }
    }
}