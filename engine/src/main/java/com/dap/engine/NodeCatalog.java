package com.dap.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.core.node.ConfigurableNodeFactory;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeModel;
import org.knime.core.node.context.NodeCreationConfiguration;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.port.PortType;
import org.osgi.framework.Bundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Каталог узлов KNIME: обход extension point, инстанцирование фабрик,
 * сбор портов и схемы настроек. Кэшируется в JSON-файл.
 */
public final class NodeCatalog {

    private static final String EXTENSION_POINT = "org.knime.workbench.repository.nodes";

    /** Версия формата кэша: при изменении каталог пересобирается. */
    private static final int CACHE_VERSION = 7;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ObjectNode> nodes = new ArrayList<>();
    private final Map<String, ObjectNode> byId = new LinkedHashMap<>();
    private final Map<String, String> contributorById = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, String> failureTraces = new LinkedHashMap<>();

    private NodeCatalog() {
    }

    public static NodeCatalog loadOrBuild(final File cache) throws IOException {
        final NodeCatalog catalog = new NodeCatalog();
        if (cache != null && cache.isFile()) {
            catalog.readCache(cache);
            if (!catalog.nodes.isEmpty()) {
                return catalog;
            }
        }
        catalog.build();
        if (cache != null) {
            catalog.writeCache(cache);
        }
        return catalog;
    }

    public int size() {
        return nodes.size();
    }

    /** Сохранить каталог в JSON-кэш. */
    public void save(final File cache) throws IOException {
        writeCache(cache);
    }

    /** Пересобрать каталог заново (диагностика). */
    public void rebuild() {
        nodes.clear();
        byId.clear();
        contributorById.clear();
        failures.clear();
        failureTraces.clear();
        build();
    }

    /** Узлы, которые не удалось описать при сборке каталога: id -> причина. */
    public Map<String, String> failures() {
        return java.util.Collections.unmodifiableMap(failures);
    }

    public Map<String, String> failureTraces() {
        return java.util.Collections.unmodifiableMap(failureTraces);
    }

    /** Все узлы каталога (для диагностических прогонов). */
    public List<ObjectNode> all() {
        return java.util.Collections.unmodifiableList(nodes);
    }

    private void readCache(final File cache) throws IOException {
        final ObjectNode root = (ObjectNode) mapper.readTree(cache);
        if (root.path("version").asInt(0) != CACHE_VERSION) {
            return;
        }
        for (final com.fasterxml.jackson.databind.JsonNode n : root.withArray("nodes")) {
            final ObjectNode node = (ObjectNode) n;
            nodes.add(node);
            byId.put(node.get("id").asText(), node);
            contributorById.put(node.get("id").asText(), node.path("contributor").asText());
        }
    }

