package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.OrthogonalStyles;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入力側の増分コンパイラ（Engine v3 M2a。計画書 §3.3 A）。{@code (DOM, 凍結した ChangeLedger, 前回の BoxNode 木) → BoxNode 木}。
 *
 * <p><b>変更の入口は {@link ChangeLedger} だけ</b>。DOM の節に汚れの欄は足さない。呼び出し側がフレームの始めに
 * {@link ChangeLedger#freeze()} した世代を読み、その世代の変更の節と祖先（{@code 変更数 × 深さ}）だけを「触れられた」とする。</p>
 *
 * <p>前回の木と DOM を<b>並走</b>させて照合するので、節 → 前回の箱の対応表は持たない（ヒープの増分ゼロ）。節 {@code n} は次の 3 通り:</p>
 * <ol>
 *   <li><b>O(1) 完全スキップ</b>: 触れられておらず、親から渡る継承群・カスタムプロパティが前回と同じ参照なら、前回の箱を
 *       そのまま（{@code ==}）返す。部分木は辿らない。</li>
 *   <li><b>継承の打ち切り</b>: 解き直した結果の群が前回と同値なら前回のインスタンスへ寄せる（{@link Style#shareEqualGroups}）。
 *       子へ渡る継承群の参照が変わらないので、触れられていない子孫は 1. に落ちる。</li>
 *   <li><b>継承値が本当に変わった</b>: 子を全員解き直す（CSS の仕様上避けられない費用）。</li>
 * </ol>
 *
 * <p>子の並びは {@link BoxChildren} のチャンクごとに照合し、DOM の並びが同じで触れられていないチャンクは同じ参照のまま使う。
 * 8,000 子への 1 件挿入で作り直すチャンクは挿入位置の 1 個（伸びて 64 を超えたら 2 個）。</p>
 *
 * <p>{@code inherit} キーワードを使う文書では、子の要素の算出値が親の非継承の値に依り得るので、親の算出スタイルが変わったら
 * 子の要素を解き直す（文字の節は解き直さない）。</p>
 *
 * <p>非局所の依存は次の規則 1 つに閉じる: 兄弟の位置を読むセレクタ（{@code + ~ :nth-* :empty}）を使う文書では、子の増減が
 * あった親の子を全員解き直す。{@code :has()} を使う文書と、台帳があふれた／節を絞れない変更（{@code ENV}・
 * 全体の {@code STYLE}）の世代は、全体を作り直す。コンテナ問い合わせは、容器の寸法が変わった（{@link #restyleContainers}）・
 * 容器の {@code container-type} / {@code container-name} が変わったときに容器の部分木を解き直す（M2b-5）。</p>
 */
public final class BoxTreeCompiler {
    private static final int SELF_STYLE = 1, SELF_CHILDREN = 2, SELF_TEXT = 4;

    private StyleSource styles;
    /** 前回のコンパイルで使った解き方（{@link #restyleWith} で替わったら次のコンパイルで全ての要素を解き直す）。 */
    private StyleSource compiledWith;
    private BoxNode root;

    // ---- 1 回のコンパイルの間だけ使う（凍結した台帳から作る） ----
    private final Map<HtmlNode, Integer> self = new IdentityHashMap<>();
    private final Set<HtmlNode> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<HtmlNode> parentOfTouched = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean positional, usesInherit;
    /** 部分木を全て解き直す容器（{@link #restyleContainers}）と、その中を辿っている深さ（0 より大きい間は子孫を全て解き直す）。 */
    private final Set<HtmlNode> restyleBelow = Collections.newSetFromMap(new IdentityHashMap<>());
    private int deep;
    /** カウンタを使う文書のコンパイル中だけ非 null（文書順に積み上げる。カウンタを使う文書は常に全体を作り直す）。 */
    private Counters counters;
    private final Counters counterState = new Counters();
    private com.nishiyu.enderframelib.core.css.CounterStyles counterStyles = com.nishiyu.enderframelib.core.css.CounterStyles.builtins();
    /** {@link com.nishiyu.enderframelib.core.css.GeneratedContentStyles#usesCounters} を規則の版ごとに 1 回だけ求めた値（このビューの実例に閉じる）。 */
    private StyleSheet countedSheet;
    private int countedVersion = -1;
    private boolean sheetUsesCounters, sheetUsesCapitalize;

    private final Stats stats = new Stats();

    public BoxTreeCompiler(StyleSource styles) {
        this.styles = styles;
    }

    /** 直前のコンパイルの観測値（テスト・計測用）。 */
    public static final class Stats {
        boolean full;
        int rebuiltBoxes, restyled, rebuiltChunks, reusedChunks;

        /** 全体を作り直したか。 */
        public boolean full() { return full; }
        /** 新しく作った {@link BoxNode} の数。 */
        public int rebuiltBoxes() { return rebuiltBoxes; }
        /** 算出スタイルを解き直した節の数。 */
        public int restyled() { return restyled; }
        /** 新しく作った {@link BoxChunk} の数（チャンク化した親のもの）。 */
        public int rebuiltChunks() { return rebuiltChunks; }
        /** 前回の参照のまま使ったチャンクの数。 */
        public int reusedChunks() { return reusedChunks; }

        void reset() {
            full = false;
            rebuiltBoxes = restyled = rebuiltChunks = reusedChunks = 0;
        }

        @Override
        public String toString() {
            return "full=" + full + " rebuiltBoxes=" + rebuiltBoxes + " restyled=" + restyled
                    + " rebuiltChunks=" + rebuiltChunks + " reusedChunks=" + reusedChunks;
        }
    }

    public Stats stats() { return stats; }

    /** 前回の結果（まだ無ければ null）。 */
    public BoxNode root() { return root; }

    /** 箱の木 {@code root} の要素の算出スタイル（擬似要素と文字を除く。全体を作り直す前の値として {@link StyleSource#history} へ渡す）。 */
    public static Map<HtmlNode, Style> elementStyles(BoxNode root) {
        Map<HtmlNode, Style> out = new IdentityHashMap<>();
        java.util.ArrayDeque<BoxNode> stack = new java.util.ArrayDeque<>();
        if (root != null) stack.push(root);
        while (!stack.isEmpty()) {
            BoxNode b = stack.pop();
            if (b.node != null && b.pseudo == BoxNode.Pseudo.NONE && b.node.nodeType == NodeType.ELEMENT && b.computed != null) out.put(b.node, b.computed);
            for (int c = 0, cn = b.children.chunkCount(); c < cn; c++) {
                BoxChunk ch = b.children.chunk(c);
                for (int i = 0, n = ch.size(); i < n; i++) stack.push(ch.get(i));
            }
        }
        return out;
    }

    /**
     * スタイルの解き方を差し替える（ビューポートの大きさの変化: {@code vw} などの単位・メディアクエリ・置換要素の候補が変わる）。次の
     * {@link #compile} は、掛かる規則が替わった（{@link StyleSource#sameRules} でない）なら全ての要素を、そうでなければビューポートに依る
     * 要素（{@link #markViewportDependent}）だけを解き直す。どちらも前回の木と並走して、値が同じ群は前回のインスタンスへ寄せ、何も変わらない
     * 箱は前回の参照のまま返す（下流のレイアウトのキャッシュが {@code ==} で当たる）。
     */
    public void restyleWith(StyleSource styles) {
        this.styles = styles;
    }

    /**
     * 箱の木を作る。{@code ledger} は呼び出し側が凍結済みであること（この世代の変更を読む）。最初の呼び出し、根の節が
     * 変わったとき、全体に効く世代は、全体を作り直す。解き方が前回と違えば（{@link #restyleWith}）その分を解き直す。
     */
    public BoxNode compile(HtmlNode domRoot, ChangeLedger ledger) {
        stats.reset();
        boolean full = readLedger(ledger) || root == null || root.node != domRoot;
        StyleSheet sheet = styles.sheet();
        boolean resolved = !full && styles != compiledWith;
        // カウンタ・capitalize の語の続きは文書順の状態を積み直すので、解き直すなら全体を作り直す（restyleContainers と同じ）
        if (resolved && sheet != null && (usesCounters(sheet) || sheetUsesCapitalize)) full = true;
        boolean rulesChanged = resolved && !styles.sameRules(compiledWith);
        compiledWith = styles;
        stats.full = full;
        counters = full && usesCounters(sheet) ? counterState : null;
        if (counters != null) counters.clear();
        if (sheet != null) counterStyles = com.nishiyu.enderframelib.core.css.CounterStyles.of(sheet.counterStyles);
        if (resolved && !full) {
            if (rulesChanged) {
                touched.add(domRoot);
                deep = 1; // 根から下の全ての要素を解き直す（restyleContainers の容器の部分木と同じ）
            } else {
                markViewportDependent(domRoot);
            }
        }
        try {
            root = compileNode(domRoot, Style.DEFAULT, full ? null : root, full, WritingModes.HORIZONTAL);
            return root;
        } finally {
            deep = 0;
            self.clear();
            restyleBelow.clear();
            touched.clear();
            parentOfTouched.clear();
        }
    }

    /**
     * 掛かる規則が同じままビューポートの大きさだけが変わった: 前回の算出値がビューポートの単位を使った要素（派生の事実
     * {@code StyleFacts.usesViewportUnits}）・ビューポートで候補を選ぶ置換要素（{@link ReplacedInput#dependsOnViewport}）と、ルート要素
     * （{@code rem} の基準を解き方に覚えさせる）を、台帳の {@code STYLE} と同じく「自身が変わった節」にする。継承値が変われば子孫は
     * 継承の打ち切りの規則で解き直される。
     */
    private void markViewportDependent(HtmlNode domRoot) {
        for (HtmlNode c : domRoot.children) if (c.nodeType == NodeType.ELEMENT) markSelf(c, SELF_STYLE);
        java.util.ArrayDeque<BoxNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            BoxNode b = stack.pop();
            if (b.kind != BoxKind.TEXT && b.node != null && b.computed != null
                    && (b.computed.facts.usesViewportUnits || ReplacedInput.dependsOnViewport(b.node))) {
                markSelf(b.node.isPseudoNode && b.node.parent != null ? b.node.parent : b.node, SELF_STYLE);
            }
            BoxChildren kids = b.children;
            for (int c = 0, cn = kids.chunkCount(); c < cn; c++) {
                BoxChunk ch = kids.chunk(c);
                for (int i = 0, n = ch.size(); i < n; i++) stack.push(ch.get(i));
            }
        }
    }

    private void markSelf(HtmlNode node, int bit) {
        self.merge(node, bit, (a, b) -> a | b);
        for (HtmlNode cur = node; cur != null && touched.add(cur); cur = cur.parent) {
            if (cur.parent != null) parentOfTouched.add(cur.parent);
        }
    }

    /**
     * コンテナ問い合わせの 2 段目（M2b-5）: レイアウトの後で寸法が変わった容器 {@code containers} の子孫を全て解き直す（容器の寸法を読む
     * {@code @container} と {@code cqw} などの単位）。カウンタ・capitalize を使う文書は文書順の状態を積み直すので全体を作り直す。
     */
    public BoxNode restyleContainers(HtmlNode domRoot, java.util.Collection<HtmlNode> containers) {
        StyleSheet sheet = styles.sheet();
        if (root == null || root.node != domRoot || (sheet != null && (usesCounters(sheet) || sheetUsesCapitalize))) {
            return compile(domRoot, null);
        }
        stats.reset();
        positional = sheet != null && sheet.usesPositionalSelectors();
        usesInherit = sheet != null && sheet.usesInheritKeyword();
        counters = null;
        try {
            for (HtmlNode c : containers) {
                restyleBelow.add(c);
                for (HtmlNode cur = c; cur != null && touched.add(cur); cur = cur.parent) {
                    if (cur.parent != null) parentOfTouched.add(cur.parent);
                }
            }
            root = compileNode(domRoot, Style.DEFAULT, root, false, WritingModes.HORIZONTAL);
            return root;
        } finally {
            restyleBelow.clear();
            deep = 0;
            touched.clear();
            parentOfTouched.clear();
        }
    }

    /** 凍結した世代から「自身が変わった節」と「触れられた節（祖先を含む）」を作る。全体を作り直すべき世代なら true。 */
    private boolean readLedger(ChangeLedger ledger) {
        StyleSheet sheet = styles.sheet();
        positional = sheet != null && sheet.usesPositionalSelectors();
        usesInherit = sheet != null && sheet.usesInheritKeyword();
        if (ledger == null) return true;
        if (ledger.frozenOverflow()) return true;
        int n = ledger.frozenSize();
        // カウンタ・capitalize の語の続きは文書順の非局所の状態。コンテナ問い合わせは容器の寸法が変わったときに駆動側が
        // restyleContainers で容器の部分木を解き直す（M2b-5。容器のスタイルの変化は容器の子の解き直しとして届く）
        if (n > 0 && sheet != null && (sheet.usesHasSelectors() || usesCounters(sheet) || sheetUsesCapitalize)) return true;
        for (int i = 0; i < n; i++) {
            HtmlNode node = ledger.frozenNode(i);
            if (node == null) {
                if (ledger.frozenIs(i, ChangeKind.SCROLL)) continue; // 箱の木には効かない
                return true;
            }
            int bit = ledger.frozenIs(i, ChangeKind.CHILDREN) ? SELF_CHILDREN
                    : ledger.frozenIs(i, ChangeKind.TEXT) ? SELF_TEXT
                    : ledger.frozenIs(i, ChangeKind.SCROLL) ? 0 : SELF_STYLE;
            if (bit == 0) continue;
            if (ledger.frozenIs(i, ChangeKind.STYLE_SUBTREE)) restyleBelow.add(node); // 子孫を全て解き直す（容器の 2 段目と同じ仕組み）
            self.merge(node, bit, (a, b) -> a | b);
            for (HtmlNode cur = node; cur != null && touched.add(cur); cur = cur.parent) {
                if (cur.parent != null) parentOfTouched.add(cur.parent);
            }
        }
        return false;
    }

    /**
     * 節 1 つ。{@code prev} は同じ節の前回の箱（無ければ null）。{@code restyle} は親の継承値が変わった・兄弟の位置が
     * 変わったなど、この節自身を解き直すべきとき。{@code parentStyle} は親のカスケードの算出スタイル（軸を入れ替える前）、
     * {@code flow} は節を置く縦書きの文脈（M2b-5。{@link OrthogonalBoxes#childContext} の符号。横書きなら 0）。文脈は祖先の継承値
     * {@code writing-mode} と位置指定で決まるので、変われば祖先か自分の解き直しとして届く。
     */
    private BoxNode compileNode(HtmlNode n, Style parentStyle, BoxNode prev, boolean restyle, int flow) {
        if (prev != null && !restyle && !touched.contains(n) && prev.context == flow) return prev; // 1. O(1) 完全スキップ
        int selfKinds = self.getOrDefault(n, 0);
        // 文字の節のスタイルは親だけで決まる（Style.forTextChild）ので、文字の節への STYLE は解き直しの理由にしない
        boolean resolve = prev == null || restyle || ((selfKinds & SELF_STYLE) != 0 && n.nodeType != NodeType.TEXT);
        Style style;
        if (n.nodeType == NodeType.TEXT) {
            style = resolve ? styles.text(parentStyle) : prev.computed;
        } else if (n.nodeType == NodeType.ELEMENT) {
            style = resolve ? styles.element(n, parentStyle, prev != null ? prev.computed : null) : prev.computed;
        } else {
            style = Style.DEFAULT;
        }
        if (resolve) stats.restyled++;
        if (prev != null) style = style.shareEqualGroups(prev.computed); // 2. 継承の打ち切り
        BoxKind kind = BoxKind.of(n, style);
        // 閉じた details の summary 以外の子は箱を作らない（HTML §15.5.? 。旧 RenderTreeBuilder と同じ。open の付け外しは details の
        // 解き直しとして届き、下の kidsRestyle で子を解き直す）
        if (kind != BoxKind.NONE && n.parent != null
                && com.nishiyu.enderframelib.core.dom.node.ElementTraits.isHiddenByClosedDetails(n.parent, n.parent.attrs.containsKey("open"), n)) {
            kind = BoxKind.NONE;
        }
        String text = null;
        BoxChildren children = BoxChunk.EMPTY;
        BoxNode before = null, after = null, marker = null;
        Style firstLine = null;
        if (kind == BoxKind.TEXT) {
            text = TextContent.of(n.text, style, style.inherited.textTransform == com.nishiyu.enderframelib.core.css.model.CssKeywords.TextTransform.CAPITALIZE
                    && TextContent.continuesWord(n));
            if (prev != null && prev.text != null && prev.text.equals(text)) text = prev.text;
        } else if (kind != BoxKind.NONE) {
            boolean usable = prev != null && prev.kind != BoxKind.NONE && prev.kind != BoxKind.TEXT;
            boolean kidsRestyle = !usable || style.inherited != prev.computed.inherited || style.inheritedPaint != prev.computed.inheritedPaint
                    || style.custom != prev.computed.custom
                    || (resolve && com.nishiyu.enderframelib.core.dom.node.ElementTraits.isDetails(n.tag));
            // inherit キーワードは要素にしか効かない（文字の節のスタイルは親の継承群だけで決まる）
            boolean elemRestyle = usable && usesInherit && style != prev.computed;
            boolean element = n.nodeType == NodeType.ELEMENT;
            // コンテナ問い合わせの相手（擬似要素と子孫の祖先の列。M2b-5）。容器の種類・名前が変わったら子孫を全て解き直す
            boolean deepen = element && (restyleBelow.contains(n) || (usable && containerChanged(style, prev.computed)));
            if (element) styles.scope().push(n, style);
            if (deepen) deep++;
            try {
                if (counters != null && element) counters.apply(n, style, listItem(style));
                int mark = counters != null ? counters.mark() : 0;
                // 疑似要素: 持ち主を解き直したとき（とカウンタを使う文書）だけ作り直し、それ以外は前回の箱のまま
                boolean pseudos = element && PseudoBoxes.capable(n);
                // 寸法が変わった容器の疑似要素も解き直す（疑似要素にとっては持ち主が容器。M2b-5）
                boolean redoPseudo = resolve || counters != null || !usable || deepen || deep > 0;
                if (pseudos && MarkerBoxes.listItem(style)) marker = redoPseudo ? marker(n, style, usable ? prev.marker : null) : prev.marker;
                if (pseudos) before = redoPseudo ? pseudo(BoxNode.Pseudo.BEFORE, n, style, usable ? prev : null) : prev.pseudoChild(BoxNode.Pseudo.BEFORE);
                int childContext = OrthogonalBoxes.childContext(OrthogonalBoxes.rootCode(n, style, kind, OrthogonalBoxes.flowOf(flow)), flow, style);
                // 子の縦書きの文脈が変わった（位置指定の付け外しなど、継承値に現れない変化）なら子を解き直す
                if (usable && childContext != OrthogonalBoxes.childContext(prev.orthogonal, prev.context, prev.computed)) kidsRestyle = true;
                if (deep > 0) kidsRestyle = true;
                children = compileChildren(n, style, usable ? prev.children : null, kidsRestyle, elemRestyle, selfKinds, childContext);
                // 番号に依るマーカー: 子の並びが変わった・子に触れた（li の value は箱を変えない）・自分を解き直した（start・reversed）なら
                // 文書順に数え直す（カウンタを使う文書は Counters）
                if (counters == null && children.hasOrdinalMarkers()
                        && (!usable || children != prev.children || resolve || parentOfTouched.contains(n))) {
                    int[] rebuilt = {0};
                    children = MarkerBoxes.renumber(n, children, styles, counterStyles, rebuilt);
                    stats.rebuiltBoxes += rebuilt[0];
                }
                if (pseudos) after = redoPseudo ? pseudo(BoxNode.Pseudo.AFTER, n, style, usable ? prev : null) : prev.pseudoChild(BoxNode.Pseudo.AFTER);
                if (element && firstLineContainer(kind)) { // ::first-line（値が前回と同じなら前回の参照）
                    firstLine = redoPseudo || !usable ? styles.firstLine(n, style) : prev.firstLine;
                    if (usable && firstLine != null && firstLine.equals(prev.firstLine)) firstLine = prev.firstLine;
                }
                if (counters != null) counters.release(mark);
            } finally {
                if (deepen) deep--;
                if (element) styles.scope().pop();
            }
        }
        // 置換要素の大きさの入力（属性・選択肢の文字）の写し。値が前回と同じなら前回の参照（属性だけの変化も箱の変化として下流へ届く）
        ReplacedInput replaced = kind == BoxKind.NONE || kind == BoxKind.TEXT ? null
                : ReplacedInput.of(n, styles.viewportW(), styles.viewportH());
        if (replaced != null && prev != null && replaced.equals(prev.replaced)) replaced = prev.replaced;
        PopoverLink popover = kind == BoxKind.NONE ? null : PopoverLink.of(n); // 暗黙のアンカーの属性の写し（M2b-5）
        if (popover != null && prev != null && popover.equals(prev.popover)) popover = prev.popover;
        // 縦書きの文脈（M2b-5）: レイアウトが読むスタイルは軸を入れ替えたもの、直交フローの根は流れの中身を包む箱のスタイルを持つ。
        // 算出スタイルと文脈が前回と同じなら前回の参照（レイアウトのキャッシュが == で照合する）
        int axis = n.nodeType == NodeType.ELEMENT && kind != BoxKind.NONE ? OrthogonalBoxes.axisOf(flow, style) : WritingModes.HORIZONTAL;
        int orth = kind == BoxKind.NONE ? 0 : OrthogonalBoxes.rootCode(n, style, kind, OrthogonalBoxes.flowOf(flow));
        boolean sameStyle = prev != null && style == prev.computed;
        Style layoutStyle = axis == WritingModes.HORIZONTAL ? style
                : sameStyle && prev.context == flow ? prev.style : OrthogonalStyles.axisSwapped(style, axis);
        Style wrapperStyle = null;
        if (orth != 0) {
            BoxNode pw = sameStyle && prev.orthogonal == orth && prev.kind == kind ? OrthogonalBoxes.wrapperOf(prev) : null;
            wrapperStyle = pw != null ? pw.style : OrthogonalStyles.wrapper(style, OrthogonalBoxes.verticalFlow(orth),
                    !OrthogonalBoxes.returnsToHorizontal(orth), OrthogonalBoxes.wrapperDisplay(kind, orth));
        }
        if (prev != null && style == prev.computed && layoutStyle == prev.style && orth == prev.orthogonal && flow == prev.context
                && (orth == 0 || wrapperStyle == OrthogonalBoxes.wrapperOf(prev).style)
                && kind == prev.kind && text == prev.text && children == prev.children
                && replaced == prev.replaced && popover == prev.popover && TableBoxes.spanOf(n, kind, BoxNode.Pseudo.NONE) == prev.span
                && before == prev.pseudoChild(BoxNode.Pseudo.BEFORE) && after == prev.pseudoChild(BoxNode.Pseudo.AFTER)
                && marker == prev.marker && firstLine == prev.firstLine) return prev;
        stats.rebuiltBoxes++;
        return new BoxNode(n, layoutStyle, kind, text, children, BoxNode.Pseudo.NONE, before, after, replaced, marker, MarkerBoxes.NO_ORDINAL,
                prev != null ? prev.flow : null, style, flow, orth, wrapperStyle, popover, firstLine, prev != null ? prev.layout : null);
    }

    /** {@code ::first-line} を持てるブロックコンテナ（ブロック・インラインブロック・表のセルと見出し。旧 {@code LayoutBox.isFirstLineContainer}）。 */
    private static boolean firstLineContainer(BoxKind kind) {
        return kind == BoxKind.BLOCK || kind == BoxKind.INLINE_BLOCK || kind == BoxKind.TABLE_CELL || kind == BoxKind.TABLE_CAPTION;
    }

    /**
     * リスト項目のマーカー。番号はカウンタを使う文書なら {@code Counters} の値、それ以外は前回の番号（無ければ 1）を仮に使い、親が
     * {@link MarkerBoxes#renumber} で直す。
     */
    private BoxNode marker(HtmlNode owner, Style style, BoxNode prev) {
        int ordinal = counters != null ? counters.value(Counters.LIST_ITEM)
                : prev != null && prev.ordinal != MarkerBoxes.NO_ORDINAL ? prev.ordinal : 1;
        BoxNode b = MarkerBoxes.build(owner, style, prev, styles, counters, counterStyles, ordinal);
        if (b != null && b != prev) stats.rebuiltBoxes += 2;
        return b;
    }

    private BoxNode pseudo(BoxNode.Pseudo which, HtmlNode owner, Style style, BoxNode prevOwner) {
        BoxNode prev = prevOwner != null ? prevOwner.pseudoChild(which) : null;
        BoxNode b = PseudoBoxes.build(which, owner, style, prev, styles, counters, counterStyles);
        if (b != null && b != prev) stats.rebuiltBoxes++;
        return b;
    }

    private boolean usesCounters(StyleSheet sheet) {
        if (sheet == null) return false;
        if (sheet != countedSheet || sheet.rules.version() != countedVersion) {
            countedSheet = sheet;
            countedVersion = sheet.rules.version();
            sheetUsesCounters = com.nishiyu.enderframelib.core.css.GeneratedContentStyles.usesCounters(sheet);
            sheetUsesCapitalize = com.nishiyu.enderframelib.core.css.GeneratedContentStyles.usesCapitalize(sheet);
        }
        return sheetUsesCounters;
    }

    /** 容器の種類・名前が変わったか（非継承なので子の解き直しとして届かない。子孫のコンテナ問い合わせの相手が変わる）。 */
    private static boolean containerChanged(Style a, Style b) {
        return a.rare.containerType != b.rare.containerType || !java.util.Objects.equals(a.rare.containerName, b.rare.containerName);
    }

    private static boolean listItem(Style st) {
        String d = st.box.display;
        return d != null && d.contains("list-item");
    }

    /** 子の並び。前回の並びのチャンクのうち、DOM の並びが同じで触れられていないものは参照のまま使う。 */
    private BoxChildren compileChildren(HtmlNode p, Style pStyle, BoxChildren prevKids, boolean parentChanged, boolean elemRestyle,
                                        int selfKinds, int flow) {
        List<HtmlNode> dom = p.children;
        int n = dom.size();
        if (prevKids == null) {
            List<BoxNode> fresh = new ArrayList<>(n);
            for (int i = 0; i < n; i++) fresh.add(compileNode(dom.get(i), pStyle, null, true, flow));
            BoxChildren out = BoxChildren.of(fresh);
            if (out.chunkCount() > 1) stats.rebuiltChunks += out.chunkCount();
            return out;
        }
        boolean childListChanged = (selfKinds & SELF_CHILDREN) != 0;
        boolean restyleAll = parentChanged || (positional && childListChanged);
        boolean anyTouched = parentOfTouched.contains(p);
        if (!restyleAll && !elemRestyle && !childListChanged && !anyTouched) return prevKids;

        boolean chunked = prevKids.chunkCount() > 1;
        List<BoxChunk> out = new ArrayList<>(prevKids.chunkCount() + 1);
        List<BoxNode> pending = new ArrayList<>();
        int d = 0;
        for (int k = 0, kc = prevKids.chunkCount(); k < kc; k++) {
            BoxChunk c = prevKids.chunk(k);
            if (!restyleAll && !elemRestyle && chunkIntact(c, dom, d, anyTouched)) {
                flush(pending, out, chunked);
                out.add(c);
                if (chunked) stats.reusedChunks++;
                d += c.size();
                continue;
            }
            int end = anchorIndex(dom, d, prevKids, k + 1, p);
            int base = pending.size();
            for (int i = d; i < end; i++) {
                HtmlNode ch = dom.get(i);
                pending.add(compileNode(ch, pStyle, findIn(c, ch, i - d), restyleAll || (elemRestyle && ch.nodeType == NodeType.ELEMENT), flow));
            }
            if (pending.size() - base == c.size() && sameAs(c, pending, base)) { // 中身が前回と同じ: チャンクも同じ参照
                for (int i = pending.size() - 1; i >= base; i--) pending.remove(i);
                flush(pending, out, chunked);
                out.add(c);
                if (chunked) stats.reusedChunks++;
            }
            d = end;
        }
        for (int i = d; i < n; i++) pending.add(compileNode(dom.get(i), pStyle, null, true, flow)); // 末尾に足された子
        flush(pending, out, chunked);
        if (out.size() == prevKids.chunkCount()) {
            boolean same = true;
            for (int k = 0; k < out.size() && same; k++) same = out.get(k) == prevKids.chunk(k);
            if (same) return prevKids;
        }
        BoxChildren result = BoxChildren.ofChunks(out);
        if (!chunked && result.chunkCount() > 1) stats.rebuiltChunks += result.chunkCount();
        return result;
    }

    /** チャンク {@code c} の子が DOM の {@code d} から同じ節の並びで続き、どれも触れられていないか。 */
    private boolean chunkIntact(BoxChunk c, List<HtmlNode> dom, int d, boolean anyTouched) {
        int size = c.size();
        if (d + size > dom.size()) return false;
        for (int i = 0; i < size; i++) {
            HtmlNode node = c.get(i).node;
            if (dom.get(d + i) != node) return false;
            if (anyTouched && touched.contains(node)) return false;
        }
        return true;
    }

    /**
     * DOM の {@code d} 以降で、{@code from} 番目以降のチャンクの（今も {@code p} の子である）先頭の節が現れる位置。
     * 無ければ DOM の末尾。並べ替えで前へ動いた節は見つからないので、次の候補へ進む。
     */
    private static int anchorIndex(List<HtmlNode> dom, int d, BoxChildren prevKids, int from, HtmlNode p) {
        int n = dom.size();
        for (int k = from, kc = prevKids.chunkCount(); k < kc; k++) {
            BoxChunk c = prevKids.chunk(k);
            for (int j = 0, cs = c.size(); j < cs; j++) {
                HtmlNode a = c.get(j).node;
                if (a.parent != p) continue;
                for (int i = d; i < n; i++) if (dom.get(i) == a) return i;
            }
        }
        return n;
    }

    /** チャンク内で節 {@code node} の前回の箱（{@code hint} の位置を先に見る）。無ければ null。 */
    private static BoxNode findIn(BoxChunk c, HtmlNode node, int hint) {
        if (hint >= 0 && hint < c.size() && c.get(hint).node == node) return c.get(hint);
        for (int i = 0, n = c.size(); i < n; i++) if (c.get(i).node == node) return c.get(i);
        return null;
    }

    private static boolean sameAs(BoxChunk c, List<BoxNode> list, int from) {
        for (int i = 0, n = c.size(); i < n; i++) if (c.get(i) != list.get(from + i)) return false;
        return true;
    }

    /** 作り直した子をチャンクにして {@code out} へ足す（{@link BoxChildren#MAX_CHUNK} 以下は 1 個、超えたら 32 ずつ）。 */
    private void flush(List<BoxNode> pending, List<BoxChunk> out, boolean chunked) {
        int n = pending.size();
        if (n == 0) return;
        if (n <= BoxChildren.MAX_CHUNK) {
            out.add(new BoxChunk(pending.toArray(new BoxNode[0])));
            if (chunked) stats.rebuiltChunks++;
        } else {
            for (int from = 0; from < n; from += BoxChildren.CHUNK) {
                out.add(new BoxChunk(pending.subList(from, Math.min(n, from + BoxChildren.CHUNK)).toArray(new BoxNode[0])));
                if (chunked) stats.rebuiltChunks++;
            }
        }
        pending.clear();
    }
}
