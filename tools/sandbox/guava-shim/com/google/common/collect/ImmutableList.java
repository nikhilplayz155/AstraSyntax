package com.google.common.collect;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Sandbox-only stand-in for Guava's {@code ImmutableList} (see Preconditions for the why). */
public final class ImmutableList<E> extends AbstractList<E> {

    private final Object[] elements;

    private ImmutableList(Object[] elements) {
        this.elements = elements;
    }

    public static <E> ImmutableList<E> of() {
        return new ImmutableList<>(new Object[0]);
    }

    public static <E> ImmutableList<E> of(E element) {
        return new ImmutableList<>(new Object[] {element});
    }

    public static <E> ImmutableList<E> of(E first, E second) {
        return new ImmutableList<>(new Object[] {first, second});
    }

    public static <E> ImmutableList<E> of(E first, E second, E third) {
        return new ImmutableList<>(new Object[] {first, second, third});
    }

    @SafeVarargs
    public static <E> ImmutableList<E> of(E... elements) {
        return new ImmutableList<>(elements.clone());
    }

    public static <E> ImmutableList<E> copyOf(Collection<? extends E> elements) {
        return new ImmutableList<>(new ArrayList<>(elements).toArray());
    }

    public static <E> ImmutableList<E> copyOf(Iterable<? extends E> elements) {
        List<E> list = new ArrayList<>();
        for (E element : elements) list.add(element);
        return new ImmutableList<>(list.toArray());
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) elements[index];
    }

    @Override
    public int size() {
        return elements.length;
    }
}
