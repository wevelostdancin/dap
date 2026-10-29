package com.dap.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.knime.core.node.NodeFactory;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.persistence.Persist;
import org.knime.node.parameters.widget.OptionalWidget;
import org.knime.node.parameters.widget.choices.ChoicesProvider;
import org.knime.node.parameters.widget.choices.ColumnChoicesProvider;
import org.knime.node.parameters.widget.choices.EnumChoicesProvider;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.StringChoicesProvider;
import org.knime.node.parameters.widget.number.NumberInputWidget;
import org.knime.node.parameters.widget.text.TextInputWidget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Извлекает схему диалога узла: заголовки, описания, типы, значения по умолчанию
 * и списки допустимых значений — из аннотаций {@code @Widget} и провайдеров.
 *
 * Работает для узлов нового образца ({@code WebUINodeFactory} / {@code DefaultNodeDialog}),
 * у остальных возвращает null — для них остаётся XML-схема из каталога.
 */
final class DialogSchema {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private DialogSchema() {
    }

    /**
     * Полная схема диалога: аннотации нового образца, затем старый Swing-диалог.
     * Возвращает узел с available/schemaKind/schema либо available=false и reason.
     */
    static ObjectNode describeNode(final Object factory) {
        return describeNode(factory, java.util.Set.of(), DynamicChoices.empty());
    }

    static ObjectNode describeNode(final Object factory, final java.util.Set<String> knownKeys) {
        return describeNode(factory, knownKeys, DynamicChoices.empty());
    }

    static ObjectNode describeNode(final Object factory, final java.util.Set<String> knownKeys,
            final DynamicChoices choices) {
        final ObjectNode result = MAPPER.createObjectNode();
        final Class<?> settingsClass = settingsClass(factory);
        if (settingsClass != null) {
            result.put("available", true);
            result.put("schemaKind", "annotations");
            result.set("schema", describe(settingsClass, choices));
            return result;
        }
        if (factory instanceof NodeFactory) {
            try {
                final ObjectNode swing = SwingDialogSchema.describe((NodeFactory<?>) factory, knownKeys);
                if (swing != null && !swing.path("empty").asBoolean()) {
                    result.put("available", true);
                    result.put("schemaKind", "swing");
                    result.set("schema", swing);
                    return result;
                }
                if (swing != null) {
                    result.set("swing", swing);
                }
            } catch (final Throwable t) {
                result.put("swingError", String.valueOf(t));
            }
        }
        result.put("available", false);
        result.put("reason", "узел не отдал ни аннотированную, ни Swing-схему; используйте settingsXml из /nodes/{id}");
        return result;
    }

    /** Класс настроек модели узла или null, если узел на старом (Swing) диалоге. */
    static Class<?> settingsClass(final Object factory) {
        final Class<?> fromDialog = fromDialog(factory);
        if (fromDialog != null) {
            return fromDialog;
        }
        return fromConfiguration(factory);
    }

    @SuppressWarnings("unchecked")
    private static Class<?> fromDialog(final Object factory) {
        try {
            final Method m = findMethod(factory.getClass(), "createNodeDialog");
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            final Object dialog = m.invoke(factory);
            if (dialog == null) {
                return null;
            }
            final Method getClasses = findMethod(dialog.getClass(), "getSettingsClasses");
            if (getClasses != null) {
                getClasses.setAccessible(true);
                final Class<?> fromMap = settingsClassFromMap(getClasses.invoke(dialog));
                if (fromMap != null) {
                    return fromMap;
                }
            }
            final Field f = findField(dialog.getClass(), "m_settingsClasses");
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            final Map<Object, Class<?>> classes = (Map<Object, Class<?>>) f.get(dialog);
            for (final Map.Entry<Object, Class<?>> e : classes.entrySet()) {
                if ("MODEL".equals(String.valueOf(e.getKey()))) {
                    return e.getValue();
                }
            }
        } catch (final Throwable ignored) {
            // узел не новый или диалог недоступен без UI
        }
        return null;
    }

    /** Из Map<SettingsType, Class> берём MODEL; если его нет — первый непустой класс. */
    private static Class<?> settingsClassFromMap(final Object map) {
        if (!(map instanceof Map)) {
            return null;
        }
        Class<?> first = null;
        for (final Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            if (!(e.getValue() instanceof Class)) {
                continue;
            }
            if ("MODEL".equals(String.valueOf(e.getKey()))) {
                return (Class<?>) e.getValue();
            }
            if (first == null) {
                first = (Class<?>) e.getValue();
            }
        }
        return first;
    }

