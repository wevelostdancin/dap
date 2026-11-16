package com.dap.nativeengine.api;

import com.dap.nativeengine.catalog.NodeSpec;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.ProfileColumn;
import com.dap.nativeengine.engine.ProfileData;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowNode;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Table previews, column profiles and plan estimates of executed workflow nodes. */
public final class DataHandlers {
    private static final int DEFAULT_LIMIT = 50;
    /** Tokens that would turn a 'where' predicate into a second statement or a file read. */
    private static final List<String> FORBIDDEN_FILTER_TOKENS = List.of(";", "--", "/*", "select",
            "from", "read_", "attach", "copy", "pragma");

    private final ServiceState state;

    public DataHandlers(ServiceState state) {
        this.state = state;
    }

    public void table(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = node(workflow, nodeId);
        NodeSpec spec = spec(node);
        int port = Http.queryInt(ctx, "port", 0);
        int limit = Math.max(0, Http.queryInt(ctx, "limit", DEFAULT_LIMIT));
        int offset = Math.max(0, Http.queryInt(ctx, "offset", 0));
        LazyPlan plan = materialized(workflow, node,
                spec.outPorts().stream().map(NodeSpec.Port::toMap).toList());
        int outPorts = spec.outPorts().size();
        if (port < 0 || port >= outPorts) {
            throw ApiException.badRequest("node has " + outPorts + " out ports");
        }
        List<ColumnSpec> columns = projection(plan.columns(), ctx.queryParam("columns"));
        String where = filter(ctx.queryParam("where"));
        String relation = where == null ? plan.sql()
                : "SELECT * FROM (" + LazyPlan.stripSemicolon(plan.sql()) + ") AS dap_filtered WHERE "
                        + where;
        String pageSql = projectionSql(relation, columns);
        TableData table;
        try {
            table = plan.page(pageSql, columns, limit, offset, timeoutMillis(), workflow.id(), nodeId);
        } catch (SQLException failure) {
            if (where != null) {
                throw ApiException.badRequest("invalid_filter",
                        "invalid where predicate: " + failure.getMessage(),
                        "the predicate must be a boolean SQL expression over the output columns");
            }
            throw failure;
        }
        long consumed = offset + table.data().size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", table.rows());
        body.put("columns", columns(table.columns()));
        body.put("data", table.data());
        body.put("preview", table.preview());
        body.put("offset", offset);
        body.put("nextOffset", consumed < table.rows() ? consumed : null);
        body.put("truncated", consumed < table.rows());
        Http.ok(ctx, body);
    }

    /** EXPLAIN (FORMAT JSON) over the node plan: estimated rows and the operator tree. */
    public void estimate(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = node(workflow, nodeId);
        spec(node);
        LazyPlan plan = node.result();
        if (plan == null) {
            if ("ERROR".equals(node.state())) {
                throw ApiException.conflict("node_failed",
                        node.message() == null ? "node failed: " + node.id() : node.message(),
                        "fix the node configuration and run the workflow again");
            }
            throw new ApiException(409, "estimate_unavailable", "node has no plan to estimate",
                    "run the workflow first: POST /v1/workflows/" + workflow.id() + "/run");
        }
        JsonNode parsed = Json.parse(plan.explainJson());
        // DuckDB returns the physical plan array; wrap it so the response always has the
        // documented {physical_plan: [...]} shape.
        com.fasterxml.jackson.databind.node.ObjectNode root = Json.object();
        JsonNode physical;
        if (parsed.isObject()) {
            root = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            physical = root.path("physical_plan");
        } else {
            root.set("physical_plan", parsed);
            physical = parsed;
        }
        List<Map<String, Object>> operators = new ArrayList<>();
        collectOperators(physical, operators);
        Long rows = estimatedRows(physical.isArray() && physical.size() > 0 ? physical.get(0) : null);
        if (rows == null) {
            // A projection root often carries no estimate; the first operator that has one
            // (the scan) is the honest number to report.
            rows = firstEstimated(physical);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", nodeId);
        body.put("rows", rows == null ? 0L : rows);
        body.put("operators", operators);
        body.put("plan", root);
        Http.ok(ctx, body);
    }

    public void profile(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = node(workflow, nodeId);
        spec(node);
        LazyPlan plan = materialized(workflow, node, null);
        ProfileData profile = plan.profile(timeoutMillis(), workflow.id(), nodeId);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ProfileColumn column : profile.columns()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", column.name());
            view.put("type", column.type());
            view.put("missing", column.missing());
            view.put("distinct", column.distinct());
            // Numeric-only statistics; a null aggregate (all-missing column) is omitted.
            if (column.min() != null) {
                view.put("min", column.min());
            }
            if (column.max() != null) {
                view.put("max", column.max());
            }
            if (column.mean() != null) {
                view.put("mean", column.mean());
            }
            columns.add(view);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", profile.rows());
        body.put("columns", columns);
        Http.ok(ctx, body);
    }

    /** DAP_QUERY_TIMEOUT_SEC bounds every /table and /profile materialization. */
    private long timeoutMillis() {
        return state.settings().queryTimeoutSec() * 1000L;
    }

    /** Unknown workflow node: 404 unknown_node on every data route. */
    private static WorkflowNode node(Workflow workflow, String nodeId) {
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.notFound("unknown_node", "unknown node: " + nodeId,
                    "GET /v1/workflows/" + workflow.id() + "/nodes lists the node ids");
        }
        return node;
    }

