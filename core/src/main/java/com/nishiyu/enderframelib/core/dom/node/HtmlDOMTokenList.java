package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.DOMException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete implementation of {@link DOMTokenList} backed by an {@link HtmlElement}'s class attribute.
 */
public final class HtmlDOMTokenList implements DOMTokenList {

    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");
    private final HtmlElement element;
    private final String attributeName;

    public HtmlDOMTokenList(HtmlElement element, String attributeName) {
        this.element = element;
        this.attributeName = attributeName;
    }

    private List<String> parseTokens() {
        String val = element.getAttribute(attributeName);
        if (val == null || val.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = WHITESPACE_SPLIT.split(val.trim());
        Set<String> unique = new LinkedHashSet<>();
        for (String p : parts) {
            if (!p.isEmpty()) unique.add(p);
        }
        return new ArrayList<>(unique);
    }

    private void syncTokens(List<String> tokens) {
        if (tokens.isEmpty()) {
            element.removeAttribute(attributeName);
        } else {
            element.setAttribute(attributeName, String.join(" ", tokens));
        }
    }

    private static void validateToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new DOMException("The token provided must not be empty.", "SyntaxError");
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                throw new DOMException("The token provided contains whitespace characters.", "InvalidCharacterError");
            }
        }
    }

    @Override
    public int getLength() {
        return parseTokens().size();
    }

    @Nullable
    @Override
    public String item(int index) {
        List<String> tokens = parseTokens();
        if (index < 0 || index >= tokens.size()) return null;
        return tokens.get(index);
    }

    @Override
    public boolean contains(String token) {
        validateToken(token);
        return parseTokens().contains(token);
    }

    @Override
    public void add(String... tokens) {
        if (tokens == null || tokens.length == 0) return;
        for (String t : tokens) {
            validateToken(t);
        }
        List<String> current = parseTokens();
        boolean changed = false;
        for (String t : tokens) {
            if (!current.contains(t)) {
                current.add(t);
                changed = true;
            }
        }
        if (changed) syncTokens(current);
    }

    @Override
    public void remove(String... tokens) {
        if (tokens == null || tokens.length == 0) return;
        for (String t : tokens) {
            validateToken(t);
        }
        List<String> current = parseTokens();
        boolean changed = false;
        for (String t : tokens) {
            if (current.remove(t)) {
                changed = true;
            }
        }
        if (changed) syncTokens(current);
    }

    @Override
    public boolean toggle(String token) {
        validateToken(token);
        List<String> current = parseTokens();
        if (current.contains(token)) {
            current.remove(token);
            syncTokens(current);
            return false;
        } else {
            current.add(token);
            syncTokens(current);
            return true;
        }
    }

    @Override
    public boolean toggle(String token, boolean force) {
        validateToken(token);
        List<String> current = parseTokens();
        if (force) {
            if (!current.contains(token)) {
                current.add(token);
                syncTokens(current);
            }
            return true;
        } else {
            if (current.remove(token)) {
                syncTokens(current);
            }
            return false;
        }
    }

    @Override
    public boolean replace(String oldToken, String newToken) {
        validateToken(oldToken);
        validateToken(newToken);
        List<String> current = parseTokens();
        int idx = current.indexOf(oldToken);
        if (idx < 0) return false;
        current.set(idx, newToken);
        syncTokens(current);
        return true;
    }

    @Override
    public String getValue() {
        String val = element.getAttribute(attributeName);
        return val != null ? val : "";
    }

    @Override
    public void setValue(String value) {
        if (value == null || value.isEmpty()) {
            element.removeAttribute(attributeName);
        } else {
            element.setAttribute(attributeName, value);
        }
    }

    @Override
    public Iterator<String> iterator() {
        return parseTokens().iterator();
    }
}
