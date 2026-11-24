package com.nishiyu.enderframelib.core.dom.node;

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

    /** 幅を最小内容幅（既定の部品の幅）で決める部品（input / select / progress）。 */
    public static boolean isControlSizedByContent(String tag) {
        return switch (tag == null ? "" : tag) {
            case "input", "select", "progress" -> true;
            default -> false;
        };
    }

    /** 標準の置換画像（img / canvas / svg）。ホストが足す画像相当は {@code ReplacedElements} が持つ。 */
    public static boolean isBuiltinReplacedImage(String tag) {
        return switch (tag == null ? "" : tag) {
            case "img", "canvas", "svg" -> true;
            default -> false;
        };
    }

    /** 標準の置換メディア（画像・video・iframe）。 */
    public static boolean isBuiltinReplacedMedia(String tag) {
        return isBuiltinReplacedImage(tag) || "video".equals(tag) || "iframe".equals(tag);
    }

    /** {@code display} の宣言が無いときにインラインとして扱うタグ。ホスト登録の置換要素は {@code ReplacedElements} が足す。 */
    public static boolean isDefaultInline(String tag) {
        return switch (tag == null ? "" : tag) {
            case "br", "span", "a", "b", "i", "strong", "em", "img", "canvas", "svg", "input", "button", "select", "progress" -> true;
            default -> false;
        };
    }

    /** 子を箱にしない要素（textarea は値の文字、svg は図形を自分で読む）。 */
    public static boolean hasOpaqueContent(String tag) {
        return switch (tag == null ? "" : lower(tag)) {
            case "textarea", "svg" -> true;
            default -> false;
        };
    }

    /** 閉じた {@code details} の中で、箱を作らない子か（{@code summary} 以外）。 */
    public static boolean isHiddenByClosedDetails(HtmlNode details, boolean open, HtmlNode child) {
        return !open && "details".equalsIgnoreCase(details.tag) && !"summary".equalsIgnoreCase(child.tag);
    }

    /** 行の断片を子のあいだで畳まない要素（リスト・fieldset・details・文字入力・svg・select・button）。 */
    public static boolean blocksInlineRunFolding(String tag) {
        return switch (tag == null ? "" : lower(tag)) {
            case "ol", "fieldset", "details", "textarea", "svg", "select", "button" -> true;
            default -> false;
        };
    }

    /** click の既定の操作を持つ要素（button / select / summary）。 */
    public static boolean isClickActivated(String tag) {
        return switch (tag == null ? "" : tag) {
            case "button", "select", "summary" -> true;
            default -> false;
        };
    }

    /** Tab で到達できる要素（type=hidden でない input・button・select・textarea・summary・href 付きの a）。 */
    public static boolean isNaturallyFocusable(HtmlNode node) {
        return switch (lower(node.tag)) {
            case "input" -> !"hidden".equalsIgnoreCase(node.attrs.getOrDefault("type", ""));
            case "button", "select", "textarea", "summary" -> true;
            case "a" -> node.attrs.containsKey("href");
            default -> false;
        };
    }

    /** スライダー（type が range の input）。 */
    public static boolean isRangeInput(HtmlNode node) {
        return node != null && "input".equals(node.tag) && "range".equalsIgnoreCase(node.attrs.getOrDefault("type", "").trim());
    }

    /** 画像の候補選び（srcset / sizes）をする要素（img / picture）。ビューポートの寸法に依る。 */
    public static boolean selectsImageCandidates(String tag) {
        return "img".equals(tag) || "picture".equals(tag);
    }

    /** 標準の置換メディアのうち、固有寸法を持たず既定寸法で測る要素（video / iframe）。 */
    public static boolean isFallbackSizedMedia(String tag) {
        return "video".equals(tag) || "iframe".equals(tag);
    }

    /** 中身のテキストが属性ハッシュ（測定キー）に効く要素（textarea / select / option）。 */
    public static boolean contentTextAffectsMeasure(String tag) {
        return "textarea".equals(tag) || "select".equals(tag) || "option".equals(tag);
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

    // ── 役割ごとの名前つきの判定（タグ名の文字列比較を各段に書かせない。大文字小文字は区別しない） ──

    public static boolean isTextarea(String tag) {
        return "textarea".equalsIgnoreCase(tag);
    }

    public static boolean isSelect(String tag) {
        return "select".equalsIgnoreCase(tag);
    }

    public static boolean isOption(String tag) {
        return "option".equalsIgnoreCase(tag);
    }

    public static boolean isDetails(String tag) {
        return "details".equalsIgnoreCase(tag);
    }

    public static boolean isSummary(String tag) {
        return "summary".equalsIgnoreCase(tag);
    }

    public static boolean isFieldset(String tag) {
        return "fieldset".equalsIgnoreCase(tag);
    }

    public static boolean isCanvas(String tag) {
        return "canvas".equalsIgnoreCase(tag);
    }

    public static boolean isLegend(String tag) {
        return "legend".equalsIgnoreCase(tag);
    }

    public static boolean isOrderedList(String tag) {
        return "ol".equalsIgnoreCase(tag);
    }

    public static boolean isImage(String tag) {
        return "img".equalsIgnoreCase(tag);
    }

    public static boolean isButton(String tag) {
        return "button".equalsIgnoreCase(tag);
    }

    public static boolean isAnchor(String tag) {
        return "a".equalsIgnoreCase(tag);
    }

    public static boolean isInputTag(String tag) {
        return "input".equalsIgnoreCase(tag);
    }

    public static boolean isLabel(String tag) {
        return "label".equalsIgnoreCase(tag);
    }

    public static boolean isSvg(String tag) {
        return "svg".equalsIgnoreCase(tag);
    }

    public static boolean isForm(String tag) {
        return "form".equalsIgnoreCase(tag);
    }

    /** 再生部品（video / audio）。 */
    public static boolean isMediaPlayer(String tag) {
        return "video".equalsIgnoreCase(tag) || "audio".equalsIgnoreCase(tag);
    }

    private static String lower(String tag) {
        return tag == null ? "" : tag.toLowerCase(Locale.ROOT);
    }
}
