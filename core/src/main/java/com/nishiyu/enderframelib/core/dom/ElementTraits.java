package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.Locale;

/**
 * 要素の性質（タグ名・type 属性から決まる分類）の唯一の表（S2）。
 *
 * <p>「フォーム部品か」「テキスト入力か」「チェックできるか」などを各段が自分のタグ名比較で持つと、
 * 部品を足したときの直し漏れが「ある段では部品、別の段では普通の箱」という食い違いになる。
 * 各段はここを引く。タグ名は大文字小文字を区別しない（パーサは小文字で持つが、DOM API 経由は保証しない）。
 * 確保なし（小文字化は既に小文字なら同じ文字列を返す）。
 */
public final class ElementTraits {
    private ElementTraits() {}

    /** 送信対象・検証対象になるフォーム部品（input / button / select / textarea）。 */
    public static boolean isFormControl(String tag) {
        return switch (lower(tag)) {
            case "input", "button", "select", "textarea" -> true;
            default -> false;
        };
    }

    public static boolean isFormControl(HtmlNode node) {
        return node != null && isFormControl(node.tag);
    }

    /** {@code value} を持つ部品（input / textarea / select）。button は含まない。 */
    public static boolean isValueControl(String tag) {
        return switch (lower(tag)) {
            case "input", "textarea", "select" -> true;
            default -> false;
        };
    }

    /** 文字を打ち込める部品の入れ物（input / textarea）。type による絞り込みは {@link #isTextControl}。 */
    public static boolean isTextEntryTag(String tag) {
        return switch (lower(tag)) {
            case "input", "textarea" -> true;
            default -> false;
        };
    }

    /** 行の断片に分けず 1 つの塊として描く要素（テキスト・画像・フォームの部品・ゲージ・canvas・svg）。 */
    public static boolean isNonFragmented(String tag) {
        return switch (tag == null ? "" : tag) {
            case "#text", "img", "input", "select", "progress", "meter", "textarea", "canvas", "svg" -> true;
            default -> false;
        };
    }

    /** 数値の表示部品（progress / meter）。 */
    public static boolean isGauge(String tag) {
        return switch (lower(tag)) {
            case "progress", "meter" -> true;
            default -> false;
        };
    }

    /** チェックできる部品（type が checkbox / radio の input）。 */
    public static boolean isCheckable(HtmlNode node) {
        if (node == null || !"input".equalsIgnoreCase(node.tag)) return false;
        String type = node.attrs.getOrDefault("type", "");
        return "checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type);
    }

    /** キャレットを持つテキスト入力（textarea、または文字を打てる type の input）。 */
    public static boolean isTextControl(HtmlNode node) {
        if (node == null) return false;
        if ("textarea".equalsIgnoreCase(node.tag)) return true;
        if (!"input".equalsIgnoreCase(node.tag)) return false;
        return switch (node.attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT)) {
            case "text", "search", "url", "email", "password", "tel", "number", "" -> true;
            default -> false;
        };
    }

    /** ラジオボタン（type が radio の input）。 */
    public static boolean isRadio(HtmlNode node) {
        return node != null && "input".equalsIgnoreCase(node.tag) && "radio".equalsIgnoreCase(node.attrs.get("type"));
    }

    private static String lower(String tag) {
        return tag == null ? "" : tag.toLowerCase(Locale.ROOT);
    }
}
