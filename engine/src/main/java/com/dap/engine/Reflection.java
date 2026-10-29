package com.dap.engine;

import java.lang.reflect.Method;

/** Поиск методов в иерархии классов: часть API KNIME защищена модификатором protected. */
final class Reflection {

    private Reflection() {
    }

    static Object call(final Object target, final String name, final Class<?>[] types,
            final Object... args) throws Exception {
        Method m = null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                m = c.getDeclaredMethod(name, types);
                break;
            } catch (final NoSuchMethodException e) {
                // ищем в родителе
            }
        }
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        }
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
