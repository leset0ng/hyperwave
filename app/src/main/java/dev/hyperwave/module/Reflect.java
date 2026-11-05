package dev.hyperwave.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    private Reflect() {}
    static Object field(Object receiver, String name) throws ReflectiveOperationException {
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static Object call(Object receiver, String name) throws ReflectiveOperationException {
        Method method = receiver.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(receiver);
    }
}
