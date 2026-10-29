package com.dap.engine;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import org.knime.core.node.ConfigurableNodeFactory;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeModel;
import org.knime.core.node.context.ModifiableNodeCreationConfiguration;
import org.knime.core.node.context.NodeCreationConfiguration;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.node.workflow.NodeContext;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.WorkflowCreationHelper;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.core.node.defaultnodesettings.SettingsModel;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Схема старого Swing-диалога: подписи и варианты берутся из готовых
 * {@code DialogComponent}, ключи и умолчания — из {@code SettingsModel}.
 * Если диалог собран вручную, дополнительно выгружается дерево компонентов
 * ({@code ui}) — без выдуманных configKey, только реальные подписи и варианты.
 * Всё, что недоступно, честно помечается в ответе.
 */
final class SwingDialogSchema {

    private static final String DNS = "org.knime.core.node.defaultnodesettings.";

    private SwingDialogSchema() {
    }

    static ObjectNode describe(final NodeFactory<?> factory) {
        return describe(factory, Set.of());
    }

    /** Всегда возвращает узел; если полей и элементов нет, ставит empty=true. */
    static ObjectNode describe(final NodeFactory<?> factory, final Set<String> knownKeys) {
        final ObjectNode root = DialogSchema.MAPPER.createObjectNode();
        root.put("kind", "swing");
        final ArrayNode fields = root.putArray("fields");
        final ArrayNode warnings = DialogSchema.MAPPER.createArrayNode();
        final Set<String> keys = new HashSet<>();
        int labelled = 0;

        Object pane = null;
        try {
            pane = createPane(factory);
            if (pane != null) {
                root.put("dialogClass", pane.getClass().getName());
            } else {
                warnings.add("createNodeDialogPane() вернул null");
            }
        } catch (final Throwable t) {
            if (needsNodeContext(t)) {
                try {
                    pane = createPaneWithContext(factory);
                    root.put("dialogClass", pane == null ? "null" : pane.getClass().getName());
                    root.put("dialogContext", "node");
                } catch (final Throwable t2) {
                    warnings.add("диалог не создан даже с NodeContext: " + shortMessage(t2));
                }
            } else {
                warnings.add("диалог не создан: " + shortMessage(t));
            }
        }

        if (pane != null) {
            final List<Object> components = components(pane);
            if (components.isEmpty()) {
                warnings.add("в диалоге не найдено DialogComponent");
            }
            for (final Object component : components) {
                final Object model = invoke(component, "getModel", new Class<?>[0]);
                if (!(model instanceof SettingsModel)) {
                    continue;
                }
                final ObjectNode field = fieldOf((SettingsModel) model);
                final String key = field.path("configKey").asText();
                if (key.isEmpty() || !keys.add(key)) {
                    continue;
                }
                final String label = labelOf(component);
                if (label != null && !label.isEmpty()) {
                    field.put("title", label);
                    field.put("titleSource", "dialog");
                    labelled++;
                } else {
                    field.put("title", humanize(key));
                    field.put("titleSource", "field-name");
                }
                addChoices(field, component, (SettingsModel) model);
                field.put("source", "dialog");
                fields.add(field);
            }
            for (final SettingsModel model : settingsModelsIn(pane)) {
                addModelField(fields, keys, model, "dialog-field", warnings);
            }
        }

        try {
            final NodeModel model = NodeCatalog.createModel(factory);
            for (final SettingsModel settings : settingsModelsIn(model)) {
                addModelField(fields, keys, settings, "node-model", warnings);
            }
        } catch (final Throwable t) {
            warnings.add("модель не создана: " + shortMessage(t));
        }

        final ArrayNode ui = pane == null ? DialogSchema.MAPPER.createArrayNode() : uiElements(pane, knownKeys);
        if (ui.size() > 0) {
            root.set("ui", ui);
        }

        root.put("labelCoverage", labelled + "/" + fields.size());
        if (ui.size() > 0) {
            root.put("uiElements", ui.size());
        }
        if (warnings.size() > 0) {
            root.set("warnings", warnings);
        }
        if (fields.size() == 0 && ui.size() == 0) {
            root.put("empty", true);
        }
        return root;
    }

