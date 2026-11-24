package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.PaintOrder;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部分構築（K5b L2。設計書 {@code core/DESIGN_INCREMENTAL_KERNEL.md} §3.6）。全再計算〔{@code RELAYOUT}〕で、変更台帳
 * 〔{@link ChangeLedger}〕に触れられていない部分木は辿らず、前回の箱をそのまま返す。<b>入口別の集合・成立条件は持たない</b>:
 * 「この節の部分木の下に、この世代の変更があるか」を台帳に聞くだけで、子の増減も文字も属性も同じ規則で扱う〔旧 {@code CHILD_PATCH}
 * の「≤ 4 親・≤ 16 件・ブロックの並びだけ」のような形の制限は無い〕。
 *
 * <p>辿った箱〔{@link #visited}〕だけが箱の木の組み立て〔{@code BoxTreeBuilder}〕・測定の印付け・配置の対象になる。次フレーム用の
 * 再利用表は前回の表を<b>そのまま使い続け</b>〔{@code prev == next}〕、辿った箱を入れ直す。</p>
 *
 * <p>飛ばさない形〔文書の事実・箱の事実から判断する。フラグを足さない〕: カウンタ・引用符を使う文書〔文書順の流れ込みがある〕・
 * コンテナクエリ・{@code content-visibility: auto}・表の中・番号つきのリスト項目〔番号が兄弟に依る〕・台帳があふれた/全体に効く変更の世代。</p>
 */
final class PartialBuild {
    private final ChangeLedger ledger;
    private final LayoutBox.ReusableTable table;
    /** 今回辿った（作り直した・使い回して辿った）節の箱。 */
    private final Map<HtmlNode, LayoutBox> visited = new IdentityHashMap<>();

    private PartialBuild(ChangeLedger ledger, LayoutBox.ReusableTable table) {
        this.ledger = ledger;
        this.table = table;
    }

    /** 部分構築ができれば新しい箱の木の根〔前回の根のまま〕、できなければ null〔何も変えていないので、呼び出し側が全体を作り直す〕。 */
    static LayoutBox tryRun(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts,
                            InteractionState st, LayoutBox prev) {
        ChangeLedger ledger = st.changeLedger();
        if (prev == null || doc == null || doc.root == null || doc.sheet == null || prev.node != doc.root) return null;
        if (!EngineContext.current().flags().incrementalLayout() || !EngineContext.current().flags().inPlaceRestyleRelayout()) return null;
        if (ledger.frozenOverflow() || ledger.frozenSize() == 0 || ledger.frozenNodeless()) return null;
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries() || prev.subtreeHasContentAuto()) return null;
        if (doc.authorCss != null && (doc.authorCss.contains("counter") || doc.authorCss.contains("quote"))) return null;
        LayoutBox.ReusableTable table = LayoutBox.acquireReusableTable(prev, doc.root);
        try {
            if (table == null || table.peek(doc.root) != prev) return null;
            st.bindStyleSheet(doc.sheet);
            PartialBuild pb = new PartialBuild(ledger, table);
            LayoutBox root = RenderTreeBuilder.buildLayoutTree(doc.root, RenderPipeline.rootViewportStyle(rootW, rootH), doc.sheet, env,
                    RenderPipeline.FORCE_NONE, ComputedStyle.DEFAULT_FONT_SIZE, RenderPipeline.StyleScope.initial(rootW, rootH), st,
                    StyleResolver.hasSiblingStateRules(doc.sheet), table, table, false, null,
                    RenderPipeline.containerFor(doc.root, st), false, pb);
            if (root != prev) return null; // 根の箱が作り直された: 全体を作り直す〔表は辿った箱を入れ直してあるので全体の経路でも整合する〕
            if (!pb.visited.isEmpty()) {
                com.nishiyu.enderframelib.core.layout.BoxTreeBuilder.build(root, CounterStyles.of(doc.sheet.counterStyles),
                        b -> b.node != null && (b.node.isPseudoNode || pb.visited.containsKey(b.node)));
                st.lastBuiltStructureVersion = st.domStructureVersion;
                // 辿った箱のうち、本当に変わった箱だけを渡す（変わっていない祖先の経路は印付けが自分で辿る。根を渡すと全体の印付けになる）
                List<LayoutBox> changed = new ArrayList<>();
                Set<LayoutBox> shallow = new HashSet<>();
                for (LayoutBox b : pb.visited.values()) collectChanged(b, changed, shallow);
                // 一度も測っていない新しい箱は、親が測り直されれば親の測定の中で測られる（個別に印付けしない）
                changed.removeIf(b -> b.neverMeasured() && b.parent != null && (shallow.contains(b.parent) || b.parent.neverMeasured()));
                if (!changed.isEmpty()) RenderPipeline.relayoutInPlace(root, rootW, rootH, root.devicePixelRatio(), scrollState, fonts, changed, shallow);
            }
            return root;
        } finally {
            prev.setReusableTable(table);
        }
    }

    /** 変わった箱を集める。DOM に無い匿名ボックスは辿った箱に入らないので、辿った箱の下の匿名ボックス〔中身が付け替わり得る〕も見る。 */
    private static void collectChanged(LayoutBox b, List<LayoutBox> changed, Set<LayoutBox> shallow) {
        int kind = b.changeSinceMeasure();
        if (kind != 0) {
            changed.add(b);
            if (kind == 1 && !b.children.isEmpty()) shallow.add(b);
        }
        if (!b.hasGeneratedBelow()) return;
        for (int i = 0, n = b.children.size(); i < n; i++) {
            LayoutBox c = b.children.get(i);
            if (c.isAnonymous) collectChanged(c, changed, shallow);
        }
    }

    /**
     * {@code node} の部分木を辿らず、前回の箱をそのまま返せるなら、その箱。返せなければ null。
     * 台帳に触れられておらず、スタイルの記憶が有効で、表の外で、前回の箱が表にある箱だけ。
     */
    LayoutBox keep(HtmlNode node, LayoutBox.ReusableTable prev, boolean ancestorMoving, ComputedStyle computed) {
        return prev == null || ledger.touches(node) ? null : intact(prev.peek(node), ancestorMoving, computed);
    }

    /**
     * 親が汚れていないとき、その子を 1 つずつ {@link #keep} に聞かず、触れられた子の集合 {@code touchedKids} 〔{@link ChangeLedger#touchedChildren}〕
     * と子自身の汚れ印だけで、前回の箱を返せるか決める（触れられた親の子の並びを組み直す費用を、子の数に比例する小さい定数にする）。
     */
    LayoutBox keepFast(HtmlNode child, LayoutBox.ReusableTable prev, java.util.Set<HtmlNode> touchedKids, boolean ancestorMoving, InteractionState st) {
        if (prev == null || touchedKids.contains(child)) return null;
        InteractionState.StateFlags f = st.get(child);
        if (f == null || f.isStyleDirty() || f.subtreeDirty() || f.dirtyBelow()) return null;
        LayoutBox box = prev.peek(child);
        return intact(box, ancestorMoving, box == null ? null : box.style);
    }

    private static boolean unnumbered(String type) {
        return "none".equalsIgnoreCase(type) || "disc".equalsIgnoreCase(type) || "circle".equalsIgnoreCase(type) || "square".equalsIgnoreCase(type);
    }

    private static LayoutBox intact(LayoutBox box, boolean ancestorMoving, ComputedStyle computed) {
        if (box == null || LayoutBox.isInternalTableBox(box) || box.touchedByTableEngine()) return null;
        // 番号つきのリスト項目は、兄弟の増減で印の番号が変わる（番号を使わない none・disc・circle・square は兄弟に依らない）
        if (box.display().listItem() && !unnumbered(box.style.getOrDefault(com.nishiyu.enderframelib.core.css.CssPropertyIndex.LIST_STYLE_TYPE, "disc"))) return null;
        return box.movingStamped && box.selfOrAncestorMoving == (ancestorMoving || PaintOrder.hasMovingProps(computed)) ? box : null;
    }

    java.util.Set<HtmlNode> touchedChildren(HtmlNode parent) { return ledger.touchedChildren(parent); }

    /** 辿った箱として数える（箱の木の組み立て・測定の印付けの対象になる）。 */
    void visited(LayoutBox box) {
        if (box.node != null) visited.put(box.node, box);
    }

    /**
     * 辿る箱の子の並びを組み直す前に、親から外れた子〔DOM で別の親へ移った・消えた〕の箱を再利用表から外す
     * 〔次に同じ番号の節が来ても取り違えず、古い箱を持ち続けない〕。
     */
    void forgetRemoved(LayoutBox box, HtmlNode node) {
        List<LayoutBox> kids = box.elementChildren;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox k = kids.get(i);
            if (k.node != null && !k.node.isPseudoNode && k.node.parent != node) forget(k);
        }
    }

    private void forget(LayoutBox sub) {
        if (sub.node != null && !sub.node.isPseudoNode) table.remove(sub.node);
        List<LayoutBox> kids = sub.elementChildren;
        for (int i = 0, n = kids.size(); i < n; i++) forget(kids.get(i));
    }
}
