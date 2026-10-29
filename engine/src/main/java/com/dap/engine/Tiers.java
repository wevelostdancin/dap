package com.dap.engine;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Курируемый набор узлов DAP. core — поддерживаемые узлы с гарантией схемы
 * и golden-тестов; experimental — остальные, доступны, но без гарантий.
 */
final class Tiers {

    static final String CORE = "core";
    static final String EXPERIMENTAL = "experimental";

    private final Set<String> core;

    private Tiers(final Set<String> core) {
        this.core = core;
    }

    static Tiers empty() {
        return new Tiers(Collections.emptySet());
    }

    static Tiers load(final File file) {
        if (file == null || !file.isFile()) {
            return empty();
        }
        try {
            final JsonNode root = new ObjectMapper().readTree(file);
            final Set<String> ids = new HashSet<>();
            for (final JsonNode n : root.withArray("core")) {
                ids.add(n.asText());
            }
            return new Tiers(ids);
        } catch (final Throwable t) {
            System.err.println("[dap] curated list not loaded: " + t);
            return empty();
        }
    }

    boolean isCore(final String nodeId) {
        return core.contains(nodeId);
    }

    int size() {
        return core.size();
    }

    /** Проставляет tier во все узлы каталога; возвращает число core. */
    int apply(final NodeCatalog catalog) {
        int count = 0;
        for (final com.fasterxml.jackson.databind.node.ObjectNode node : catalog.all()) {
            final boolean isCore = core.contains(node.path("id").asText());
            node.put("tier", isCore ? CORE : EXPERIMENTAL);
            if (isCore) {
                count++;
            }
        }
        return count;
    }
}
