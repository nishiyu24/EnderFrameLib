package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 描画が読む DOM の値の写し（M3b-1・決定 F。計画書 §3.4「描画は DOM を読まない」）。不変の値で、{@link BoxNode#values} に載る。
 *
 * <p>描画（{@code core.paint}）は属性（{@code title}・{@code on*}・入力の {@code value}・{@code checked}・画像の {@code src} ほか）・
 * textarea の中身・状態の擬似クラスの規則が掛かるかを読む。生きた DOM を読むと、スタイルも寸法も変えない属性だけの変化で箱が前回と
 * 同じ参照のまま（{@code ==}）になり、描画の記憶が古い値を返す（M2c までは台帳に載った節の祖先を使い回さないことで避けていた）。
 * そこで入力側（{@link BoxTreeCompiler}）が箱を作るときに写しを作り、値が前回と同じなら前回の参照を使う。値が変われば最新の箱が
 * 変わる（レイアウトの入力の照合には入れないので、レイアウトの入力の箱は前回のまま）。</p>
 *
 * <p>写さない属性: {@code class}・{@code id}・{@code style}（スタイルと箱の鍵の側で読み、描画は読まない。多くの要素が持つので、
 * 写すと要素ごとに表を作ることになる）。</p>
 *
 * @param attrs       写した属性（挿入順・変更不可。無ければ空）
 * @param text        textarea の中身（{@code value} 属性が無いときの既定の値。他の要素は null）
 * @param interactive 状態の擬似クラス（{@code :hover} など）の規則が掛かり得る要素か（項目に印を付け、背景が透明でも当たり判定の項目を出す）
 */
public record DomValues(Map<String, String> attrs, String text, boolean interactive) {
    /** 写すものが無い箱（文字の節・疑似要素・匿名の箱・規則も属性も無い要素）。 */
    public static final DomValues NONE = new DomValues(Map.of(), null, false);
    /** 写す属性は無いが、状態の擬似クラスの規則が掛かり得る要素（{@code li:hover} の一覧の行など。要素ごとに作らず共有する）。 */
    public static final DomValues INTERACTIVE_ONLY = new DomValues(Map.of(), null, true);

    public DomValues {
        Objects.requireNonNull(attrs, "attrs");
    }

    /** 属性 {@code name} の値（無ければ null）。 */
    public String attr(String name) {
        return attrs.get(name);
    }

    /** 属性 {@code name} の値（無ければ {@code def}）。 */
    public String attr(String name, String def) {
        return attrs.getOrDefault(name, def);
    }

    /** 属性 {@code name} を持つか。 */
    public boolean has(String name) {
        return attrs.containsKey(name);
    }

    /** {@code on*} のイベント属性を 1 つでも持つか。 */
    public boolean hasEventHandlers() {
        for (String k : attrs.keySet()) if (k != null && k.regionMatches(true, 0, "on", 0, 2)) return true;
        return false;
    }

    /**
     * 要素 {@code n} の写し。{@code resolve} はこのコンパイルで要素のスタイルを解き直したか（状態の規則が掛かるかは解き直したときだけ
     * 引き直し、それ以外は前回の値）。値が {@code prev} と同じなら {@code prev} を返す（確保しない）。
     */
    static DomValues of(HtmlNode n, StyleSheet sheet, boolean resolve, DomValues prev) {
        if (n.nodeType != NodeType.ELEMENT) return NONE;
        boolean interactive = resolve || prev == null ? sheet != null && StyleResolver.hasInteractiveRules(n, sheet) : prev.interactive;
        String text = ElementTraits.isTextarea(n.tag) ? n.getTextContent() : null;
        Map<String, String> src = n.attrs;
        if (prev != null && prev.interactive == interactive && Objects.equals(prev.text, text) && sameAttrs(prev.attrs, src)) return prev;
        int kept = 0;
        for (String k : src.keySet()) if (!skipped(k)) kept++;
        if (kept == 0 && text == null) return interactive ? INTERACTIVE_ONLY : NONE;
        Map<String, String> copy = Map.of();
        if (kept > 0) {
            Map<String, String> m = new LinkedHashMap<>(kept * 2);
            for (Map.Entry<String, String> e : src.entrySet()) if (!skipped(e.getKey())) m.put(e.getKey(), e.getValue());
            copy = Collections.unmodifiableMap(m);
        }
        return new DomValues(copy, text, interactive);
    }

    /** 写した表 {@code copy} が DOM の表 {@code live}（写さない属性を除く）と同じか（確保しない）。 */
    private static boolean sameAttrs(Map<String, String> copy, Map<String, String> live) {
        int n = 0;
        for (Map.Entry<String, String> e : live.entrySet()) {
            if (skipped(e.getKey())) continue;
            n++;
            String v = copy.get(e.getKey());
            if (v == null ? e.getValue() != null || !copy.containsKey(e.getKey()) : !v.equals(e.getValue())) return false;
        }
        return n == copy.size();
    }

    private static boolean skipped(String k) {
        return "class".equals(k) || "id".equals(k) || "style".equals(k);
    }
}
