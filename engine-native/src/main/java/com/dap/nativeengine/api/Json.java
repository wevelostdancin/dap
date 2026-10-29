package com.dap.nativeengine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Jackson entry point. One mapper for the whole process. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static JsonNode parse(String text) throws JsonProcessingException {
        return MAPPER.readTree(text);
    }

    /** Parse a request body; an absent or blank body is an empty object. */
    public static JsonNode parseBody(String text) throws JsonProcessingException {
        if (text == null || text.isBlank()) {
            return object();
        }
        JsonNode node = MAPPER.readTree(text);
        if (node == null) {
            return object();
        }
        return node;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialise response", exception);
        }
    }
}
