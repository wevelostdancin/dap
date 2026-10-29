package com.dap.engine;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.WorkflowManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Сливает JSON-дельту агента в XML-схему настроек узла по умолчанию и превращает
 * результат в NodeSettings, который принимает WorkflowManager#loadNodeSettings.
 *
 * JSON повторяет структуру XML: вложенные <config> — объекты, <entry> — скаляры,
 * повторяющиеся config с числовыми ключами — массивы.
 */
final class SettingsMerge {

    private SettingsMerge() {
    }

    static NodeSettingsRO apply(final ObjectNode node, final JsonNode delta) throws Exception {
        final String xml = node.path("settingsXml").asText(null);
        if (xml == null || xml.isEmpty()) {
            throw new IllegalStateException("node has no settings schema: " + node.path("id").asText());
        }
        final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        final Element root = doc.getDocumentElement();
        if (delta != null && !delta.isNull()) {
            merge(root, delta, root.getAttribute("key"));
        }
        final StringWriter sw = new StringWriter();
        final Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return NodeSettingsRO.class.cast(org.knime.core.node.NodeSettings.loadFromXML(
                new ByteArrayInputStream(sw.toString().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Полный цикл: взять живые настройки узла (saveNodeSettings), подменить в них
     * секцию model на схему из каталога с применённой дельтой агента.
     */
    static NodeSettingsRO applyToNode(final WorkflowManager wfm, final NodeID nid,
            final ObjectNode meta, final JsonNode delta) throws Exception {
        if (meta.path("settingsXml").asText("").isEmpty() && meta.has("settingsUnavailable")) {
            throw new IllegalStateException("settings of this node are not serializable by default: "
                    + meta.path("settingsUnavailable").asText());
        }
        final Document modelDoc = parse(meta.path("settingsXml").asText(null));
        merge(modelDoc.getDocumentElement(), delta == null ? emptyObject() : delta, "settings");

        final NodeSettings live = new NodeSettings("node_settings");
        wfm.saveNodeSettings(nid, live);
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        live.saveToXML(baos);

        final Document fullDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(baos.toByteArray()));
        final Element fullRoot = fullDoc.getDocumentElement();
        Element modelSection = null;
        final NodeList kids = fullRoot.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element
                    && "model".equals(((Element) kids.item(i)).getAttribute("key"))) {
                modelSection = (Element) kids.item(i);
            }
        }
        if (modelSection == null) {
            modelSection = fullDoc.createElement("config");
            modelSection.setAttribute("key", "model");
            fullRoot.appendChild(modelSection);
        }
        while (modelSection.hasChildNodes()) {
            modelSection.removeChild(modelSection.getFirstChild());
        }
        final NodeList modelKids = modelDoc.getDocumentElement().getChildNodes();
        for (int i = 0; i < modelKids.getLength(); i++) {
            modelSection.appendChild(fullDoc.importNode(modelKids.item(i), true));
        }

        final StringWriter sw = new StringWriter();
        final Transformer t = TransformerFactory.newInstance().newTransformer();
        t.transform(new DOMSource(fullDoc), new StreamResult(sw));
        return NodeSettings.loadFromXML(
                new ByteArrayInputStream(sw.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static Document parse(final String xml) throws Exception {
        if (xml == null || xml.isEmpty()) {
            throw new IllegalStateException("node has no settings schema");
        }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode emptyObject() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private static void merge(final Element config, final JsonNode delta, final String path) {
        if (!delta.isObject()) {
            throw new IllegalArgumentException("settings at '" + path + "' must be an object");
        }
        final Iterator<Map.Entry<String, JsonNode>> it = delta.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final Element child = findChild(config, e.getKey());
            if (child == null) {
                throw new IllegalArgumentException("unknown setting '" + e.getKey() + "' in '" + path
                        + "'; available: " + availableKeys(config));
            }
            if ("entry".equals(child.getTagName())) {
                if (e.getValue().isObject() || e.getValue().isArray()) {
                    throw new IllegalArgumentException("setting '" + path + "." + e.getKey()
                            + "' is a value, not a structure");
                }
                child.setAttribute("value", scalar(e.getValue()));
                if (child.hasAttribute("isnull")) {
                    child.removeAttribute("isnull");
                }
            } else {
                mergeConfig(child, e.getValue(), path + "." + e.getKey());
            }
        }
    }

    private static void mergeConfig(final Element config, final JsonNode value, final String path) {
        if (value.isObject()) {
            merge(config, value, path);
            return;
        }
        if (value.isArray()) {
            final Element sizeEntry = findEntry(config, "array-size");
            if (sizeEntry != null) {
                sizeEntry.setAttribute("value", Integer.toString(value.size()));
                final Element template = findEntry(config, "0");
                for (int i = 0; i < value.size(); i++) {
                    if (value.get(i).isObject() || value.get(i).isArray()) {
                        throw new IllegalArgumentException("'" + path + "[" + i + "]' must be a scalar");
                    }
                    Element slot = findEntry(config, Integer.toString(i));
                    if (slot == null && template != null) {
                        slot = (Element) template.cloneNode(true);
                        slot.setAttribute("key", Integer.toString(i));
                        config.appendChild(slot);
                    }
                    if (slot == null) {
                        // Пустой массив в XML (array-size=0) не содержит шаблона:
                        // создаём entry с типом по значению — это нужно для
                        // list/filterString-настроек (GroupBy, Column Filter и т.п.).
                        slot = config.getOwnerDocument().createElement("entry");
                        slot.setAttribute("key", Integer.toString(i));
                        slot.setAttribute("type", xmlType(value.get(i)));
                        config.appendChild(slot);
                    }
                    slot.setAttribute("value", scalar(value.get(i)));
                    if (slot.hasAttribute("isnull")) {
                        slot.removeAttribute("isnull");
                    }
                }
                removeNumericEntriesBeyond(config, value.size());
                return;
            }
            final List<Element> slots = numericChildren(config);
            for (int i = 0; i < value.size(); i++) {
                Element slot;
                if (i < slots.size()) {
                    slot = slots.get(i);
                } else if (!slots.isEmpty()) {
                    slot = (Element) slots.get(slots.size() - 1).cloneNode(true);
                    slot.setAttribute("key", Integer.toString(i));
                    renumberNested(slot, i);
                    config.appendChild(slot);
                    slots.add(slot);
                } else {
                    throw new IllegalArgumentException("'" + path + "' has no template to extend");
                }
                merge(slot, value.get(i), path + "[" + i + "]");
            }
            return;
        }
        throw new IllegalArgumentException("'" + path + "' must be an object or array");
    }

    private static String xmlType(final JsonNode value) {
        if (value.isBoolean()) {
            return "xboolean";
        }
        if (value.isInt() || value.isLong()) {
            return "xint";
        }
        if (value.isNumber()) {
            return "xdouble";
        }
        return "xstring";
    }

    /** Убираем entry с числовыми ключами за пределами новой длины массива. */
    private static void removeNumericEntriesBeyond(final Element config, final int size) {
        final List<Element> stale = new ArrayList<>();
        final NodeList children = config.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) {
                final Element el = (Element) n;
                final String key = el.getAttribute("key");
                if ("entry".equals(el.getTagName()) && key.matches("\\d+")
                        && Integer.parseInt(key) >= size) {
                    stale.add(el);
                }
            }
        }
        for (final Element el : stale) {
            config.removeChild(el);
        }
    }

    /** При клонировании повторяющегося блока перенумеровываем вложенные числовые ключи. */
    private static void renumberNested(final Element slot, final int index) {
        final NodeList children = slot.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element && "config".equals(((Element) n).getTagName())
                    && ((Element) n).hasAttribute("key")) {
                final String key = ((Element) n).getAttribute("key");
                if (key.matches("\\d+")) {
                    ((Element) n).setAttribute("key", Integer.toString(index));
                }
            }
        }
    }

    private static List<Element> numericChildren(final Element config) {
        final List<Element> result = new ArrayList<>();
        final NodeList children = config.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) {
                final Element el = (Element) n;
                if ("config".equals(el.getTagName()) && el.getAttribute("key").matches("\\d+")) {
                    result.add(el);
                }
            }
        }
        return result;
    }

    private static Element findEntry(final Element config, final String key) {
        final NodeList children = config.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) {
                final Element el = (Element) n;
                if ("entry".equals(el.getTagName()) && key.equals(el.getAttribute("key"))) {
                    return el;
                }
            }
        }
        return null;
    }

    private static Element findChild(final Element config, final String key) {
        final NodeList children = config.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) {
                final Element el = (Element) n;
                if (key.equals(el.getAttribute("key"))) {
                    return el;
                }
            }
        }
        return null;
    }

    private static String availableKeys(final Element config) {
        final StringBuilder sb = new StringBuilder("[");
        final NodeList children = config.getChildNodes();
        boolean first = true;
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n instanceof Element) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(((Element) n).getAttribute("key"));
            }
        }
        return sb.append(']').toString();
    }

    private static String scalar(final JsonNode value) {
        if (value.isNull()) {
            return "";
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "true" : "false";
        }
        return value.asText();
    }
}
