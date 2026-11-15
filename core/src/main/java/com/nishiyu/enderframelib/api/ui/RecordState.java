package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Objects;

/**
 * Typed two-way binding between a Java {@code record} and per-component {@link State}s.
 *
 * <pre>{@code
 * record Profile(String name, int age, boolean admin) {}
 * RecordState<Profile> profile = RecordState.of(new Profile("a", 3, false));
 * State<String> name = profile.field("name", String.class);   // bind to an input
 * name.set("b");                                               // profile.get().name() == "b"
 * }</pre>
 *
 * Setting a field rebuilds the record through its canonical constructor (so record invariants still run);
 * setting the whole record updates every field state. The component name and type are checked when the field
 * state is created.
 */
@ApiStatus.AvailableSince("1.0")
public final class RecordState<R extends Record> {

    private final State<R> state;
    private final Class<R> type;
    private final Constructor<R> canonical;

    @SuppressWarnings("unchecked")
    private RecordState(R initial) {
        this.type = (Class<R>) initial.getClass();
        this.state = State.of(initial);
        RecordComponent[] comps = type.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
        try {
            this.canonical = type.getDeclaredConstructor(types);
            this.canonical.setAccessible(true);
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot call the canonical constructor of " + type.getName(), e);
        }
    }

    public static <R extends Record> RecordState<R> of(R initial) {
        return new RecordState<>(Objects.requireNonNull(initial, "initial"));
    }

    /** The whole record as a state (use with {@link UI#watch}). */
    public State<R> state() {
        return state;
    }

    public R get() {
        return state.get();
    }

    public void set(R next) {
        state.set(Objects.requireNonNull(next, "next"));
    }

    /** A two-way state for one record component. {@code valueType} must match the component type (boxed for primitives). */
    @SuppressWarnings("unchecked")
    public <V> State<V> field(String name, Class<V> valueType) {
        RecordComponent[] comps = type.getRecordComponents();
        int index = -1;
        for (int i = 0; i < comps.length; i++) if (comps[i].getName().equals(name)) index = i;
        if (index < 0) throw new IllegalArgumentException(type.getSimpleName() + " has no component " + name);
        Class<?> declared = box(comps[index].getType());
        if (!declared.isAssignableFrom(box(valueType)) && !box(valueType).isAssignableFrom(declared)) {
            throw new IllegalArgumentException(type.getSimpleName() + "." + name + " is " + declared.getSimpleName()
                    + ", not " + valueType.getSimpleName());
        }
        final int at = index;
        final Method accessor = comps[index].getAccessor();
        return state.lens(r -> (V) read(accessor, r), (r, v) -> with(r, at, v));
    }

    private static Object read(Method accessor, Object record) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private R with(R current, int index, Object value) {
        RecordComponent[] comps = type.getRecordComponents();
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) args[i] = i == index ? value : read(comps[i].getAccessor(), current);
        try {
            return canonical.newInstance(args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re; // record validation errors pass through
            throw new IllegalStateException(e.getCause());
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        return switch (c.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            default -> Character.class;
        };
    }
}
