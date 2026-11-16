package com.dap.nativeengine.catalog;

import com.dap.nativeengine.api.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Declarative node description loaded from classpath specs/<id>.json. */
public record NodeSpec(String id, String name, String category, String type, String contributor,
                       String tier, boolean deprecated, boolean available, String description,
                       List<Port> inPorts, List<Port> outPorts, JsonNode configSchema) {

    public record Port(int index, String name, String type) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("name", name);
            map.put("type", type);
            return map;
        }
    }

    public NodeSpec {
        inPorts = List.copyOf(inPorts);
        outPorts = List.copyOf(outPorts);
    }

    public static NodeSpec fromJson(JsonNode node) {
        String id = required(node, "id");
        String name = required(node, "name");
        String category = required(node, "category");
        String type = required(node, "type");
        return new NodeSpec(
                id,
                name,
                category,
                type,
                node.path("contributor").asText("dap-native"),
                node.path("tier").asText("core"),
                node.path("deprecated").asBoolean(false),
                node.path("available").asBoolean(true),
                node.path("description").asText(""),
                ports(node.path("inPorts")),
                ports(node.path("outPorts")),
                node.path("configSchema").isObject() ? node.path("configSchema") : Json.object());
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("node spec misses '" + field + "'");
        }
        return value.asText();
    }

    private static List<Port> ports(JsonNode array) {
        List<Port> ports = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode entry : array) {
                ports.add(new Port(entry.path("index").asInt(ports.size()),
                        entry.path("name").asText("Data"),
                        entry.path("type").asText("BufferedDataTable")));
            }
        }
        return ports;
    }

    public Set<String> settingKeys() {
        Set<String> keys = new TreeSet<>();
        JsonNode properties = configSchema.path("properties");
        properties.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    public boolean hasConfigKey(String key) {
        return configSchema.path("properties").has(key);
    }

    public List<String> requiredKeys() {
        List<String> keys = new ArrayList<>();
        JsonNode required = configSchema.path("required");
        if (required.isArray()) {
            for (JsonNode entry : required) {
                keys.add(entry.asText());
            }
        }
        return keys;
    }

    public Map<String, Object> brief() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("category", category);
        map.put("type", type);
        map.put("deprecated", deprecated);
        map.put("tier", tier);
        map.put("inPorts", inPorts.stream().map(Port::toMap).toList());
        map.put("outPorts", outPorts.stream().map(Port::toMap).toList());
        return map;
    }

    public Map<String, Object> full() {
        Map<String, Object> map = brief();
        map.put("contributor", contributor);
        map.put("configurable", true);
        map.put("settings", new ArrayList<>(settingKeys()));
        map.put("settingsXml", null);
        map.put("settingsUnavailable", "native node: configure through configSchema from /v1/nodes/" + id + "/dialog");
        map.put("description", description);
        map.put("available", available);
        return map;
    }

    public Map<String, Object> dialog() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("tier", tier);
        map.put("precomputed", true);
        if (!available) {
            map.put("available", false);
            map.put("reason", "not implemented in native engine yet");
            return map;
        }
        map.put("available", true);
        map.put("schemaKind", "annotations");
        ObjectNode schema = Json.object();
        schema.put("class", id);
        ArrayNode fields = schema.putArray("fields");
        JsonNode properties = configSchema.path("properties");
        List<String> required = requiredKeys();
        properties.fieldNames().forEachRemaining(key -> fields.add(field(key, properties.get(key), required.contains(key))));
        schema.put("labelCoverage", fields.size() + "/" + fields.size());
        schema.putArray("warnings");
        map.put("schema", schema);
        return map;
    }

    private static ObjectNode field(String key, JsonNode property, boolean required) {
        ObjectNode field = Json.object();
        field.put("field", key);
        field.put("configKey", key);
        field.put("title", property.path("title").asText(humanize(key)));
        if (property.has("description")) {
            field.put("description", property.path("description").asText());
        }
        field.put("advanced", property.path("advanced").asBoolean(false));
        field.put("optional", !required);
        field.put("type", dialogType(property));
        if (property.has("default")) {
            field.set("default", property.get("default"));
        }
        if (property.has("placeholder")) {
            field.put("placeholder", property.path("placeholder").asText());
        }
        if (property.has("enum") && property.get("enum").isArray()) {
            ArrayNode choices = field.putArray("choices");
            for (JsonNode value : property.get("enum")) {
                ObjectNode choice = choices.addObject();
                choice.put("value", value.asText());
                choice.put("label", humanize(value.asText()));
            }
        }
        if (property.has("items")) {
            field.set("items", property.get("items"));
        }
        return field;
    }

    private static String dialogType(JsonNode property) {
        if (property.has("enum")) {
            return "enum";
        }
        String type = property.path("type").asText("unknown");
        return switch (type) {
            case "integer", "number", "boolean", "string", "array", "object" -> type;
            default -> "unknown";
        };
    }

    private static String humanize(String value) {
        String spaced = value.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public String displayName() {
        return name.toLowerCase(Locale.ROOT);
    }
}