    private void writeCache(final File cache) throws IOException {
        final ObjectNode root = mapper.createObjectNode();
        root.put("version", CACHE_VERSION);
        root.put("count", nodes.size());
        final ArrayNode arr = root.putArray("nodes");
        for (final ObjectNode n : nodes) {
            arr.add(n);
        }
        if (cache.getParentFile() != null) {
            cache.getParentFile().mkdirs();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(cache, root);
    }

    private void build() {
        final IExtensionRegistry registry = Platform.getExtensionRegistry();
        final IExtensionPoint ep = registry.getExtensionPoint(EXTENSION_POINT);
        if (ep == null) {
            throw new IllegalStateException("extension point not found: " + EXTENSION_POINT);
        }
        for (final IConfigurationElement ce : ep.getConfigurationElements()) {
            final String factoryClass = ce.getAttribute("factory-class");
            if (factoryClass == null) {
                continue;
            }
            final String contributor = ce.getContributor() == null ? "" : ce.getContributor().getName();
            final String category = nullToEmpty(ce.getAttribute("category-path"));
            final boolean deprecated = "true".equals(ce.getAttribute("deprecated"));
            final ObjectNode node;
            try {
                node = describe(contributor, factoryClass, category, deprecated);
            } catch (final Throwable t) {
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                failures.put(factoryClass, cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
                final StringBuilder trace = new StringBuilder();
                int n = 0;
                for (final StackTraceElement e : cause.getStackTrace()) {
                    if (n++ >= 10) {
                        break;
                    }
                    trace.append(e).append(" | ");
                }
                failureTraces.put(factoryClass, trace.toString());
                continue;
            }
            nodes.add(node);
            byId.put(factoryClass, node);
            contributorById.put(factoryClass, contributor);
        }
        nodes.sort((a, b) -> a.get("name").asText().compareToIgnoreCase(b.get("name").asText()));
    }

    private ObjectNode describe(final String contributor, final String factoryClass,
            final String category, final boolean deprecated) throws Exception {
        final Bundle bundle = Platform.getBundle(contributor);
        if (bundle == null) {
            throw new IllegalStateException("bundle not installed: " + contributor);
        }
        final Class<?> clazz = bundle.loadClass(factoryClass);
        final NodeFactory<?> factory = (NodeFactory<?>) clazz.getDeclaredConstructor().newInstance();
        factory.init();
        final NodeModel model = createModel(factory);

        final ObjectNode node = mapper.createObjectNode();
        node.put("id", factoryClass);
        node.put("name", factory.getNodeName());
        node.put("category", category);
        node.put("contributor", contributor);
        node.put("type", String.valueOf(factory.getType()));
        node.put("configurable", factory instanceof ConfigurableNodeFactory);
        node.put("deprecated", deprecated || factory.isDeprecated());

        final int nrIn = (Integer) Reflection.call(model, "getNrInPorts", new Class<?>[0]);
        final int nrOut = (Integer) Reflection.call(model, "getNrOutPorts", new Class<?>[0]);

        final ArrayNode inPorts = node.putArray("inPorts");
        for (int i = 0; i < nrIn; i++) {
            final ObjectNode p = inPorts.addObject();
            p.put("index", i);
            p.put("name", nullToEmpty(factory.getInportName(i)));
            p.put("type", ((PortType) Reflection.call(model, "getInPortType", new Class<?>[]{int.class}, i)).getName());
        }
        final ArrayNode outPorts = node.putArray("outPorts");
        for (int i = 0; i < nrOut; i++) {
            final ObjectNode p = outPorts.addObject();
            p.put("index", i);
            p.put("name", nullToEmpty(factory.getOutportName(i)));
            p.put("type", ((PortType) Reflection.call(model, "getOutPortType", new Class<?>[]{int.class}, i)).getName());
        }

        final NodeSettings settings = new NodeSettings("node_settings");
        final ArrayNode keys = node.putArray("settings");
        try {
            Reflection.call(model, "saveSettingsTo", new Class<?>[]{NodeSettingsWO.class}, settings);
            for (final String key : settings.keySet()) {
                keys.add(key);
            }
            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            settings.saveToXML(baos);
            node.put("settingsXml", baos.toString("UTF-8"));
        } catch (final Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            node.put("settingsUnavailable", cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        }
        return node;
    }
    /**
     * Модель узла. У {@link ConfigurableNodeFactory} обычный createNodeModel()
     * запрещён: модель создаётся по конфигурации создания (наборы портов).
     */
    static NodeModel createModel(final NodeFactory<?> factory) throws Exception {
        if (factory instanceof ConfigurableNodeFactory) {
            final Object configuration = ((ConfigurableNodeFactory<?>) factory).createNodeCreationConfig();
            final Object model = Reflection.call(factory, "createNodeModel",
                    new Class<?>[]{NodeCreationConfiguration.class}, configuration);
            return (NodeModel) model;
        }
        return factory.createNodeModel();
    }

    public List<ObjectNode> search(final String query, final String category, final int limit) {
        final String q = query == null ? "" : query.trim().toLowerCase();
        final String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        final List<ObjectNode> result = new ArrayList<>();
        for (final ObjectNode n : nodes) {
            if (tokens.length > 0) {
                final String hay = (n.get("name").asText() + " " + n.get("id").asText() + " "
                        + n.get("category").asText()).toLowerCase();
                boolean all = true;
                for (final String t : tokens) {
                    if (!hay.contains(t)) {
                        all = false;
                        break;
                    }
                }
                if (!all) {
                    continue;
                }
            }
            if (category != null && !category.isEmpty() && !n.get("category").asText().startsWith(category)) {
                continue;
            }
            result.add(n);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public ObjectNode byId(final String id) {
        return byId.get(id);
    }

    public String contributorOf(final String id) {
        return contributorById.get(id);
    }

    public NodeFactory<?> newFactory(final String id) throws Exception {
        final String contributor = contributorById.get(id);
        if (contributor == null) {
            throw new IllegalArgumentException("unknown node: " + id);
        }
        final Bundle bundle = Platform.getBundle(contributor);
        if (bundle == null) {
            throw new IllegalStateException("bundle not installed: " + contributor);
        }
        final NodeFactory<?> factory =
                (NodeFactory<?>) bundle.loadClass(id).getDeclaredConstructor().newInstance();
        factory.init();
        return factory;
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