    private static Class<?> fromConfiguration(final Object factory) {
        try {
            final Field cfg = findField(factory.getClass(), "m_configuration");
            if (cfg == null) {
                return null;
            }
            cfg.setAccessible(true);
            final Object configuration = cfg.get(factory);
            if (configuration == null) {
                return null;
            }
            final Field cls = findField(configuration.getClass(), "m_modelSettingsClass");
            if (cls == null) {
                return null;
            }
            cls.setAccessible(true);
            return (Class<?>) cls.get(configuration);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /** Описание класса настроек: поля с заголовками, типами, умолчаниями и вариантами. */
    static ObjectNode describe(final Class<?> settingsClass) {
        return describe(settingsClass, DynamicChoices.empty());
    }

    static ObjectNode describe(final Class<?> settingsClass, final DynamicChoices choices) {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("class", settingsClass.getName());
        final Object instance = instantiate(settingsClass);
        root.set("fields", fieldsOf(settingsClass, instance, choices));
        return root;
    }

    private static ArrayNode fieldsOf(final Class<?> type, final Object instance,
            final DynamicChoices choices) {
        final ArrayNode fields = MAPPER.createArrayNode();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (final Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                final Persist persist = field.getAnnotation(Persist.class);
                if (persist != null && persist.hidden()) {
                    continue;
                }
                try {
                    final ObjectNode node = describeField(field, instance, choices);
                    if (node != null) {
                        fields.add(node);
                    }
                } catch (final Throwable t) {
                    final ObjectNode broken = fields.addObject();
                    broken.put("field", field.getName());
                    broken.put("configKey", configKey(field));
                    broken.put("title", titleOf(field));
                    broken.put("type", "unknown");
                    broken.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static ObjectNode describeField(final Field field, final Object instance,
            final DynamicChoices choices) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("field", field.getName());
        node.put("configKey", configKey(field));
        node.put("title", titleOf(field));
        final Widget widget = field.getAnnotation(Widget.class);
        node.put("description", widget == null ? "" : widget.description());
        node.put("advanced", widget != null && widget.advanced());

        final Class<?> raw = field.getType();
        final Object value = read(field, instance);

        if (raw.isEnum()) {
            node.put("type", "enum");
            node.set("choices", choicesOf(raw));
            node.put("default", value == null ? null : String.valueOf(value));
        } else if (raw == String.class) {
            node.put("type", "string");
            node.put("default", value == null ? null : String.valueOf(value));
            markTextual(node, field);
        } else if (raw == boolean.class || raw == Boolean.class) {
            node.put("type", "boolean");
            node.put("default", Boolean.TRUE.equals(value));
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            node.put("type", "integer");
            node.put("default", value == null ? null : ((Number) value).longValue());
            markNumeric(node, field);
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            node.put("type", "number");
            node.put("default", value == null ? null : ((Number) value).doubleValue());
            markNumeric(node, field);
        } else if (raw == Optional.class) {
            node.put("type", describeType(innerType(field.getGenericType())));
            node.put("nullable", true);
            node.putNull("default");
            annotateChoices(node, field, instance, choices);
        } else if (List.class.isAssignableFrom(raw) || raw.isArray()) {
            node.put("type", "array");
            final ObjectNode items = MAPPER.createObjectNode();
            final Class<?> itemClass = raw.isArray() ? raw.getComponentType() : innerType(field.getGenericType());
            items.put("type", describeType(itemClass));
            if (itemClass != null && itemClass.isEnum()) {
                items.set("choices", choicesOf(itemClass));
            }
            node.set("items", items);
            node.put("addButtonText", field.getAnnotation(ArrayWidget.class) == null ? ""
                    : field.getAnnotation(ArrayWidget.class).addButtonText());
            putDefault(node, value);
        } else if (NodeParameters.class.isAssignableFrom(raw)) {
            node.put("type", "object");
            node.set("fields", fieldsOf(raw, instantiate(raw), choices));
        } else {
            node.put("type", "unknown");
            node.put("javaType", raw.getName());
            if (value != null && isSimple(value)) {
                node.put("default", String.valueOf(value));
            }
        }
        annotateChoices(node, field, instance, choices);
        return node;
    }

    /** Умолчание может быть несериализуемым (Optional, внутренние объекты KNIME) — не роняем всю схему. */
    private static void putDefault(final ObjectNode node, final Object value) {
        if (!simpleTree(value, 0)) {
            node.putNull("default");
            node.put("defaultNote", "значение не сериализуется без UI");
            return;
        }
        try {
            node.set("default", value == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                    : MAPPER.valueToTree(value));
        } catch (final Throwable t) {
            node.putNull("default");
            node.put("defaultNote", "значение не сериализуется: " + t.getClass().getSimpleName());
        }
    }

    /** Только простые значения и списки простых: остальное не отдаём в JSON. */
    private static boolean simpleTree(final Object value, final int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean
                || value instanceof CharSequence || value instanceof Enum) {
            return true;
        }
        if (depth > 3) {
            return false;
        }
        if (value instanceof java.util.Collection) {
            for (final Object element : (java.util.Collection<?>) value) {
                if (!simpleTree(element, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (value.getClass().isArray()) {
            final int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (!simpleTree(java.lang.reflect.Array.get(value, i), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static void markTextual(final ObjectNode node, final Field field) {
        final TextInputWidget text = field.getAnnotation(TextInputWidget.class);
        if (text != null && !text.placeholder().isEmpty()) {
            node.put("placeholder", text.placeholder());
        }
    }

    private static void markNumeric(final ObjectNode node, final Field field) {
        final NumberInputWidget number = field.getAnnotation(NumberInputWidget.class);
        if (number != null && number.stepSize() > 0) {
            node.put("step", number.stepSize());
        }
    }

    private static void annotateChoices(final ObjectNode node, final Field field,
            final Object instance, final DynamicChoices choices) {
        final OptionalWidget optional = field.getAnnotation(OptionalWidget.class);
        if (optional != null) {
            node.put("optional", true);
        }
        final ChoicesProvider provider = field.getAnnotation(ChoicesProvider.class);
        if (provider == null) {
            return;
        }
        final Class<?> providerClass = provider.value();
        final ObjectNode source = node.putObject("choicesSource");
        source.put("provider", providerClass.getName());
        if (ColumnChoicesProvider.class.isAssignableFrom(providerClass)) {
            source.put("kind", "columns");
            source.put("note", "варианты зависят от входной таблицы");
        } else if (StringChoicesProvider.class.isAssignableFrom(providerClass)) {
            source.put("kind", "strings");
            source.put("note", "варианты вычисляются провайдером");
        } else if (EnumChoicesProvider.class.isAssignableFrom(providerClass)
                || providerClass.getName().startsWith("org.knime.node.parameters.widget.choices")) {
            source.put("kind", "enum");
        } else {
            source.put("kind", "dynamic");
        }
        if (choices != null) {
            choices.resolve(node, providerClass, instance);
        }
    }

    private static ArrayNode choicesOf(final Class<?> enumClass) {
        final ArrayNode choices = MAPPER.createArrayNode();
        final Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return choices;
        }
        for (final Object constant : constants) {
            final ObjectNode choice = choices.addObject();
            choice.put("value", constant.toString());
            choice.put("label", labelOf(enumClass, constant.toString()));
        }
        return choices;
    }

    /** Правило KNIME: @Label, иначе имя константы с заглавной буквы и пробелами. */
    private static String labelOf(final Class<?> enumClass, final String name) {
        try {
            final Field constField = enumClass.getField(name);
            final Label label = constField.getAnnotation(Label.class);
            if (label != null && !label.value().isEmpty()) {
                return label.value();
            }
        } catch (final NoSuchFieldException ignored) {
            // нет поля — берём имя
        }
        final String spaced = name.toLowerCase(java.util.Locale.ENGLISH).replace('_', ' ');
        return spaced.isEmpty() ? name : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String describeType(final Class<?> type) {
        if (type == null) {
            return "unknown";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type == String.class) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        if (NodeParameters.class.isAssignableFrom(type)) {
            return "object";
        }
        return "unknown";
    }

    private static Class<?> innerType(final Type generic) {
        if (generic instanceof ParameterizedType) {
            final Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class) {
                return (Class<?>) args[0];
            }
        }
        return null;
    }

    private static String titleOf(final Field field) {
        final Widget widget = field.getAnnotation(Widget.class);
        if (widget != null && !widget.title().isEmpty()) {
            return widget.title();
        }
        final String name = field.getName().startsWith("m_") ? field.getName().substring(2) : field.getName();
        final String spaced = name.replace('_', ' ');
        return spaced.isEmpty() ? name : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String configKey(final Field field) {
        final Persist persist = field.getAnnotation(Persist.class);
        if (persist != null && !persist.configKey().isEmpty()) {
            return persist.configKey();
        }
        return field.getName().startsWith("m_") ? field.getName().substring(2) : field.getName();
    }

    private static Object instantiate(final Class<?> type) {
        try {
            final java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static Object read(final Field field, final Object instance) {
        if (instance == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static boolean isSimple(final Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof CharSequence
                || value instanceof Enum;
    }

    static Field findField(final Class<?> start, final String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (final NoSuchFieldException ignored) {
                // ищем выше
            }
        }
        return null;
    }

    static Method findMethod(final Class<?> start, final String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    static List<String> fieldNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return names;
    }
}