    private static void addModelField(final ArrayNode fields, final Set<String> keys,
            final SettingsModel model, final String source, final ArrayNode warnings) {
        try {
            final ObjectNode field = fieldOf(model);
            final String key = field.path("configKey").asText();
            if (key.isEmpty() || !keys.add(key)) {
                return;
            }
            field.put("title", humanize(key));
            field.put("titleSource", "field-name");
            field.put("source", source);
            addChoices(field, null, model);
            fields.add(field);
        } catch (final Throwable t) {
            warnings.add("поле " + model.getClass().getSimpleName() + ": " + shortMessage(t));
        }
    }

    /** Поля настроек, объявленные прямо в объекте (диалог не DefaultNodeSettingsPane). */
    private static List<SettingsModel> settingsModelsIn(final Object owner) {
        final List<SettingsModel> result = new ArrayList<>();
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!SettingsModel.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                final Object value = get(f, owner);
                if (value instanceof SettingsModel) {
                    result.add((SettingsModel) value);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> components(final Object pane) {
        final List<Object> result = new ArrayList<>();
        final Field field = DialogSchema.findField(pane.getClass(), "m_dialogComponents");
        if (field == null) {
            return result;
        }
        try {
            field.setAccessible(true);
            final Object value = field.get(pane);
            if (value instanceof List) {
                result.addAll((List<Object>) value);
            }
        } catch (final Throwable ignored) {
            // список компонентов недоступен
        }
        return result;
    }

    private static ObjectNode fieldOf(final SettingsModel model) {
        final ObjectNode node = DialogSchema.MAPPER.createObjectNode();
        node.put("field", fieldNameOf(model));
        node.put("configKey", stringCall(model, "getConfigName"));
        node.put("javaType", model.getClass().getName());
        final String type = typeOf(model);
        node.put("type", type);
        final Object value = valueOf(model, type);
        if (value == null) {
            node.putNull("default");
        } else {
            try {
                node.set("default", DialogSchema.MAPPER.valueToTree(value));
            } catch (final Throwable t) {
                node.putNull("default");
            }
        }
        final Object lower = invoke(model, "getLowerBound", new Class<?>[0]);
        final Object upper = invoke(model, "getUpperBound", new Class<?>[0]);
        if (lower instanceof Number && upper instanceof Number) {
            node.put("min", ((Number) lower).doubleValue());
            node.put("max", ((Number) upper).doubleValue());
        }
        return node;
    }

    private static void addChoices(final ObjectNode field, final Object component, final SettingsModel model) {
        final String type = field.path("type").asText();
        final boolean columnish = "column".equals(type) || "filter".equals(type)
                || component != null && component.getClass().getName().contains("Column");
        if (component == null) {
            if (columnish) {
                final ObjectNode source = field.putObject("choicesSource");
                source.put("kind", "columns");
                source.put("note", "варианты зависят от входной таблицы");
            }
            return;
        }
        final JComboBox<?> combo = findCombo(component);
        if (combo != null && combo.getItemCount() > 0) {
            final ArrayNode choices = field.putArray("choices");
            for (int i = 0; i < combo.getItemCount(); i++) {
                final String text = itemText(combo.getItemAt(i));
                choices.addObject().put("value", text).put("label", text);
            }
            field.putObject("choicesSource").put("kind", "dialog-combo");
            return;
        }
        final JSpinner spinner = findSpinner(component);
        if (spinner != null && spinner.getModel() instanceof SpinnerNumberModel) {
            final SpinnerNumberModel sm = (SpinnerNumberModel) spinner.getModel();
            if (sm.getMinimum() instanceof Number) {
                field.put("min", ((Number) sm.getMinimum()).doubleValue());
            }
            if (sm.getMaximum() instanceof Number) {
                field.put("max", ((Number) sm.getMaximum()).doubleValue());
            }
            if (sm.getStepSize() instanceof Number) {
                field.put("step", ((Number) sm.getStepSize()).doubleValue());
            }
        }
        if (columnish) {
            final ObjectNode source = field.putObject("choicesSource");
            source.put("kind", "columns");
            source.put("note", "варианты зависят от входной таблицы");
            final Object spec = getField(component, "m_specIndex");
            if (spec instanceof Integer) {
                source.put("specIndex", (Integer) spec);
            }
        }
    }

    // ---------------------------------------------------------------- ручные диалоги

    /**
     * Дерево Swing-компонентов диалога: подписи, списки, диапазоны.
     * configKey заполняется только при точном совпадении подписи с ключом настроек.
     */
    private static ArrayNode uiElements(final Object pane, final Set<String> knownKeys) {
        final ArrayNode out = DialogSchema.MAPPER.createArrayNode();
        final Object panel = invoke(pane, "getPanel", new Class<?>[0]);
        if (!(panel instanceof Component)) {
            return out;
        }
        final Walker walker = new Walker(out, knownKeys);
        walk((Component) panel, walker);
        return out;
    }

    private static final class Walker {
        final ArrayNode out;
        final Set<String> knownKeys;
        String label;
        String section;

        Walker(final ArrayNode out, final Set<String> knownKeys) {
            this.out = out;
            this.knownKeys = knownKeys;
        }

        ObjectNode item(final String type) {
            final ObjectNode node = out.addObject();
            node.put("type", type);
            if (label != null && !label.isEmpty()) {
                node.put("label", label);
                final String key = matchKey(label, knownKeys);
                if (key != null) {
                    node.put("configKey", key);
                    node.put("keySource", "label-match");
                }
            }
            if (section != null && !section.isEmpty()) {
                node.put("section", section);
            }
            node.put("source", "dialog-ui");
            label = null;
            return node;
        }
    }

    private static void walk(final Component component, final Walker walker) {
        if (component == null) {
            return;
        }
        if (component instanceof JTabbedPane) {
            final JTabbedPane tabs = (JTabbedPane) component;
            final String previous = walker.section;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                walker.section = cleanLabel(tabs.getTitleAt(i));
                walk(tabs.getComponentAt(i), walker);
            }
            walker.section = previous;
            return;
        }
        if (component instanceof JLabel) {
            final String text = cleanLabel(((JLabel) component).getText());
            if (!text.isEmpty()) {
                walker.label = text;
            }
            return;
        }
        if (component instanceof JComboBox) {
            final JComboBox<?> combo = (JComboBox<?>) component;
            final ObjectNode node = walker.item("dropdown");
            final ArrayNode choices = node.putArray("choices");
            for (int i = 0; i < combo.getItemCount(); i++) {
                final String text = itemText(combo.getItemAt(i));
                choices.addObject().put("value", text).put("label", text);
            }
            final Object selected = combo.getSelectedItem();
            if (selected != null) {
                node.put("default", itemText(selected));
            }
            return;
        }
        if (component instanceof JSpinner) {
            final JSpinner spinner = (JSpinner) component;
            final ObjectNode node = walker.item("number");
            if (spinner.getModel() instanceof SpinnerNumberModel) {
                final SpinnerNumberModel sm = (SpinnerNumberModel) spinner.getModel();
                if (sm.getValue() instanceof Number) {
                    node.put("default", ((Number) sm.getValue()).doubleValue());
                }
                if (sm.getMinimum() instanceof Number) {
                    node.put("min", ((Number) sm.getMinimum()).doubleValue());
                }
                if (sm.getMaximum() instanceof Number) {
                    node.put("max", ((Number) sm.getMaximum()).doubleValue());
                }
                if (sm.getStepSize() instanceof Number) {
                    node.put("step", ((Number) sm.getStepSize()).doubleValue());
                }
            }
            return;
        }
        if (component instanceof JCheckBox) {
            final JCheckBox box = (JCheckBox) component;
            walker.label = cleanLabel(box.getText());
            walker.item("boolean").put("default", box.isSelected());
            return;
        }
        if (component instanceof JRadioButton) {
            final JRadioButton radio = (JRadioButton) component;
            walker.label = cleanLabel(radio.getText());
            walker.item("boolean").put("default", radio.isSelected());
            return;
        }
        if (component instanceof JTextField) {
            final JTextField text = (JTextField) component;
            final ObjectNode node = walker.item("string");
            node.put("default", text.getText() == null ? "" : text.getText());
            node.put("editable", text.isEditable());
            return;
        }
        if (component instanceof JTextArea) {
            final JTextArea text = (JTextArea) component;
            final ObjectNode node = walker.item("text");
            node.put("default", text.getText() == null ? "" : text.getText());
            return;
        }
        if (component instanceof JList) {
            final JList<?> list = (JList<?>) component;
            final ObjectNode node = walker.item("list");
            final ArrayNode choices = node.putArray("choices");
            for (int i = 0; i < list.getModel().getSize(); i++) {
                final String text = itemText(list.getModel().getElementAt(i));
                choices.addObject().put("value", text).put("label", text);
            }
            return;
        }
        if (component instanceof Container) {
            final String previous = walker.section;
            if (component instanceof javax.swing.JComponent) {
                final Border border = ((javax.swing.JComponent) component).getBorder();
                if (border instanceof TitledBorder) {
                    final String title = cleanLabel(((TitledBorder) border).getTitle());
                    if (!title.isEmpty()) {
                        walker.section = title;
                    }
                }
            }
            for (final Component child : ((Container) component).getComponents()) {
                walk(child, walker);
            }
            walker.section = previous;
        }
    }

    /** Точное совпадение нормализованной подписи и ключа; иначе null (не угадываем). */
    private static String matchKey(final String label, final Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        final String normalized = normalize(label);
        if (normalized.length() < 2) {
            return null;
        }
        String found = null;
        for (final String key : keys) {
            if (normalize(key).equals(normalized)) {
                if (found != null) {
                    return null;
                }
                found = key;
            }
        }
        return found;
    }

    private static String normalize(final String text) {
        return text.toLowerCase(java.util.Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    private static String cleanLabel(final String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("<[^>]+>", " ").replace("&", "");
        s = s.trim();
        while (s.endsWith(":") || s.endsWith("*")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** Все нечисловые ключи из XML-схемы настроек — кандидаты для сопоставления с подписями. */
    static Set<String> keysOf(final String settingsXml) {
        final Set<String> keys = new LinkedHashSet<>();
        if (settingsXml == null || settingsXml.isEmpty()) {
            return keys;
        }
        try {
            final javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(settingsXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            final org.w3c.dom.NodeList elements = doc.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                final org.w3c.dom.Element element = (org.w3c.dom.Element) elements.item(i);
                final String key = element.getAttribute("key");
                if (key != null && !key.isEmpty() && !key.matches("\\d+") && !"array-size".equals(key)) {
                    keys.add(key);
                }
            }
        } catch (final Throwable ignored) {
            // без XML обойдёмся без сопоставления
        }
        return keys;
    }

    // ---------------------------------------------------------------- базовые утилиты

    private static JComboBox<?> findCombo(final Object owner) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field f : c.getDeclaredFields()) {
                if (JComboBox.class.isAssignableFrom(f.getType())) {
                    final Object value = get(f, owner);
                    if (value instanceof JComboBox) {
                        return (JComboBox<?>) value;
                    }
                }
            }
        }
        return null;
    }

    private static JSpinner findSpinner(final Object owner) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field f : c.getDeclaredFields()) {
                if (JSpinner.class.isAssignableFrom(f.getType())) {
                    final Object value = get(f, owner);
                    if (value instanceof JSpinner) {
                        return (JSpinner) value;
                    }
                }
            }
        }
        return null;
    }

    private static String labelOf(final Object component) {
        for (Class<?> c = component.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field f : c.getDeclaredFields()) {
                if (!JLabel.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                final Object value = get(f, component);
                if (value instanceof JLabel) {
                    final String text = ((JLabel) value).getText();
                    if (text != null && !text.trim().isEmpty()) {
                        return text.trim();
                    }
                }
            }
        }
        final Object panel = invoke(component, "getComponentPanel", new Class<?>[0]);
        return panel instanceof Component ? firstLabel((Component) panel, 0) : null;
    }

    private static String firstLabel(final Component component, final int depth) {
        if (depth > 8) {
            return null;
        }
        if (component instanceof JLabel) {
            final String text = ((JLabel) component).getText();
            return text == null || text.trim().isEmpty() ? null : text.trim();
        }
        if (!(component instanceof Container)) {
            return null;
        }
        for (final Component child : ((Container) component).getComponents()) {
            final String text = firstLabel(child, depth + 1);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static WorkflowManager probeWorkflow;

    /** Одноразовый workflow, чтобы дать диалогу настоящий NodeContext. */
    private static synchronized WorkflowManager probeWorkflow() {
        if (probeWorkflow == null) {
            probeWorkflow = WorkflowManager.ROOT.createAndAddProject(
                    "__dialog_probe_" + System.nanoTime(), new WorkflowCreationHelper());
        }
        return probeWorkflow;
    }

    private static Object createPaneWithContext(final NodeFactory<?> factory) throws Exception {
        final WorkflowManager wf = probeWorkflow();
        NodeID nid = null;
        try {
            if (factory instanceof ConfigurableNodeFactory) {
                final ModifiableNodeCreationConfiguration creation =
                        ((ConfigurableNodeFactory<?>) factory).createNodeCreationConfig();
                nid = wf.addNodeAndApplyContext(factory, creation, -1);
            } else {
                nid = wf.createAndAddNode(factory);
            }
            final NodeContainer nc = wf.getNodeContainer(nid);
            NodeContext.pushContext(nc);
            try {
                return createPane(factory);
            } finally {
                NodeContext.removeLastContext();
            }
        } finally {
            if (nid != null) {
                try {
                    wf.removeNode(nid);
                } catch (final Throwable ignored) {
                    // узел мог остаться, если диалог его удерживает
                }
            }
        }
    }

    private static boolean needsNodeContext(final Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            final String msg = cause.getMessage();
            if (msg != null && msg.contains("NodeContext")) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    private static Object createPane(final NodeFactory<?> factory) throws Exception {
        if (factory instanceof ConfigurableNodeFactory) {
            final Object configuration = ((ConfigurableNodeFactory<?>) factory).createNodeCreationConfig();
            return Reflection.call(factory, "createNodeDialogPane",
                    new Class<?>[]{NodeCreationConfiguration.class}, configuration);
        }
        Method m = DialogSchema.findMethod(factory.getClass(), "createNodeDialogPane");
        if (m == null) {
            m = DialogSchema.findMethod(factory.getClass(), "createNodeDialog");
        }
        if (m == null) {
            return null;
        }
        m.setAccessible(true);
        return m.getParameterCount() == 0 ? m.invoke(factory) : m.invoke(factory, new Object[]{null});
    }

    private static String typeOf(final SettingsModel model) {
        String base = model.getClass().getSimpleName();
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith(DNS + "SettingsModel")) {
                base = c.getSimpleName();
                break;
            }
        }
        if (base.contains("ColumnName")) {
            return "column";
        }
        if (base.contains("FilterString") || base.contains("ColumnFilter") || base.contains("ListSelection")) {
            return "filter";
        }
        if (base.contains("StringArray") || base.contains("IntegerArray") || base.contains("DoubleArray")) {
            return "array";
        }
        if (base.contains("String")) {
            return "string";
        }
        if (base.contains("Boolean")) {
            return "boolean";
        }
        if (base.contains("Integer") || base.contains("Long")) {
            return "integer";
        }
        if (base.contains("Double") || base.contains("Float")) {
            return "number";
        }
        return "unknown";
    }

    private static Object valueOf(final SettingsModel model, final String type) {
        final String[] candidates;
        switch (type) {
            case "integer":
                candidates = new String[]{"getIntValue", "getLongValue"};
                break;
            case "number":
                candidates = new String[]{"getDoubleValue"};
                break;
            case "boolean":
                candidates = new String[]{"getBooleanValue"};
                break;
            case "array":
                candidates = new String[]{"getStringArrayValue", "getIntArrayValue", "getDoubleArrayValue"};
                break;
            case "column":
                candidates = new String[]{"getColumnName", "getStringValue"};
                break;
            default:
                candidates = new String[]{"getStringValue"};
                break;
        }
        for (final String name : candidates) {
            final Object value = invoke(model, name, new Class<?>[0]);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String fieldNameOf(final SettingsModel model) {
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field f : c.getDeclaredFields()) {
                if (!SettingsModel.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                final Object value = get(f, model);
                if (value == model) {
                    return f.getName();
                }
            }
        }
        return model.getClass().getSimpleName();
    }

    private static String stringCall(final Object owner, final String name) {
        final Object value = invoke(owner, name, new Class<?>[0]);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object invoke(final Object owner, final String name, final Class<?>[] args) {
        final Method m = DialogSchema.findMethod(owner.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            m.setAccessible(true);
            return m.invoke(owner, new Object[args.length]);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static Object getField(final Object owner, final String name) {
        final Field f = DialogSchema.findField(owner.getClass(), name);
        return f == null ? null : get(f, owner);
    }

    private static Object get(final Field field, final Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static String itemText(final Object item) {
        if (item == null) {
            return "";
        }
        if (item instanceof String) {
            return (String) item;
        }
        final Object text = invoke(item, "getText", new Class<?>[0]);
        return text == null ? String.valueOf(item) : String.valueOf(text);
    }

    private static String humanize(final String key) {
        final String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? key
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String shortMessage(final Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String msg = cause.getMessage();
        final String text = cause.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        return text.length() > 180 ? text.substring(0, 180) : text;
    }
}
