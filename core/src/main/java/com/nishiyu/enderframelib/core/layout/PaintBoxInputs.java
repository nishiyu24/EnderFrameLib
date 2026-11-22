package com.nishiyu.enderframelib.core.layout;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 描画の入力読み取りの統合宣言表（C3 ④ / C7 ③ の完結）。
 *
 * <p>{@link PaintStamps#SIGNATURE_FIELDS}・{@link PaintStamps#COVERED_ELSEWHERE} と、
 * {@code FragmentPaint.Memo} の記憶キー欄を一つの宣言表として管理する。各項目を変更したときはここを更新し、
 * {@code PaintReadSetTest} が {@code LayoutBox}・{@link PaintStamps}・{@code FragmentPaint.Memo} の三者の
 * 全単射と実測読み取り集合を機械的に検証する。</p>
 */
public final class PaintBoxInputs {
    private PaintBoxInputs() {}

    /** 描画の記憶がこの入力の変化をどの経路で検知するか。 */
    public enum GuardKind {
        /** {@link PaintStamps#signature(LayoutBox)} の署名のみで検知する {@code LayoutBox} 出力欄。 */
        SIGNATURE,
        /** 署名に加え {@code FragmentPaint.Memo} の同脈判定キーにも持つ {@code LayoutBox} 出力欄。 */
        SIGNATURE_AND_MEMO_KEY,
        /** スタイルの更新印（と該当時はキー欄）で検知する {@code LayoutBox} の由来欄。 */
        STYLE_STAMP,
        /** 箱の木構築・構造印で検知する {@code LayoutBox} の由来欄。 */
        TREE_BUILD,
        /** 属性・断片・描画文脈・親子関係など、{@code LayoutBox} の署名外にある {@code FragmentPaint.Memo} のキー欄。 */
        MEMO_KEY
    }

    /** 描画が読む 1 つの入力欄と、その監視経路・対応する {@code FragmentPaint.Memo} のキー欄名（無ければ {@code null}）。 */
    public record Entry(String name, GuardKind guard, String memoKeyField, String note) {}

    private static Entry sig(String name) {
        return new Entry(name, GuardKind.SIGNATURE, null, "PaintStamps.signature");
    }

    private static Entry sigKey(String name, String memoKeyField) {
        return new Entry(name, GuardKind.SIGNATURE_AND_MEMO_KEY, memoKeyField, "PaintStamps.signature + Memo." + memoKeyField);
    }

    private static Entry styleStamp(String name, String note) {
        return new Entry(name, GuardKind.STYLE_STAMP, null, note);
    }

    private static Entry treeBuild(String name, String note) {
        return new Entry(name, GuardKind.TREE_BUILD, null, note);
    }

    private static Entry key(String name, String memoKeyField, String note) {
        return new Entry(name, GuardKind.MEMO_KEY, memoKeyField, note);
    }

    public static final List<Entry> ALL = List.of(
            // LayoutBox: signature + FragmentPaint.Memo key (6)
            sigKey("x", "x"), sigKey("y", "y"), sigKey("w", "w"), sigKey("h", "h"),
            sigKey("scrollWidth", "scrollW"), sigKey("scrollHeight", "scrollH"),
            // LayoutBox: signature only (42)
            sig("mT"), sig("mR"), sig("mB"), sig("mL"),
            sig("pT"), sig("pR"), sig("pB"), sig("pL"),
            sig("bT"), sig("bR"), sig("bB"), sig("bL"),
            sig("rowGap"), sig("columnGap"), sig("textBaseline"), sig("lineHeightPx"),
            sig("inlineContentBottom"), sig("descendantOverflowRight"), sig("descendantOverflowBottom"),
            sig("contentOffsetY"), sig("legendSpill"), sig("contentBlockExtent"),
            sig("ownMarginTop"), sig("ownMarginBottom"),
            sig("overrideWidth"), sig("overrideHeight"), sig("rememberedInnerW"), sig("rememberedInnerH"),
            sig("collapsedBorders"), sig("columnRules"), sig("firstLineBgRect"),
            sig("sizingMode"), sig("zIndex"), sig("virtualChildIndex"),
            sig("measuredClamped"), sig("contentAutoSkipped"), sig("childrenDeferred"),
            sig("hasAbsoluteChild"), sig("hasOutsideMarker"), sig("isOutsideMarker"),
            sig("isRenderedLegend"), sig("displayText"),
            // LayoutBox: covered elsewhere (8)
            styleStamp("isAbsolute", "position から導出。スタイルの更新（updateStyle）が書き、スタイルの印が進む"),
            styleStamp("isFixed", "position から導出。スタイルの印"),
            styleStamp("isRelative", "position から導出。スタイルの印"),
            styleStamp("isSticky", "position から導出。スタイルの印"),
            treeBuild("isVerticalRoot", "書字方向から導出。BoxTreeBuilder が木の構築で書く（箱を作り直すので新しい箱の記憶になる）"),
            treeBuild("isHorizontalReturnRoot", "書字方向から導出。BoxTreeBuilder が木の構築で書く"),
            new Entry("hasInteractiveRules", GuardKind.STYLE_STAMP, "interactive",
                    "スタイルシートの規則から導出。記憶の鍵 interactive（箱自身の項目）とスタイルの印"),
            treeBuild("subtreeHasContentAuto", "子の並びと content-visibility から導出。構造の印（子の増減）とスタイルの印"),
            // LayoutBox: Memo key only (2)
            key("parent", "parent", "親リンク（箱の再採用・再所属で変わる）"),
            key("placeholderStyle", "placeholder", "プレースホルダー用の解決済みスタイル"),
            // Non-field inputs tracked as FragmentPaint.Memo key fields (21)
            key("node:attrVersion", "attrVersion", "HTML 属性版（ownReusable / sameContextCheap）"),
            key("node:treeNonStyleAttrVersion", "treeAttrVer", "部分木内の非スタイル属性版（Pillar2 判定）"),
            key("box:children", "kids", "子ボックス配列のスナップショット"),
            key("frag:f0x", "f0x", "先頭インライン断片の x"),
            key("frag:f0y", "f0y", "先頭インライン断片の y"),
            key("frag:firstLineStyle", "flFrag", "先頭断片の ::first-line 実効スタイル"),
            key("frag:firstLineOwnerStyle", "flStyle", "::first-line 所有者本体のスタイル"),
            key("frag:firstLineOwnerPseudo", "flPseudo", "::first-line 所有者の ::first-line スタイル"),
            key("ctx:clip", "clip", "親から渡されるクリップ矩形"),
            key("ctx:parentId", "parentId", "親ノードのレンダー ID"),
            key("ctx:parentOriginX", "pox", "親要素ノードの原点 X（丸め後）"),
            key("ctx:parentOriginY", "poy", "親要素ノードの原点 Y（丸め後）"),
            key("ctx:moving", "moving", "祖先または自身が transform/animation で動くか"),
            key("ctx:viewportW", "vpW", "ビューポート幅"),
            key("ctx:viewportH", "vpH", "ビューポート高さ"),
            key("ctx:canvasBackgroundSource", "canvasSource", "ビューポート背景を担う root/body 源"),
            key("ctx:suppressTextShadow", "suppressShadow", "描画コンテキストの影抑制フラグ"),
            key("ctx:scrollContentOneNode", "oneNode", "1 ノードまとめスクロール節の対象か"),
            key("ctx:flexOrGridItem", "flexOrGridItem", "親が flex / grid コンテナか"),
            key("ctx:flexOrGridContainer", "flexOrGridContainer", "自身が flex / grid コンテナか"),
            key("ctx:scrollOffsets", "scrolls", "子孫スクロール領域と記憶時スクロール量")
    );

    /** {@link PaintStamps#SIGNATURE_FIELDS} に入る {@code LayoutBox} 欄名の集合。 */
    public static Set<String> signatureFields() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : ALL) {
            if (!e.name().contains(":") && (e.guard() == GuardKind.SIGNATURE || e.guard() == GuardKind.SIGNATURE_AND_MEMO_KEY)) {
                out.add(e.name());
            }
        }
        return out;
    }

    /** {@link PaintStamps#COVERED_ELSEWHERE} に入る {@code LayoutBox} 欄名と理由の対応。 */
    public static Map<String, String> coveredElsewhere() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : ALL) {
            if (!e.name().contains(":") && (e.guard() == GuardKind.STYLE_STAMP || e.guard() == GuardKind.TREE_BUILD)) {
                out.put(e.name(), e.note());
            }
        }
        return out;
    }

    /** {@code FragmentPaint.Memo} が同脈判定キーとして保持する全欄名。 */
    public static Set<String> memoKeyFields() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : ALL) {
            if (e.memoKeyField() != null) out.add(e.memoKeyField());
        }
        return out;
    }
}
