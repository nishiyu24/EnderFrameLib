package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A data structure representing form fields and their values.
 *
 * <p>Conforms to WHATWG XMLHttpRequest §4 Interface FormData.</p>
 */
@ApiStatus.AvailableSince("1.0")
public class FormData implements Iterable<FormData.Entry> {

    /**
     * An entry within a {@link FormData} collection.
     */
    @ApiStatus.AvailableSince("1.1")
    public record Entry(String name, String value, String filename) {

        public Entry(String name, String value) {
            this(name, value, "");
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public FormData() {
    }

    public FormData(List<Entry> initialEntries) {
        if (initialEntries != null) {
            entries.addAll(initialEntries);
        }
    }

    public void append(String name, String value) {
        entries.add(new Entry(name != null ? name : "", value != null ? value : "", ""));
    }

    public void append(String name, String value, String filename) {
        entries.add(new Entry(name != null ? name : "", value != null ? value : "", filename != null ? filename : ""));
    }

    public void delete(String name) {
        String key = name != null ? name : "";
        entries.removeIf(entry -> key.equals(entry.name()));
    }

    @Nullable
    public String get(String name) {
        String key = name != null ? name : "";
        for (Entry entry : entries) {
            if (key.equals(entry.name())) {
                return entry.value();
            }
        }
        return null;
    }

    public List<String> getAll(String name) {
        String key = name != null ? name : "";
        List<String> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (key.equals(entry.name())) {
                result.add(entry.value());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean has(String name) {
        return get(name) != null;
    }

    public void set(String name, String value) {
        set(name, value, "");
    }

    public void set(String name, String value, String filename) {
        String key = name != null ? name : "";
        String val = value != null ? value : "";
        String file = filename != null ? filename : "";
        boolean replaced = false;
        List<Entry> next = new ArrayList<>();
        for (Entry entry : entries) {
            if (!key.equals(entry.name())) {
                next.add(entry);
            } else if (!replaced) {
                next.add(new Entry(key, val, file));
                replaced = true;
            }
        }
        if (!replaced) {
            next.add(new Entry(key, val, file));
        }
        entries.clear();
        entries.addAll(next);
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public Iterator<Entry> iterator() {
        return getEntries().iterator();
    }

    public List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries) {
            keys.add(entry.name());
        }
        return Collections.unmodifiableList(keys);
    }

    public List<String> getValues() {
        List<String> values = new ArrayList<>();
        for (Entry entry : entries) {
            values.add(entry.value());
        }
        return Collections.unmodifiableList(values);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(entry.name(), StandardCharsets.UTF_8));
            out.append('=');
            out.append(URLEncoder.encode(entry.value(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}