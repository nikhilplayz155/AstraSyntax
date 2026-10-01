package com.google.common.collect;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Sandbox-only stand-in for Guava's {@code ImmutableMap} (see Preconditions for the why). */
public final class ImmutableMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> delegate;

    private ImmutableMap(Map<K, V> values) {
        this.delegate = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static <K, V> ImmutableMap<K, V> of() {
        return new ImmutableMap<>(Map.of());
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        return new ImmutableMap<>(map);
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return new ImmutableMap<>(map);
    }

    public static <K, V> ImmutableMap<K, V> copyOf(Map<? extends K, ? extends V> values) {
        Map<K, V> map = new LinkedHashMap<>();
        map.putAll(values);
        return new ImmutableMap<>(map);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }
}
