package com.dap.engine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeModel;
import org.osgi.framework.Bundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Диагностический прогон: строит схемы диалогов для всего каталога без HTTP.
 * Нужен для замера покрытия и разбора причин отказов.
 */
public class SchemaAudit implements IApplication {

    @Override
    public Object start(final IApplicationContext context) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final String[] args = (String[]) context.getArguments().get("application.args");
        String catalogPath = "catalog.json";
        String out = "dialog-report.json";
        String schemasDir = null;
        String curatedPath = null;
        boolean diagnose = false;
        boolean rebuild = false;
        for (final String a : args) {
            if (a.startsWith("-catalog=")) {
                catalogPath = a.substring("-catalog=".length());
            } else if (a.startsWith("-out=")) {
                out = a.substring("-out=".length());
            } else if (a.startsWith("-schemas=")) {
                schemasDir = a.substring("-schemas=".length());
            } else if (a.startsWith("-curated=")) {
                curatedPath = a.substring("-curated=".length());
            } else if (a.equals("-diagnose")) {
                diagnose = true;
            } else if (a.equals("-rebuild")) {
                rebuild = true;
            }
        }
        try {
            org.knime.core.internal.CorePlugin.getInstance();
            final Bundle wbCore = Platform.getBundle("org.knime.workbench.core");
            if (wbCore != null) {
                try {
                    wbCore.start(Bundle.START_TRANSIENT);
                } catch (final Exception e) {
                    System.out.println("[probe] workbench.core not started: " + e.getMessage());
                }
            }
            final NodeCatalog catalog = NodeCatalog.loadOrBuild(new File(catalogPath));
            if (rebuild) {
                catalog.rebuild();
            }
            final Tiers tiers = Tiers.load(curatedPath == null ? null : new File(curatedPath));
            final int core = tiers.apply(catalog);
            catalog.save(new File(catalogPath));
            if (schemasDir != null) {
                new File(schemasDir).mkdirs();
            }
            final ObjectMapper mapper = new ObjectMapper();
            final ArrayNode rows = mapper.createArrayNode();
            final Map<String, Integer> reasons = new TreeMap<>();
            int ok = 0;
            int swing = 0;
            int annotations = 0;
            int fields = 0;
            int labelled = 0;
            final long t0 = System.currentTimeMillis();
            for (final ObjectNode node : catalog.all()) {
                final String id = node.path("id").asText();
                final ObjectNode row = mapper.createObjectNode();
                row.put("id", id);
                final long n0 = System.currentTimeMillis();
                try {
                    final NodeFactory<?> factory = catalog.newFactory(id);
                    final ObjectNode d = DialogSchema.describeNode(factory,
                            SwingDialogSchema.keysOf(node.path("settingsXml").asText("")));
                    row.setAll(d);
                    if (d.path("available").asBoolean()) {
                        ok++;
                        final String kind = d.path("schemaKind").asText();
                        if ("swing".equals(kind)) {
                            swing++;
                        } else {
                            annotations++;
                        }
                        final int n = d.path("schema").path("fields").size();
                        fields += n;
                        final String coverage = d.path("schema").path("labelCoverage").asText("");
                        if (coverage.contains("/")) {
                            labelled += Integer.parseInt(coverage.substring(0, coverage.indexOf('/')));
                        }
                    } else {
                        final String reason = d.path("reason").asText("?");
                        reasons.merge(reason, 1, Integer::sum);
                    }
                } catch (final Throwable t) {
                    row.put("available", false);
                    row.put("reason", "probe: " + t);
                    reasons.merge("probe: " + t.getClass().getSimpleName(), 1, Integer::sum);
                }
                if (!row.path("available").asBoolean() && diagnose) {
                    try {
                        diagnose(catalog.newFactory(id), row);
                    } catch (final Throwable t) {
                        row.put("diagError", String.valueOf(t));
                    }
                }
                row.put("tier", node.path("tier").asText(Tiers.EXPERIMENTAL));
                row.put("ms", System.currentTimeMillis() - n0);
                rows.add(row);
                if (schemasDir != null) {
                    final ObjectNode schema = row.deepCopy();
                    schema.remove("ms");
                    Files.write(new File(schemasDir, id + ".json").toPath(),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(schema));
                }
            }
            final ObjectNode report = mapper.createObjectNode();
            report.put("nodes", catalog.size());
            report.put("core", core);
            report.set("missing", missingFromRegistry(catalog, mapper));
            report.set("catalogFailures", mapper.valueToTree(catalog.failures()));
            report.set("catalogFailureTraces", mapper.valueToTree(catalog.failureTraces()));
            report.put("available", ok);
            report.put("swing", swing);
            report.put("annotations", annotations);
            report.put("fields", fields);
            report.put("labelled", labelled);
            report.put("ms", System.currentTimeMillis() - t0);
            reasons.forEach((k, v) -> report.putObject("reasons").put(k, v));
            report.set("details", rows);
            Files.write(new File(out).toPath(),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
            System.out.println("[probe] nodes=" + catalog.size() + " core=" + core + " available=" + ok
                    + " (swing=" + swing + ", annotations=" + annotations + ")"
                    + " fields=" + fields + " labelled=" + labelled
                    + " ms=" + (System.currentTimeMillis() - t0));
            reasons.forEach((k, v) -> System.out.println("[probe] reason " + v + "x " + k));
            return IApplication.EXIT_OK;
        } catch (final Throwable t) {
            System.err.println("[probe] FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    /** Узлы из extension point, которые не попали в каталог, с причиной. */
    private static ArrayNode missingFromRegistry(final NodeCatalog catalog, final ObjectMapper mapper) {
        final ArrayNode missing = mapper.createArrayNode();
        try {
            final org.eclipse.core.runtime.IExtensionPoint ep = Platform.getExtensionRegistry()
                    .getExtensionPoint("org.knime.workbench.repository.nodes");
            final java.util.Set<String> known = new java.util.HashSet<>();
            for (final ObjectNode n : catalog.all()) {
                known.add(n.path("id").asText());
            }
            for (final org.eclipse.core.runtime.IConfigurationElement ce : ep.getConfigurationElements()) {
                final String id = ce.getAttribute("factory-class");
                if (id == null || known.contains(id)) {
                    continue;
                }
                final ObjectNode row = missing.addObject();
                row.put("id", id);
                row.put("contributor", ce.getContributor() == null ? "" : ce.getContributor().getName());
                try {
                    final Bundle bundle = Platform.getBundle(row.path("contributor").asText());
                    final Class<?> clazz = bundle.loadClass(id);
                    final NodeFactory<?> factory = (NodeFactory<?>) clazz.getDeclaredConstructor().newInstance();
                    factory.init();
                    row.put("configurable", factory instanceof org.knime.core.node.ConfigurableNodeFactory);
                    try {
                        final Object cfg = factory instanceof org.knime.core.node.ConfigurableNodeFactory
                                ? ((org.knime.core.node.ConfigurableNodeFactory<?>) factory).createNodeCreationConfig() : null;
                        row.put("cfg", cfg == null ? "null" : cfg.getClass().getName());
                        NodeCatalog.createModel(factory);
                        row.put("model", "ok");
                    } catch (final Throwable t) {
                        row.put("error", shortTrace(t));
                        row.put("trace", trace(t));
                    }
                } catch (final Throwable t) {
                    row.put("loadError", shortTrace(t));
                }
            }
        } catch (final Throwable t) {
            missing.addObject().put("error", String.valueOf(t));
        }
        return missing;
    }

    /** Почему узел не отдал схему: что вернули фабричные методы и что лежит в модели. */
    private static void diagnose(final NodeFactory<?> factory, final ObjectNode row) {
        row.put("factoryClass", factory.getClass().getName());
        try {
            final Object dialog = call(factory, "createNodeDialog");
            row.put("nodeDialog", dialog == null ? "null" : dialog.getClass().getName());
            if (dialog != null) {
                final Object classes = call(dialog, "getSettingsClasses");
                if (classes instanceof java.util.Map) {
                    final ObjectNode m = row.putObject("settingsClasses");
                    ((java.util.Map<?, ?>) classes).forEach((k, v) -> m.put(String.valueOf(k),
                            v == null ? "null" : ((Class<?>) v).getName()));
                }
            }
        } catch (final Throwable t) {
            row.put("nodeDialogError", shortTrace(t));
        }
        try {
            final Object pane = call(factory, "createNodeDialogPane");
            row.put("pane", pane == null ? "null" : pane.getClass().getName());
            if (pane != null) {
                final ObjectNode ui = row.putObject("ui");
                countUi(pane instanceof java.awt.Container ? (java.awt.Container) pane : null, ui);
            }
        } catch (final Throwable t) {
            row.put("paneError", shortTrace(t));
            row.put("paneTrace", trace(t));
        }
        try {
            final NodeModel model = NodeCatalog.createModel(factory);
            final ArrayNode fields = row.putArray("modelFields");
            for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (final java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    final ObjectNode o = fields.addObject();
                    o.put("name", f.getName());
                    o.put("type", f.getType().getSimpleName());
                }
            }
            row.put("model", model.getClass().getName());
        } catch (final Throwable t) {
            row.put("modelError", shortTrace(t));
            row.put("modelTrace", trace(t));
        }
    }

    private static Object call(final Object target, final String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (final NoSuchMethodException ignored) {
                // ищем выше
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void countUi(final java.awt.Container root, final ObjectNode out) {
        if (root == null) {
            return;
        }
        final Map<String, Integer> counts = new TreeMap<>();
        final java.util.ArrayDeque<java.awt.Component> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && counts.values().stream().mapToInt(Integer::intValue).sum() < 2000) {
            final java.awt.Component c = queue.poll();
            counts.merge(c.getClass().getSimpleName(), 1, Integer::sum);
            if (c instanceof java.awt.Container) {
                for (final java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                    queue.add(child);
                }
            }
        }
        counts.forEach(out::put);
    }

    private static String trace(final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        int n = 0;
        for (final StackTraceElement e : c.getStackTrace()) {
            if (n++ >= 12) {
                break;
            }
            sb.append(e).append(" | ");
        }
        return sb.toString();
    }

    private static String shortTrace(final Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        final String s = c.getClass().getSimpleName() + ": " + c.getMessage();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    @Override
    public void stop() {
    }
}
