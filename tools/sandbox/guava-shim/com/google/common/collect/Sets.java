package com.google.common.collect;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Sandbox-only stand-in for Guava's {@code Sets} (see Preconditions for the why). */
public final class Sets {

    private Sets() { }

    public static <E> HashSet<E> newHashSet() {
        return new HashSet<>();
    }

    public static <E> HashSet<E> newHashSet(java.util.Collection<? extends E> source) {
        return new HashSet<>(source);
    }

    public static <E> LinkedHashSet<E> newLinkedHashSet() {
        return new LinkedHashSet<>();
    }

    public static <E> TreeSet<E> newTreeSet() {
        return new TreeSet<>();
    }

    public static <E> Set<E> newConcurrentHashSet() {
        return ConcurrentHashMap.newKeySet();
    }

    public static <E> Set<E> newIdentityHashSet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }
}
