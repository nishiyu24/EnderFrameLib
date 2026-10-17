package com.nishiyu.enderframelib.core.dom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;

public class HtmlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlParser.class);

    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 100;

    // VoidElements に統合したため、ここでは PRESERVE_WHITESPACE_TAGS のみ定義
    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");

    public static HtmlNode parse(String html) {
        // パーサ呼び出しの度にIDカウンタをリセットし、安定したIDを生成する
        NodeId.resetCounter();

        HtmlNode root = new HtmlNode(NodeType.ROOT, "root");
        root.setId("html_root");
        Stack<HtmlNode> stack = new Stack<>();
        stack.push(root);

        int nodeCount = 0;

        for (HtmlTokenizer.Token token : HtmlTokenizer.tokenize(html)) {
            if (++nodeCount > MAX_NODES) {
                LOGGER.warn("HTML Parser: MAX_NODES ({}) exceeded. Truncating document.", MAX_NODES);
                break;
            }

            if (token.type == HtmlTokenizer.Token.Type.START_TAG) {
                if (stack.size() > MAX_DEPTH) continue;

                HtmlNode node = new HtmlNode(NodeType.ELEMENT, token.name);
                node.parent = stack.peek();

                for (var entry : token.attrs.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (val != null && val.length() > 4096) val = val.substring(0, 4096);
                    node.attrs.put(key, val);
                }

                if (node.getId().isEmpty()) {
                    node.setId(NodeId.generateId());
                }

                stack.peek().children.add(node);

                boolean isSelfClosing = token.isSelfClosing || VoidElements.isVoid(token.name);
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
                    String raw = token.text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty()) {
                        if (!token.text.contains("\n") && !token.text.contains("\r") && !parent.children.isEmpty()) {
                            text = " ";
                        } else {
                            text = "";
                        }
                    } else {
                        boolean leadSpace = !token.text.isEmpty() && Character.isWhitespace(token.text.charAt(0))
                                && token.text.charAt(0) != '\n' && token.text.charAt(0) != '\r';
                        boolean trailSpace = !token.text.isEmpty() && Character.isWhitespace(token.text.charAt(token.text.length() - 1))
                                && token.text.charAt(token.text.length() - 1) != '\n' && token.text.charAt(token.text.length() - 1) != '\r';
                        text = (leadSpace ? " " : "") + trimmed + (trailSpace ? " " : "");
                    }
                }

                if (text.length() > 16384) text = text.substring(0, 16384);

                if (!text.isEmpty()) {
                    HtmlNode textNode = HtmlNode.createTextNode(text);
                    textNode.setId(NodeId.generateTextId());
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