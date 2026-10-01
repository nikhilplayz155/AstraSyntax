package com.google.common.collect;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Sandbox-only stand-in for Guava's {@code Maps} (see Preconditions for the why). */
public final class Maps {

    private Maps() { }

    public static <K, V> HashMap<K, V> newHashMap() {
        return new HashMap<>();
    }

    public static <K, V> HashMap<K, V> newHashMap(Map<? extends K, ? extends V> source) {
        return new HashMap<>(source);
    }

    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap() {
        return new LinkedHashMap<>();
    }

    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap(Map<? extends K, ? extends V> source) {
        return new LinkedHashMap<>(source);
    }

    public static <K, V> TreeMap<K, V> newTreeMap() {
        return new TreeMap<>();
    }

    public static <K extends Enum<K>, V> EnumMap<K, V> newEnumMap(Class<K> type) {
        return new EnumMap<>(type);
    }

    public static <K extends Enum<K>, V> ImmutableMap<K, V> immutableEnumMap(Map<K, ? extends V> source) {
        return ImmutableMap.copyOf(source);
    }

    public static <K, V> ConcurrentHashMap<K, V> newConcurrentMap() {
        return new ConcurrentHashMap<>();
    }
}
