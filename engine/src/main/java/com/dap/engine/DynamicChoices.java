package com.dap.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.widget.choices.ColumnChoicesProvider;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Вычисление вариантов для {@code @ChoicesProvider} без UI.
 * ColumnChoicesProvider требует спек входных портов, поэтому для статической
 * схемы варианты пустые (это помечается), а для узла в workflow берутся реальные спеки.
 */
final class DynamicChoices {

    private final NodeParametersInput input;
    private final boolean hasTableInput;

    private DynamicChoices(final NodeParametersInput input) {
        this.input = input;
        this.hasTableInput = input != null && input.getInTableSpecs() != null
                && input.getInTableSpecs().length > 0;
    }

    /** Контекст без входных данных: работают статические провайдеры, колонки — нет. */
    static DynamicChoices empty() {
        try {
            return new DynamicChoices(org.knime.core.webui.node.dialog.defaultdialog.NodeParametersUtil
                    .createNodeParametersInput(new PortObjectSpec[0]));
        } catch (final Throwable t) {
            return new DynamicChoices(null);
        }
    }

    /** Контекст конкретного узла в workflow: реальные спеки и объекты входных портов. */
    static DynamicChoices forNode(final WorkflowManager wfm, final NodeID nid) {
        try {
            final NodeContainer nc = wfm.getNodeContainer(nid);
            final boolean hidden = nc.getNrInPorts() > 0
                    && "Flow Variable".equals(nc.getInPort(0).getPortType().getName());
            final int start = hidden ? 1 : 0;
            final int count = Math.max(0, nc.getNrInPorts() - start);
            final PortType[] types = new PortType[count];
            for (int i = 0; i < count; i++) {
                types[i] = nc.getInPort(i + start).getPortType();
            }
            final PortObjectSpec[] specs = inputSpecs(wfm, nid, start, count);
            final NodeParametersInput in = org.knime.core.webui.node.dialog.defaultdialog.impl
                    .NodeParametersInputImpl.createNodeParametersInput(types, specs,
                            nc.getFlowObjectStack(), null);
            return new DynamicChoices(in);
        } catch (final Throwable t) {
            return empty();
        }
    }

    /** Спеки входных портов из уже выполненных источников (getNodeInputSpecs может быть пуст). */
    private static PortObjectSpec[] inputSpecs(final WorkflowManager wfm, final NodeID nid,
            final int start, final int count) {
        final PortObjectSpec[] specs = new PortObjectSpec[count];
        for (final org.knime.core.node.workflow.ConnectionContainer c : wfm.getConnectionContainers()) {
            final int dest = c.getDestPort();
            if (!c.getDest().equals(nid) || dest < start || dest >= start + count) {
                continue;
            }
            try {
                final NodeContainer source = wfm.getNodeContainer(c.getSource());
                if (source == null) {
                    continue;
                }
                final org.knime.core.node.workflow.NodeOutPort out = source.getOutPort(c.getSourcePort());
                PortObjectSpec spec = out.getPortObjectSpec();
                if (spec == null) {
                    final org.knime.core.node.port.PortObject object = out.getPortObject();
                    spec = object == null ? null : object.getSpec();
                }
                specs[dest - start] = spec;
            } catch (final Throwable ignored) {
                // порт ещё не готов
            }
        }
        return specs;
    }

    /** Заполняет choices для поля; ошибки остаются в choicesError, схема не падает. */
    void resolve(final ObjectNode field, final Class<?> providerClass, final Object enclosing) {
        if (providerClass == null) {
            return;
        }
        if (ColumnChoicesProvider.class.isAssignableFrom(providerClass) && !hasTableInput) {
            field.put("choicesNote", "варианты появятся, когда к узлу подключена входная таблица");
            return;
        }
        if (input == null) {
            field.put("choicesError", "NodeParametersInput недоступен");
            return;
        }
        try {
            final Object provider = instantiate(providerClass, enclosing);
            initialize(provider, input);
            final Method compute = providerClass.getMethod("computeState", NodeParametersInput.class);
            compute.setAccessible(true);
            final Object result = compute.invoke(provider, input);
            final ArrayNode choices = field.putArray("choices");
            if (result instanceof List) {
                for (final Object element : (List<?>) result) {
                    addChoice(choices, element);
                }
            }
            field.put("choicesResolved", true);
        } catch (final Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            field.put("choicesError", cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        }
    }

    /**
     * Провайдеры читают значения других полей через suppliers, которые обычно
     * устанавливает UI-фреймворк в init(). Даём no-op инициализатор: статические
     * варианты остаются, зависимые честно падают в choicesError.
     */
    private static void initialize(final Object provider, final NodeParametersInput input) {
        try {
            final Class<?> initializerType =
                    org.knime.node.parameters.updates.StateProvider.StateProviderInitializer.class;
            final Method init = provider.getClass().getMethod("init", initializerType);
            init.setAccessible(true);
            init.invoke(provider, new NoopInitializer(input));
        } catch (final Throwable ignored) {
            // без init провайдер может обойтись
        }
    }

    private static final class NoopInitializer implements
            org.knime.node.parameters.updates.StateProvider.StateProviderInitializer {
        private final NodeParametersInput input;

        NoopInitializer(final NodeParametersInput input) {
            this.input = input;
        }

        @Override
        public NodeParametersInput getNodeParametersInput() {
            return input;
        }

        @Override
        public <T> java.util.function.Supplier<T> computeFromValueSupplier(
                final Class<? extends org.knime.node.parameters.updates.ParameterReference<T>> ref) {
            return () -> null;
        }

        @Override
        public <T> java.util.function.Supplier<T> getValueSupplier(
                final Class<? extends org.knime.node.parameters.updates.ParameterReference<T>> ref) {
            return () -> null;
        }

        @Override
        public <T> void computeOnValueChange(
                final Class<? extends org.knime.node.parameters.updates.ParameterReference<T>> ref) {
            // нечего пересчитывать
        }

        @Override
        public <T> java.util.function.Supplier<T> computeFromProvidedState(
                final Class<? extends org.knime.node.parameters.updates.StateProvider<T>> provider) {
            return () -> null;
        }

        @Override
        public void computeOnButtonClick(
                final Class<? extends org.knime.node.parameters.updates.ButtonReference> ref) {
            // кнопок нет
        }

        @Override
        public void computeBeforeOpenDialog() {
            // нечего делать
        }

        @Override
        public void computeAfterOpenDialog() {
            // нечего делать
        }
    }

    private static void addChoice(final ArrayNode choices, final Object element) {
        if (element == null) {
            return;
        }
        final ObjectNode choice = choices.addObject();
        if (element instanceof CharSequence) {
            choice.put("value", element.toString());
            choice.put("label", element.toString());
            return;
        }
        final Object id = call(element, "id");
        final Object text = call(element, "text");
        final String value = id == null ? String.valueOf(element) : String.valueOf(id);
        choice.put("value", value);
        choice.put("label", text == null ? value : String.valueOf(text));
        final Object disabled = call(element, "disabled");
        if (Boolean.TRUE.equals(disabled)) {
            choice.put("disabled", true);
        }
    }

    private static Object instantiate(final Class<?> type, final Object enclosing) throws Exception {
        try {
            final Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (final NoSuchMethodException noDefault) {
            final Class<?> outer = type.getEnclosingClass();
            if (outer != null && enclosing != null) {
                final Constructor<?> ctor = type.getDeclaredConstructor(outer);
                ctor.setAccessible(true);
                return ctor.newInstance(enclosing);
            }
            throw noDefault;
        }
    }

    private static Object call(final Object target, final String name) {
        try {
            final Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
