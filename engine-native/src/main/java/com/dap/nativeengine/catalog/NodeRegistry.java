package com.dap.nativeengine.catalog;

import com.dap.nativeengine.api.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Source of truth for node metadata. Specs live in resources/specs and are listed in index.txt. */
public final class NodeRegistry {
    private final Map<String, NodeSpec> byId = new LinkedHashMap<>();

    public NodeRegistry() {
        this(NodeRegistry.class.getClassLoader());
    }

    public NodeRegistry(ClassLoader loader) {
        List<String> files = readIndex(loader);
        for (String file : files) {
            try (InputStream stream = loader.getResourceAsStream("specs/" + file)) {
                if (stream == null) {
                    throw new IllegalStateException("missing node spec resource: specs/" + file);
                }
                NodeSpec spec = NodeSpec.fromJson(Json.MAPPER.readTree(stream));
                if (byId.put(spec.id(), spec) != null) {
                    throw new IllegalStateException("duplicate node spec: " + spec.id());
                }
            } catch (IOException exception) {
                throw new IllegalStateException("cannot read node spec specs/" + file, exception);
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalStateException("no node specs loaded");
        }
    }

    private static List<String> readIndex(ClassLoader loader) {
        try (InputStream stream = loader.getResourceAsStream("specs/index.txt")) {
            if (stream == null) {
                throw new IllegalStateException("missing specs/index.txt");
            }
            List<String> files = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String file = line.strip();
                    if (!file.isEmpty() && !file.startsWith("#")) {
                        files.add(file);
                    }
                }
            }
            return files;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read specs/index.txt", exception);
        }
    }

    public List<NodeSpec> all() {
        return new ArrayList<>(byId.values());
    }

    public NodeSpec find(String id) {
        return byId.get(id);
    }

    public NodeSpec require(String id) {
        NodeSpec spec = byId.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown node: " + id);
        }
        return spec;
    }

    public List<NodeSpec> search(String query, String category, int limit) {
        List<String> tokens = new ArrayList<>();
        if (query != null) {
            for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        List<NodeSpec> matches = new ArrayList<>();
        for (NodeSpec spec : byId.values()) {
            if (category != null && !category.isBlank() && !spec.category().startsWith(category)) {
                continue;
            }
            String haystack = (spec.name() + " " + spec.id() + " " + spec.category()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String token : tokens) {
                if (!haystack.contains(token)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matches.add(spec);
            }
        }
        matches.sort(Comparator.comparing(NodeSpec::name, String.CASE_INSENSITIVE_ORDER).thenComparing(NodeSpec::id));
        if (limit >= 0 && matches.size() > limit) {
            return new ArrayList<>(matches.subList(0, limit));
        }
        return matches;
    }

    public int size() {
        return byId.size();
    }

    public int availableCount() {
        return (int) byId.values().stream().filter(NodeSpec::available).count();
    }

    public int coreCount() {
        return (int) byId.values().stream().filter(spec -> "core".equals(spec.tier())).count();
    }
}
