package io.astra.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflective helpers.
 *
 * <p>AstraSyntax never uses reflection inside hot per-event paths. It is used only
 * where a capability genuinely may or may not exist (Folia schedulers, optional
 * Vault/PlaceholderAPI/Citizens/WorldGuard bridges, the server command map).
 * Lookups are resolved once and cached; failures degrade to "capability
 * unavailable" instead of throwing into the server.</p>
 */
public final class Reflect {

    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> STATIC_METHODS = new ConcurrentHashMap<>();

    private Reflect() {}

    /** Load a class by name, returning an empty optional when absent. */
    public static Optional<Class<?>> findClass(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try {
                return Optional.of(Class.forName(n));
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        });
    }

    /** True when the class exists in the current server's class path. */
    public static boolean hasClass(String name) {
        return findClass(name).isPresent();
    }

    /** Look up a public method by name and exact parameter types. */
    public static Optional<Method> findMethod(Class<?> owner, String name, Class<?>... params) {
        String key = owner.getName() + '#' + name + ':' + java.util.Arrays.toString(params);
        return METHODS.computeIfAbsent(key, k -> {
            Class<?> current = owner;
            while (current != null) {
                try {
                    Method m = current.getMethod(name, params);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (Throwable ignored) {
                    current = current.getSuperclass();
                }
            }
            return Optional.empty();
        });
    }

    /** Look up a method by name and parameter count (types resolved dynamically). */
    public static Optional<Method> findMethodByArity(Class<?> owner, String name, int parameterCount) {
        String key = owner.getName() + '#' + name + '/' + parameterCount;
        return STATIC_METHODS.computeIfAbsent(key, k -> {
            Class<?> current = owner;
            while (current != null) {
                for (Method m : current.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == parameterCount) {
                        m.setAccessible(true);
                        return Optional.of(m);
                    }
                }
                current = current.getSuperclass();
            }
            return Optional.empty();
        });
    }

    /** Look up a field by name, walking up the hierarchy. */
    public static Optional<Field> findField(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        return FIELDS.computeIfAbsent(key, k -> {
            Class<?> current = owner;
            while (current != null) {
                try {
                    Field f = current.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (Throwable ignored) {
                    current = current.getSuperclass();
                }
            }
            return Optional.empty();
        });
    }

    /** Invoke a method, converting any failure into {@code null}. */
    public static Object invokeQuietly(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Invoke a method through its declaring class name and parameter count. */
    public static Object invokeQuietly(Object target, String name, int arity, Object... args) {
        if (target == null) return null;
        Method method = findMethodByArity(target.getClass(), name, arity).orElse(null);
        return invokeQuietly(method, target, args);
    }

    /** Read a field, converting any failure into {@code null}. */
    public static Object readQuietly(Field field, Object target) {
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
