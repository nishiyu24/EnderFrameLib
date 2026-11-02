package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 要素ツリー（DOM と 1:1 の {@link LayoutBox}）からボックスツリーを組み立てる（軸 6）。
 *
 * レイアウトの入力は DOM ではなくこのツリー。DOM に無い箱をここで作る:
 * <ul>
 *   <li>{@code display: contents} の要素は箱を作らず、子が親の子に繰り上がる</li>
 *   <li>ブロックコンテナの中でブロックとインラインが混ざると、インラインの並びを匿名ブロックで包む
 *       （CSS 2.2 §9.2.1.1）</li>
 *   <li>表の構造が欠けていると匿名の table / table-row / table-cell を補う（CSS 2.2 §17.2.1）</li>
 *   <li>{@code list-item} に {@code ::marker} を作り、CSS カウンタ（{@code counter-reset} /
 *       {@code counter-increment} / {@code counter-set} と暗黙の {@code list-item}）を数える</li>
 *   <li>{@code ::before} / {@code ::after} の {@code content} を、カウンタを含めてここで評価する</li>
 * </ul>
 *
 * 要素ツリー（{@link LayoutBox#elementChildren}）は変えず、{@link LayoutBox#children} を作り直す。
 * 再スタイルは要素ツリーを辿るので、箱の組み替えはスタイルの継承に影響しない。
 *
 * LIMITATION(制約): インラインボックスの中にブロックがあるとき、インラインを分割しない
 * （ブロックはインラインの中に置かれたまま組まれる）。flex / grid コンテナの中のテキストは
 * 匿名の flex / grid アイテムで包まず、テキストの箱がそのままアイテムになる。
 * README の "Not supported" に対応。
 *
 * <p>リスト（R9）: {@code <ol reversed>} は項目数（{@code start} があればその値）から 1 ずつ減らして数える。
 * {@code list-style-image: url(...)} は、マーカーの文字の代わりに画像の置換要素を置く。
 * {@code @counter-style} の作者定義は {@link CounterStyles} が解決する（知らない名前は {@code decimal}）。
 */
public final class BoxTreeBuilder {

    private final Counters counters = new Counters();
    private final CounterStyles counterStyles;
    /** 匿名ボックスの id の通し番号（持ち主ごと）。再レイアウトしても同じ id になるよう決定的に振る。 */
    private final Map<LayoutBox, Integer> anonymousCounts = new IdentityHashMap<>();

    /**
     * 前回の組み立てで作った匿名ボックスとマーカー（id ごと）。同じ id・同じ種類の箱を作るときは新しく作らずに使い回す。
     * 箱が同じなら親の子の並びも前回と同じになり、差分レイアウトが前回の measure の結果を使い回せる。
     * 同じ id が 2 つ以上あった（DOM の id の重複）ものは取り違えないよう使い回さない。
     */
    private final Map<String, LayoutBox> reusableGenerated = new java.util.HashMap<>();
    private final java.util.Set<String> ambiguousGeneratedIds = new java.util.HashSet<>();

    private BoxTreeBuilder(CounterStyles counterStyles) {
        this.counterStyles = counterStyles != null ? counterStyles : CounterStyles.builtins();
    }

    /** 定義済みスタイルだけで {@code root} 以下のボックスツリーを作り直す。 */
    public static void build(LayoutBox root) {
        build(root, CounterStyles.builtins());
    }

    /** {@code root} 以下のボックスツリーを要素ツリーから作り直す。{@code counterStyles} は文書の {@code @counter-style}。 */
    public static void build(LayoutBox root, CounterStyles counterStyles) {
        BoxTreeBuilder builder = new BoxTreeBuilder(counterStyles);
        builder.collectReusableGenerated(root);
        builder.counters.apply(root);
        builder.buildBox(root);
        root.linkParents();
    }

    /** 前回の箱の木（{@link LayoutBox#children}）にある匿名ボックスとマーカーを集める。新しく作った木なら何も無い。 */
    private void collectReusableGenerated(LayoutBox box) {
        for (LayoutBox child : box.children) {
            if (child.isAnonymous) {
                String id = child.node.getId();
                if (reusableGenerated.putIfAbsent(id, child) != null) ambiguousGeneratedIds.add(id);
            }
            collectReusableGenerated(child);
        }
    }

    /** 同じ id の前回の箱。無い・取り違えのおそれがあれば null。一度渡した箱は二度渡さない。 */
    private LayoutBox takeReusableGenerated(String id) {
        if (ambiguousGeneratedIds.contains(id)) return null;
        return reusableGenerated.remove(id);
    }

    private void buildBox(LayoutBox box) {
        List<LayoutBox> items = new ArrayList<>();
        LayoutBox marker = box.display.listItem() ? createMarker(box, counters) : null;
        if (marker != null) items.add(marker);
        collectItems(box, items);
        LayoutBox legend = takeRenderedLegend(box, items);
        box.children.clear();
        if (legend != null) box.addChild(legend);
        for (LayoutBox child : fixup(box, items)) box.addChild(child);
    }

    /**
     * fieldset の描画される legend（HTML §15.3.13）を子の並びから外して返す。最初の legend 要素の子で、
     * float でも絶対配置でもないもの。通常フローに入らないので、インラインの並びを包む判断にも加わらない。
     */
    private static LayoutBox takeRenderedLegend(LayoutBox box, List<LayoutBox> items) {
        for (LayoutBox item : items) item.isRenderedLegend = false;
        if (!"fieldset".equals(box.node.tag)) return null;
        LayoutBox found = null;
        for (LayoutBox item : items) {
            if ("legend".equals(item.node.tag) && !item.node.isPseudoNode) {
                found = item;
                break;
            }
        }
        if (found == null || found.isAbsolute || found.display.isNone()
                || found.style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) != CssKeywords.FloatSide.NONE) {
            return null;
        }
        found.isRenderedLegend = true;
        items.remove(found);
        return found;
    }

    /**
     * 箱の子になる箱を文書順に集める。{@code display: contents} の子はその子を代わりに入れる。
     * カウンタの適用もこの順で行う（CSS のカウンタは要素ツリーの文書順で数える）。
     */
    private void collectItems(LayoutBox element, List<LayoutBox> out) {
        int mark = counters.mark();
        for (LayoutBox child : element.elementChildren) {
            counters.apply(child);
            if (child.node.isPseudoNode) evaluateGeneratedContent(child, counters);
            if (child.display.isContents()) {
                collectItems(child, out);
            } else {
                buildBox(child);
                out.add(child);
            }
        }
        // counter-reset の有効範囲は要素自身・子孫・後続の兄弟とその子孫。親の子を数え終えたら閉じる
        counters.release(mark);
    }

    // ==========================================
    // 匿名ボックス
    // ==========================================

    private List<LayoutBox> fixup(LayoutBox parent, List<LayoutBox> items) {
        List<LayoutBox> result = fixupTable(parent, items);
        if (parent.display.isBlockContainer()) result = wrapInlineRuns(parent, result);
        return result;
    }

    /** 表の構造の補完（CSS 2.2 §17.2.1 の「欠けた子」と「欠けた親」）。 */
    private List<LayoutBox> fixupTable(LayoutBox parent, List<LayoutBox> items) {
        Inner inner = parent.display.inner();
        if (inner == Inner.TABLE) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && !isProperTableChild(c), "table-row");
        }
        if (inner == Inner.TABLE_ROW_GROUP) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && c.display.inner() != Inner.TABLE_ROW, "table-row");
        }
        if (inner == Inner.TABLE_ROW) {
            return wrapRuns(parent, dropWhitespace(items), c -> !c.display.isNone() && c.display.inner() != Inner.TABLE_CELL, "table-cell");
        }
        if (items.stream().noneMatch(c -> c.display.isTableInternal())) return items;

        // 行の外にあるセルの並びは匿名の行で、表の外にある行・行グループの並びは匿名の表で包む
        List<LayoutBox> withRows = wrapRuns(parent, items, c -> c.display.inner() == Inner.TABLE_CELL, "table-row");
        String tableDisplay = parent.display.isInlineLevel() ? "inline-table" : "table";
        return wrapRuns(parent, withRows, this::isProperTableChild, tableDisplay);
    }

    private boolean isProperTableChild(LayoutBox box) {
        Inner inner = box.display.inner();
        return inner == Inner.TABLE_ROW_GROUP || inner == Inner.TABLE_ROW
                || inner == Inner.TABLE_CAPTION || inner == Inner.TABLE_COLUMN;
    }

    /**
     * 条件を満たす子の連続を 1 つの匿名ボックスで包む。並びの間にある空白だけのテキストは
     * 表の箱どうしの間では存在しないものとして扱う（CSS 2.2 §17.2.1 の 1.3）。
     */
    private List<LayoutBox> wrapRuns(LayoutBox parent, List<LayoutBox> items,
                                            Predicate<LayoutBox> belongsToRun, String anonymousDisplay) {
        List<LayoutBox> result = new ArrayList<>();
        List<LayoutBox> run = new ArrayList<>();
        List<LayoutBox> pendingWhitespace = new ArrayList<>();
        for (LayoutBox item : items) {
            if (belongsToRun.test(item)) {
                pendingWhitespace.clear();
                run.add(item);
            } else if (!run.isEmpty() && isCollapsibleWhitespace(item)) {
                pendingWhitespace.add(item);
            } else {
                flushRun(parent, run, anonymousDisplay, result);
                result.addAll(pendingWhitespace);
                pendingWhitespace.clear();
                result.add(item);
            }
        }
        flushRun(parent, run, anonymousDisplay, result);
        return result;
    }

    private void flushRun(LayoutBox parent, List<LayoutBox> run, String anonymousDisplay, List<LayoutBox> out) {
        if (run.isEmpty()) return;
        LayoutBox anon = createAnonymous(parent, anonymousDisplay);
        for (LayoutBox child : fixup(anon, run)) anon.addChild(child);
        out.add(anon);
        run.clear();
    }

    private static List<LayoutBox> dropWhitespace(List<LayoutBox> items) {
        List<LayoutBox> kept = new ArrayList<>(items.size());
        for (LayoutBox item : items) {
            if (!isCollapsibleWhitespace(item)) kept.add(item);
        }
        return kept;
    }

    /**
     * ブロックコンテナがブロックレベルの子を持つなら、インラインレベルの子の並びを匿名ブロックで包む。
     *
     * 何が壊れていたか: 以前はインラインの並びとブロックを同じ親の中で直接組み、インラインの行の後の
     * ブロックが「親の先頭の子」として親の上端に置かれていた（インラインと重なっていた）。
     */
    private List<LayoutBox> wrapInlineRuns(LayoutBox parent, List<LayoutBox> items) {
        if (items.stream().noneMatch(BoxTreeBuilder::isInFlowBlockLevel)) return items;

        List<LayoutBox> result = new ArrayList<>();
        List<LayoutBox> run = new ArrayList<>();
        for (LayoutBox item : items) {
            if (isInFlowBlockLevel(item)) {
                flushInlineRun(parent, run, result);
                result.add(item);
            } else if (isOutOfFlow(item) && run.isEmpty()) {
                // 並びの外にある位置指定・非表示・外側マーカーは包まない（並びの中なら一緒に包む）
                result.add(item);
            } else {
                run.add(item);
            }
        }
        flushInlineRun(parent, run, result);
        return result;
    }

    private void flushInlineRun(LayoutBox parent, List<LayoutBox> run, List<LayoutBox> out) {
        if (run.isEmpty()) return;
        boolean hasContent = run.stream().anyMatch(c -> !isOutOfFlow(c) && !isCollapsibleWhitespace(c));
        if (hasContent) {
            LayoutBox anon = createAnonymous(parent, "block");
            for (LayoutBox child : run) anon.addChild(child);
            out.add(anon);
        } else {
            // 畳まれる空白だけの並びは行ボックスを作らない（CSS 2.2 §9.2.1.1）
            for (LayoutBox child : run) {
                if (!isCollapsibleWhitespace(child)) out.add(child);
            }
        }
        run.clear();
    }

    private static boolean isInFlowBlockLevel(LayoutBox box) {
        return !isOutOfFlow(box) && !box.display.isInlineLevel();
    }

    /**
     * 匿名ブロックを作るかどうかの判断に加わらない箱。
     * float は通常フローの外なので、ブロックの並びに数えない（行の中の float は行と一緒に包む）。
     */
    private static boolean isOutOfFlow(LayoutBox box) {
        return box.display.isNone() || box.isAbsolute || box.isOutsideMarker
                || !"none".equals(box.style.getOrDefault(CssProperty.FLOAT, "none"));
    }

    private static boolean isCollapsibleWhitespace(LayoutBox box) {
        if (box.node.nodeType != NodeType.TEXT) return false;
        if (!WhiteSpace.of(box.style.get(CssProperty.WHITE_SPACE)).collapses()) return false;
        return box.node.text == null || box.node.text.isBlank();
    }

    /** 匿名ボックスは親の継承プロパティだけを受け継ぎ、それ以外は初期値になる（CSS 2.2 §9.2.1.1）。 */
    private LayoutBox createAnonymous(LayoutBox parent, String display) {
        int index = anonymousCounts.merge(parent, 1, Integer::sum);
        String id = parent.node.getId() + "_anon" + index;
        ComputedStyle style = generatedStyle(parent, Map.of(CssProperty.DISPLAY, display));
        LayoutBox reused = takeReusableGenerated(id);
        // 匿名の表・行・セルは表のエンジンが直接書き込むので作り直す（LayoutBox.reusableBoxes と同じ）
        if (reused != null && !reused.node.isPseudoNode && !display.contains("table")
                && LayoutBox.isReusableShape(reused)) {
            reused.node.parent = parent.node;
            reused.children.clear();
            reused.updateStyle(style);
            return reused;
        }
        HtmlNode node = new HtmlNode("div");
        node.parent = parent.node;
        node.setId(id);
        return LayoutBox.anonymous(node, style);
    }

    /**
     * 持ち主の算出スタイルが変わったあと、その中の匿名ボックスとマーカーのスタイルを作り直す。
     *
     * 何が壊れていたか（W3 で入れた落とし穴）: 色など描画だけの変化は再レイアウトを通らず、
     * ボックスツリーも組み直されないので、{@code li:hover { color }} でマーカーの色が変わらなかった。
     * 要素の箱は再スタイルが要素ツリーを辿って直すが、DOM に無い箱はここで直す。
     */
    public static void restyleGeneratedBoxes(LayoutBox owner) {
        for (LayoutBox child : owner.children) {
            if (!child.isAnonymous) continue;
            Map<String, String> overrides = child.node.isPseudoNode
                    ? MARKER_OVERRIDES
                    : Map.of(CssProperty.DISPLAY, child.style.getOrDefault(CssProperty.DISPLAY, "block"));
            child.updateStyle(child.node.isPseudoNode ? markerStyleOf(owner) : generatedStyle(owner, overrides));
            if (child.node.isPseudoNode) {
                for (LayoutBox text : child.children) text.updateStyle(child.style);
            } else {
                restyleGeneratedBoxes(child);
            }
        }
    }

    /**
     * マーカーの算出スタイル。{@code ::marker} の規則が掛かっていればそれ（親はリスト項目）、
     * 無ければリスト項目の継承値。どちらも UA の ::marker と同じ上書き（空白を畳まない・text-transform なし）を掛ける。
     */
    private static ComputedStyle markerStyleOf(LayoutBox listItem) {
        ComputedStyle declared = listItem.markerStyle;
        if (declared == null) return generatedStyle(listItem, MARKER_OVERRIDES);
        Map<String, String> props = new LinkedHashMap<>(declared.asMap());
        props.remove(CssProperty.CONTENT);
        props.putAll(MARKER_OVERRIDES);
        return new ComputedStyle(props, declared.fontSizePx(), declared.rootFontSizePx());
    }

    private static ComputedStyle generatedStyle(LayoutBox owner, Map<String, String> overrides) {
        Map<String, String> props = inheritedProperties(owner.style);
        props.putAll(overrides);
        return new ComputedStyle(props, owner.style.fontSizePx(), owner.style.rootFontSizePx());
    }

    private static Map<String, String> inheritedProperties(ComputedStyle style) {
        Map<String, String> props = new LinkedHashMap<>();
        style.asMap().forEach((key, value) -> {
            if (key.startsWith("--") || PropertyRegistry.isInherited(key)) props.put(key, value);
        });
        return props;
    }

    // ==========================================
    // ::marker と生成内容
    // ==========================================

    /**
     * リスト項目の {@code ::marker}。{@code list-style-position: outside} なら通常フローの外に置かれ、
     * {@link BlockLayoutEngine} が最初の行の左に並べる。{@code inside} なら先頭のインライン内容になる。
     *
     * {@code ::marker} の規則（色・フォント・{@code content} など）は {@link LayoutBox#markerStyle} から読む。
     * {@code content} があればリストの種類より優先する（{@code li::marker { content: "→ " }}）。
     *
     * {@code list-style-image: url(...)} は、{@code content} が無いときに文字の代わりに画像を置く。
     */
    private LayoutBox createMarker(LayoutBox listItem, Counters counters) {
        String text = markerContent(listItem, counters);
        String imageUrl = text == null ? listStyleImageUrl(listItem.style.get(CssProperty.LIST_STYLE_IMAGE)) : null;
        if (imageUrl != null) return createImageMarker(listItem, imageUrl);
        if (text == null) {
            text = counterStyles.marker(listItem.style.getOrDefault(CssProperty.LIST_STYLE_TYPE, "disc"),
                    counters.value(LIST_ITEM));
        }
        if (text == null || text.isEmpty()) return null;

        ComputedStyle style = markerStyleOf(listItem);
        boolean outside = listItem.style.keywordOf(CssProperty.LIST_STYLE_POSITION, CssKeywords.ListStylePosition.class)
                != CssKeywords.ListStylePosition.INSIDE;

        // 前回と同じ文字のマーカーは箱ごと使い回す（番号が変わったら作り直す）
        LayoutBox reused = takeReusableGenerated(listItem.node.getId() + "_marker");
        if (reused != null && reused.node.isPseudoNode && reused.children.size() == 1
                && text.equals(reused.children.getFirst().node.text)) {
            reused.node.parent = listItem.node;
            reused.updateStyle(style);
            reused.isOutsideMarker = outside;
            reused.children.getFirst().updateStyle(style);
            return reused;
        }

        HtmlNode node = new HtmlNode("span");
        node.isPseudoNode = true;
        node.parent = listItem.node;
        node.setId(listItem.node.getId() + "_marker");
        HtmlNode textNode = new HtmlText(text);
        textNode.parent = node;
        textNode.setId(node.getId() + "_txt");

        // DOM に無い箱なので匿名ボックスとして持つ（描画は中のテキストだけ）
        LayoutBox marker = LayoutBox.anonymous(node, style);
        marker.isOutsideMarker = outside;
        marker.addChild(new LayoutBox(textNode, style));
        return marker;
    }

    /** {@code list-style-image} の {@code url(...)} の中身。{@code none} や url 以外は null。 */
    static String listStyleImageUrl(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() < 6 || !v.regionMatches(true, 0, "url(", 0, 4) || !v.endsWith(")")) return null;
        String inner = v.substring(4, v.length() - 1).trim();
        if (inner.length() >= 2 && (inner.charAt(0) == '"' || inner.charAt(0) == '\'')
                && inner.charAt(inner.length() - 1) == inner.charAt(0)) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return inner.isEmpty() ? null : inner;
    }

    /** 画像のマーカー: 画像の置換要素と、続く空白 1 文字。画像が同じなら前回の箱を使い回す。 */
    private LayoutBox createImageMarker(LayoutBox listItem, String url) {
        ComputedStyle style = markerStyleOf(listItem);
        boolean outside = listItem.style.keywordOf(CssProperty.LIST_STYLE_POSITION, CssKeywords.ListStylePosition.class)
                != CssKeywords.ListStylePosition.INSIDE;

        LayoutBox reused = takeReusableGenerated(listItem.node.getId() + "_marker");
        if (reused != null && reused.node.isPseudoNode && reused.children.size() == 2
                && reused.children.getFirst().node.nodeType == NodeType.ELEMENT
                && url.equals(reused.children.getFirst().node.attrs.get("src"))) {
            reused.node.parent = listItem.node;
            reused.updateStyle(style);
            reused.isOutsideMarker = outside;
            for (LayoutBox child : reused.children) child.updateStyle(style);
            return reused;
        }

        HtmlNode node = new HtmlNode("span");
        node.isPseudoNode = true;
        node.parent = listItem.node;
        node.setId(listItem.node.getId() + "_marker");
        HtmlNode image = new HtmlElement("img");
        image.isPseudoNode = true;
        // 親に繋ぐ前に書く（DOM の観測者には見えない箱なので、属性の変更を通知させない）
        image.setAttribute("src", url);
        image.parent = node;
        image.setId(node.getId() + "_img");
        HtmlNode gap = new HtmlText(" ");
        gap.parent = node;
        gap.setId(node.getId() + "_txt");

        LayoutBox marker = LayoutBox.anonymous(node, style);
        marker.isOutsideMarker = outside;
        marker.addChild(new LayoutBox(image, style));
        marker.addChild(new LayoutBox(gap, style));
        return marker;
    }

    /** {@code ::marker} の {@code content}。無い・{@code normal} は null（リストの種類で決める）、{@code none} は空文字列。 */
    private String markerContent(LayoutBox listItem, Counters counters) {
        if (listItem.markerStyle == null) return null;
        String content = listItem.markerStyle.get(CssProperty.CONTENT);
        if (content == null || content.isBlank() || content.trim().equalsIgnoreCase("normal")) return null;
        if (content.trim().equalsIgnoreCase("none")) return "";
        return evaluateContent(content, listItem.node, counters);
    }

    /** UA の ::marker と同じ（空白を畳まず、text-transform を掛けない）。 */
    private static final Map<String, String> MARKER_OVERRIDES = Map.of(
            CssProperty.DISPLAY, "inline-block",
            CssProperty.WHITE_SPACE, "pre",
            CssProperty.TEXT_TRANSFORM, "none");

    /**
     * {@code ::before} / {@code ::after} の {@code content} を評価して、擬似要素のテキストにする。
     *
     * 何が壊れていたか: 以前はパーサが {@code content} の前後の引用符を外すだけだったので、
     * {@code "(" attr(title) ")"} のような連結が文字列のまま出ていた。カウンタも読めなかった。
     */
    private void evaluateGeneratedContent(LayoutBox pseudo, Counters counters) {
        String content = pseudo.style.get(CssProperty.CONTENT);
        if (content == null || pseudo.elementChildren.isEmpty()) return;
        LayoutBox textBox = pseudo.elementChildren.getFirst();
        if (textBox.node.nodeType != NodeType.TEXT) return;
        textBox.node.text = evaluateContent(content, pseudo.node.parent, counters);
        textBox.updateStyle(textBox.style);
    }

    /** {@code content} の値（文字列・{@code attr()}・{@code counter()}・{@code counters()} の並び）を評価する。 */
    String evaluateContent(String content, HtmlNode owner, Counters counters) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                StringBuilder s = new StringBuilder();
                while (end < n && content.charAt(end) != c) {
                    char ch = content.charAt(end);
                    if (ch == '\\' && end + 1 < n) {
                        end++;
                        ch = content.charAt(end);
                    }
                    s.append(ch);
                    end++;
                }
                out.append(s);
                i = Math.min(n, end + 1);
            } else {
                int open = content.indexOf('(', i);
                int space = indexOfWhitespace(content, i);
                if (open >= 0 && (space < 0 || open < space)) {
                    int close = matchingParen(content, open);
                    String name = content.substring(i, open).trim().toLowerCase(Locale.ROOT);
                    String args = content.substring(open + 1, close).trim();
                    out.append(evaluateFunction(name, args, owner, counters));
                    i = close + 1;
                } else {
                    // open-quote などのキーワード。引用符の入れ子は持たないので何も出さない
                    i = space < 0 ? n : space;
                }
            }
        }
        return out.toString();
    }

    private String evaluateFunction(String name, String args, HtmlNode owner, Counters counters) {
        List<String> parts = splitArgs(args);
        return switch (name) {
            case "attr" -> owner != null && !parts.isEmpty() ? owner.attrs.getOrDefault(parts.getFirst(), "") : "";
            case "counter" -> parts.isEmpty() ? ""
                    : counterStyles.representation(counters.value(parts.get(0)),
                            parts.size() > 1 ? parts.get(1) : "decimal");
            case "counters" -> {
                if (parts.size() < 2) yield "";
                String separator = unquote(parts.get(1));
                String style = parts.size() > 2 ? parts.get(2) : "decimal";
                List<String> formatted = new ArrayList<>();
                for (int v : counters.values(parts.get(0))) formatted.add(counterStyles.representation(v, style));
                if (formatted.isEmpty()) formatted.add(counterStyles.representation(0, style));
                yield String.join(separator, formatted);
            }
            default -> "";
        };
    }

    private static List<String> splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString().trim());
        return parts;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("'"))) return s.substring(1, s.length() - 1);
        return s;
    }

    private static int indexOfWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    // ==========================================
    // CSS カウンタ
    // ==========================================

    /** リスト項目が暗黙に数えるカウンタ（CSS Lists 3）。 */
    static final String LIST_ITEM = "list-item";

    /**
     * 文書順に辿りながら持つカウンタの実体の積み上げ。
     * 同名の実体は後から作られたもの（内側）が見える。{@code counters()} は外側から全部を読む。
     */
    static final class Counters {

        private final List<String> names = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        int mark() {
            return names.size();
        }

        /** 逆順に数える {@code list-item}（{@code <ol reversed>}）の実体の位置。 */
        private final java.util.Set<Integer> reversedInstances = new java.util.HashSet<>();

        void release(int mark) {
            while (names.size() > mark) {
                names.removeLast();
                values.removeLast();
                reversedInstances.remove(names.size());
            }
        }

        /** {@code <ol>} の項目（{@code li} の子）の数。 */
        private static int listItemChildren(HtmlNode list) {
            int count = 0;
            for (HtmlNode child : list.children) {
                if (child.nodeType == NodeType.ELEMENT && "li".equalsIgnoreCase(child.tag)) count++;
            }
            return count;
        }

        int value(String name) {
            int i = innermost(name);
            return i < 0 ? 0 : values.get(i);
        }

        List<Integer> values(String name) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals(name)) result.add(values.get(i));
            }
            return result;
        }

        /**
         * 要素の {@code counter-reset} → {@code counter-increment} → {@code counter-set} を適用する
         * （CSS Lists 3 の順。{@code <li value>} が増分の後に効くのはこの順のため）。
         */
        void apply(LayoutBox box) {
            if (box.node.nodeType == NodeType.TEXT) return;
            ComputedStyle style = box.style;
            HtmlNode node = box.node;

            Map<String, Integer> resets = parseCounterList(style.get(CssProperty.COUNTER_RESET), 0);
            // <ol start> は list-item の初期値を変える表示上のヒント（HTML の Rendering 節）
            if (resets.containsKey(LIST_ITEM) && "ol".equalsIgnoreCase(node.tag) && node.attrs.containsKey("start")) {
                Integer start = parseInt(node.attrs.get("start"));
                if (start != null) resets.put(LIST_ITEM, start - 1);
            }
            // <ol reversed> は項目数（start があればその値）から 1 ずつ減らす。最初の項目の減算の前に 1 足しておく
            boolean reversed = resets.containsKey(LIST_ITEM) && "ol".equalsIgnoreCase(node.tag) && node.attrs.containsKey("reversed");
            if (reversed) {
                Integer start = node.attrs.containsKey("start") ? parseInt(node.attrs.get("start")) : null;
                resets.put(LIST_ITEM, (start != null ? start : listItemChildren(node)) + 1);
            }
            for (var reset : resets.entrySet()) {
                reset(reset.getKey(), reset.getValue());
                if (reversed && reset.getKey().equals(LIST_ITEM)) reversedInstances.add(names.size() - 1);
            }

            Map<String, Integer> increments = parseCounterList(style.get(CssProperty.COUNTER_INCREMENT), 1);
            if (box.display.listItem() && !increments.containsKey(LIST_ITEM)) {
                int i = innermost(LIST_ITEM);
                increments.put(LIST_ITEM, i >= 0 && reversedInstances.contains(i) ? -1 : 1);
            }
            increments.forEach(this::increment);

            Map<String, Integer> sets = parseCounterList(style.get(CssProperty.COUNTER_SET), 0);
            if (box.display.listItem() && !sets.containsKey(LIST_ITEM) && node.attrs.containsKey("value")) {
                Integer value = parseInt(node.attrs.get("value"));
                if (value != null) sets.put(LIST_ITEM, value);
            }
            sets.forEach(this::set);
        }

        private void reset(String name, int value) {
            names.add(name);
            values.add(value);
        }

        private void increment(String name, int by) {
            int i = innermost(name);
            if (i < 0) {
                reset(name, by);
            } else {
                values.set(i, values.get(i) + by);
            }
        }

        private void set(String name, int value) {
            int i = innermost(name);
            if (i < 0) reset(name, value);
            else values.set(i, value);
        }

        private int innermost(String name) {
            for (int i = names.size() - 1; i >= 0; i--) {
                if (names.get(i).equals(name)) return i;
            }
            return -1;
        }

        /** {@code "a 2 b"} → {@code {a=2, b=既定値}}。{@code none} と空は空の表。 */
        private static Map<String, Integer> parseCounterList(String value, int defaultValue) {
            Map<String, Integer> result = new LinkedHashMap<>();
            if (value == null) return result;
            String v = value.trim();
            if (v.isEmpty() || v.equalsIgnoreCase("none")) return result;
            String pending = null;
            for (String token : v.split("\\s+")) {
                Integer number = parseInt(token);
                if (number != null && pending != null) {
                    result.put(pending, number);
                    pending = null;
                } else if (number == null) {
                    if (pending != null) result.put(pending, defaultValue);
                    pending = token;
                }
            }
            if (pending != null) result.put(pending, defaultValue);
            return result;
        }

        private static Integer parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException | NullPointerException e) {
                return null;
            }
        }
    }
}