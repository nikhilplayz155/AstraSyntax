package com.google.common.collect;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Sandbox-only stand-in for Guava's {@code ImmutableSet} (see Preconditions for the why). */
public final class ImmutableSet<E> extends AbstractSet<E> {

    private final Set<E> delegate;

    private ImmutableSet(Collection<? extends E> elements) {
        this.delegate = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(elements));
    }

    public static <E> ImmutableSet<E> of() {
        return new ImmutableSet<>(List.of());
    }

    @SafeVarargs
    public static <E> ImmutableSet<E> of(E... elements) {
        return new ImmutableSet<>(new ArrayList<>(List.of(elements)));
    }

    public static <E> ImmutableSet<E> of(E first, E second) {
        List<E> list = new ArrayList<>();
        list.add(first);
        list.add(second);
        return new ImmutableSet<>(list);
    }

    public static <E> ImmutableSet<E> copyOf(Collection<? extends E> elements) {
        return new ImmutableSet<>(elements);
    }

    public static <E> ImmutableSet<E> copyOf(Iterable<? extends E> elements) {
        List<E> list = new ArrayList<>();
        for (E element : elements) list.add(element);
        return new ImmutableSet<>(list);
    }

    @Override
    public Iterator<E> iterator() {
        return delegate.iterator();
    }

    @Override
    public int size() {
        return delegate.size();
    }
}
