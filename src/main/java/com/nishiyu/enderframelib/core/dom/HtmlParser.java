package com.nishiyu.enderframelib.core.dom;

import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

public class HtmlParser {

    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 100;
    private static final Set<String> VOID_TAGS = Set.of(
            "br", "hr", "img", "input", "meta", "link",
            "mc-fluid", "mc-entity", "mc-scene", "mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar"
    );
    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");
    private static final AtomicInteger globalAutoId = new AtomicInteger(0);

    public static HtmlNode parse(String html) {
        HtmlNode root = new HtmlNode("root");
        root.id = "html_root";
        Stack<HtmlNode> stack = new Stack<>();
        stack.push(root);

        int nodeCount = 0;

        for (HtmlTokenizer.Token token : HtmlTokenizer.tokenize(html)) {
            if (++nodeCount > MAX_NODES) break;

            if (token.type == HtmlTokenizer.Token.Type.START_TAG) {
                if (stack.size() > MAX_DEPTH) continue;

                HtmlNode node = new HtmlNode(token.name);
                node.parent = stack.peek();

                for (var entry : token.attrs.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (val != null && val.length() > 4096) val = val.substring(0, 4096);

                    node.attrs.put(key, val);
                    if (key.equals("id")) node.id = val;
                    else if (key.equals("class")) {
                        for (String c : Objects.requireNonNull(val).trim().split("\\s+")) {
                            if (!c.isEmpty()) node.classes.add(c);
                        }
                    }
                }

                if (node.id.isEmpty()) {
                    node.id = "html_gen_" + globalAutoId.incrementAndGet();
                    node.attrs.put("id", node.id);
                }

                stack.peek().children.add(node);

                boolean isSelfClosing = token.isSelfClosing || VOID_TAGS.contains(token.name);
                if (!isSelfClosing) {
                    stack.push(node);
                }

            } else if (token.type == HtmlTokenizer.Token.Type.END_TAG) {
                int matchIndex = -1;
                for (int i = stack.size() - 1; i > 0; i--) {
                    if (stack.get(i).tag.equals(token.name)) {
                        matchIndex = i;
                        break;
                    }
                }
                if (matchIndex != -1) {
                    while (stack.size() > matchIndex) stack.pop();
                }

            } else if (token.type == HtmlTokenizer.Token.Type.TEXT) {
                HtmlNode parent = stack.peek();
                boolean preserveWs = token.isRawText || PRESERVE_WHITESPACE_TAGS.contains(parent.tag);

                String text;
                if (preserveWs) {
                    text = token.text.replace("\r\n", "\n");
                    if (text.startsWith("\n")) text = text.substring(1);
                    if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                } else {
                    text = token.text.trim();
                }

                if (text.length() > 16384) text = text.substring(0, 16384);

                if (!text.isEmpty()) {
                    HtmlNode textNode = new HtmlNode("#text");
                    textNode.text = text;
                    textNode.id = "html_txt_" + globalAutoId.incrementAndGet();
                    textNode.parent = parent;
                    parent.children.add(textNode);

                    if ("textarea".equals(parent.tag) && !parent.attrs.containsKey("value")) {
                        parent.attrs.put("value", text);
                    }
                }
            }
        }
        return root;
    }
}