    /**
     * The node's materialized plan: 409 node_failed when the node failed (with the node
     * message) and 409 not_materialized before a run. Ports are only reported by /table,
     * which had them in its 400 body before the code was pinned.
     */
    private static LazyPlan materialized(Workflow workflow, WorkflowNode node,
                                         List<Map<String, Object>> ports) {
        LazyPlan plan = node.result();
        if (plan != null) {
            return plan;
        }
        if ("ERROR".equals(node.state())) {
            throw ApiException.conflict("node_failed",
                    node.message() == null ? "node failed: " + node.id() : node.message(),
                    "fix the node configuration and run the workflow again");
        }
        String hint = "run the workflow first: POST /v1/workflows/" + workflow.id() + "/run";
        if (ports == null) {
            throw new ApiException(409, "not_materialized", "node has no table output", hint);
        }
        throw new ApiException(409, "not_materialized", "node has no table output", hint,
                Map.of("ports", ports));
    }

    /**
     * Optional 'columns' projection: a comma-separated list of output column names. An unknown
     * name is 400 unknown_column naming the available columns.
     */
    private static List<ColumnSpec> projection(List<ColumnSpec> available, String raw) {
        if (raw == null || raw.isBlank()) {
            return available;
        }
        List<ColumnSpec> projected = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String name = entry.strip();
            if (name.isEmpty()) {
                continue;
            }
            ColumnSpec match = null;
            for (ColumnSpec column : available) {
                if (column.name().equals(name)) {
                    match = column;
                    break;
                }
            }
            if (match == null) {
                throw ApiException.badRequest("unknown_column",
                        "unknown column in 'columns': " + name,
                        "available columns: " + String.join(", ",
                                available.stream().map(ColumnSpec::name).toList()));
            }
            projected.add(match);
        }
        return projected.isEmpty() ? available : projected;
    }

    /** Optional 'where' predicate; anything that could escape the predicate is 400 invalid_filter. */
    private static String filter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN_FILTER_TOKENS) {
            if (lower.contains(token)) {
                throw ApiException.badRequest("invalid_filter",
                        "'where' must be a single boolean predicate over the output columns; '"
                                + token + "' is not allowed",
                        "use a plain expression such as \"amount > 10 AND region = 'EU'\"");
            }
        }
        return raw;
    }

    private static String projectionSql(String relation, List<ColumnSpec> columns) {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            projection.add("\"" + column.name().replace("\"", "\"\"") + "\"");
        }
        return "SELECT " + projection + " FROM (" + LazyPlan.stripSemicolon(relation) + ") AS dap_projection";
    }

    private static void collectOperators(JsonNode plan, List<Map<String, Object>> operators) {
        if (plan == null || !plan.isArray()) {
            return;
        }
        for (JsonNode operator : plan) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", operator.path("name").asText(""));
            Long rows = estimatedRows(operator);
            view.put("rows", rows == null ? 0L : rows);
            operators.add(view);
            collectOperators(operator.path("children"), operators);
        }
    }

    private static Long firstEstimated(JsonNode plan) {
        if (plan == null || !plan.isArray()) {
            return null;
        }
        for (JsonNode operator : plan) {
            Long rows = estimatedRows(operator);
            if (rows != null) {
                return rows;
            }
            Long child = firstEstimated(operator.path("children"));
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    /** DuckDB puts the estimate into extra_info."Estimated Cardinality" as a number or string. */
    private static Long estimatedRows(JsonNode operator) {
        if (operator == null) {
            return null;
        }
        JsonNode cardinality = operator.path("extra_info").path("Estimated Cardinality");
        if (cardinality.isNumber()) {
            return cardinality.asLong();
        }
        if (cardinality.isTextual()) {
            try {
                return Long.parseLong(cardinality.asText().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private NodeSpec spec(WorkflowNode node) {
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.notFound("unknown_node", "unknown node: " + node.factory(),
                    "GET /v1/nodes lists the available node types");
        }
        return spec;
    }

    private static List<Map<String, Object>> columns(List<ColumnSpec> specs) {
        List<Map<String, Object>> columns = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("index", index);
            column.put("name", specs.get(index).name());
            column.put("type", specs.get(index).knimeType());
            columns.add(column);
        }
        return columns;
    }
}
