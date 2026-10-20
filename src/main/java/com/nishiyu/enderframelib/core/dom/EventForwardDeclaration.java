package com.nishiyu.enderframelib.core.dom;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「この要素のこのイベントをサーバーへ送る」という宣言。要素の属性で書く。
 *
 * <pre>
 * &lt;button data-forward="click" data-forward-action="buy:sword"&gt;
 * &lt;input  data-forward="input:value change:value" data-forward-action="rename"&gt;
 * &lt;div    data-forward="wheel:point mousedown:target" data-forward-action="pan"&gt;
 * </pre>
 *
 * <p>{@code data-forward} は空白（またはカンマ）区切りの {@code イベント種別[:送る値]}。
 * 送る値は {@link Payload} の名前（既定は {@link Payload#DEFAULT}）。{@code data-forward-action} が
 * サーバーのレシーバへ渡るアクション名（{@code name:arg} の形も可）。
 * 宣言した要素の子孫で起きたイベントも、バブリングで届けば転送される。</p>
 *
 * <p>従来の {@code onclick="server:アクション名"} は、この宣言の糖衣。{@link #declaredActionNames} は両方を数える。</p>
 *
 * <p>サーバー所有の部分木（{@link Ownership#SERVER}）の入力コントロールは、値の書き戻しのために
 * {@code input:value} と {@code change:value} をアクション名なしで暗黙に宣言した扱いになる。</p>
 *
 * <p>転送の順序は、クライアントのリスナーが先に走り、{@code preventDefault} されなければ送る（{@link HtmlNode#dispatchEvent}）。</p>
 */
public record EventForwardDeclaration(String eventType, Payload payload, String action) {

    public static final String ATTRIBUTE = "data-forward";
    public static final String ACTION_ATTRIBUTE = "data-forward-action";

    private static final String SERVER_ACTION_PREFIX = "server:";

    /** 送る値の取り方。 */
    public enum Payload {
        /** イベントの種類に応じた既定値（入力・変更は現在値、クリックは対象 id と座標、キーは key、ホイールは増分）。 */
        DEFAULT,
        /** フォームコントロールの現在値（チェックボックスとラジオは {@code checked} の真偽）。 */
        VALUE,
        /** {@code KeyboardEvent.key}。 */
        KEY,
        /** ポインタの {@code clientX,clientY}（ホイールは増分）。 */
        POINT,
        /** イベントの対象要素の id。 */
        TARGET,
        /** 値なし。 */
        NONE;

        @Nullable
        static Payload parse(String name) {
            if (name == null || name.isEmpty()) return DEFAULT;
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    public EventForwardDeclaration {
        eventType = eventType.toLowerCase(Locale.ROOT);
        action = action != null ? action : "";
    }

    /** {@code node} 自身に宣言された転送（明示 → 暗黙の順。同じイベント種別は明示が勝つ）。 */
    public static List<EventForwardDeclaration> declaredOn(HtmlNode node) {
        List<EventForwardDeclaration> declarations = explicitlyDeclaredOn(node);
        if (isValueControl(node) && node.ownership() == Ownership.SERVER) {
            for (String valueEvent : new String[]{"input", "change"}) {
                if (declarations.stream().noneMatch(d -> d.eventType.equals(valueEvent))) {
                    declarations.add(new EventForwardDeclaration(valueEvent, Payload.VALUE, ""));
                }
            }
        }
        return declarations;
    }

    /** {@code node} 自身に宣言された転送のうち、{@code eventType} のもの。 */
    public static List<EventForwardDeclaration> declaredOn(HtmlNode node, String eventType) {
        String type = eventType.toLowerCase(Locale.ROOT);
        List<EventForwardDeclaration> matching = new ArrayList<>(1);
        for (EventForwardDeclaration declaration : declaredOn(node)) {
            if (declaration.eventType.equals(type)) matching.add(declaration);
        }
        return matching;
    }

    /**
     * {@code node} が送出するアクション名の頭（{@code name:arg} の {@code name}）。
     * {@code data-forward-action} と、糖衣の {@code on*="server:…"} の両方を数える。
     * サーバーは受け取ったアクション名を、自分が送出した DOM のこの集合と突き合わせて検証する。
     */
    public static Set<String> declaredActionNames(HtmlNode node) {
        Set<String> names = new LinkedHashSet<>();
        addActionHead(names, node.attrs.get(ACTION_ATTRIBUTE));
        for (var attribute : node.attrs.entrySet()) {
            String value = attribute.getValue();
            if (value == null || !attribute.getKey().regionMatches(true, 0, "on", 0, 2)) continue;
            if (value.startsWith(SERVER_ACTION_PREFIX)) {
                addActionHead(names, value.substring(SERVER_ACTION_PREFIX.length()));
            }
        }
        return names;
    }

    /** サーバーのレシーバへ渡すアクション名を持つか（値の書き戻しだけの宣言は持たない）。 */
    public boolean hasAction() {
        return !action.isEmpty();
    }

    private static List<EventForwardDeclaration> explicitlyDeclaredOn(HtmlNode node) {
        List<EventForwardDeclaration> declarations = new ArrayList<>();
        String spec = node.attrs.get(ATTRIBUTE);
        if (spec == null || spec.isBlank()) return declarations;

        String action = node.attrs.getOrDefault(ACTION_ATTRIBUTE, "").trim();
        for (String token : spec.trim().split("[\\s,]+")) {
            int colon = token.indexOf(':');
            String type = colon >= 0 ? token.substring(0, colon) : token;
            Payload payload = Payload.parse(colon >= 0 ? token.substring(colon + 1) : "");
            if (type.isEmpty() || payload == null) continue;

            EventForwardDeclaration declaration = new EventForwardDeclaration(type, payload, action);
            // アクション名の無い宣言は、フォームの値の書き戻し以外には宛先が無い。
            boolean valueSync = isValueControl(node) && (declaration.eventType.equals("input") || declaration.eventType.equals("change"));
            if (declaration.hasAction() || valueSync) declarations.add(declaration);
        }
        return declarations;
    }

    private static boolean isValueControl(HtmlNode node) {
        if (node.nodeType != HtmlNode.NodeType.ELEMENT) return false;
        String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
        return tag.equals("input") || tag.equals("textarea") || tag.equals("select");
    }

    private static void addActionHead(Set<String> names, @Nullable String action) {
        if (action == null || action.isBlank()) return;
        String trimmed = action.trim();
        int colon = trimmed.indexOf(':');
        String head = colon >= 0 ? trimmed.substring(0, colon) : trimmed;
        if (!head.isEmpty()) names.add(head);
    }
}
