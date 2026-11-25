package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.AnonymousStyles;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 表の構造を補う匿名の箱（Engine v3 M2b-4。CSS 2.2 §17.2.1 の「欠けた子」と「欠けた親」。旧 {@code BoxTreeBuilder.fixupTable} の移植）。
 * {@link BoxNode#flow}（レイアウトが並べる子）だけを組み直し、{@link BoxNode#children} は DOM の子と 1 対 1 のまま（増分の照合を崩さない）。
 *
 * <ul>
 *   <li>表の子のうち、行グループ・行・キャプション・列でない子の並びは匿名の行で包む（行の中で匿名のセルになる）。</li>
 *   <li>行グループの子のうち行でない子の並びは匿名の行で、行の子のうちセルでない子の並びは匿名のセルで包む。</li>
 *   <li>表・行グループ・行の子の空白だけの文字は箱を作らない（{@code white-space} に依らない。旧経路とブラウザの実装に合わせる）。</li>
 *   <li>それ以外の親の下のセルの並びは匿名の行で、行・行グループ・キャプション・列の並びは匿名の表（インライン要素の下なら
 *       {@code inline-table}）で包む。並びの間の畳まれる空白は捨てる。</li>
 * </ul>
 *
 * <p>{@code display: contents} の子は先に中身へ展開してから判定する（旧経路と同じく、要素ツリーの子として扱う）。</p>
 *
 * <p>匿名の箱は前回の {@link BoxNode#flow} の中で、種類・スタイル・包んだ子の並び（{@code ==}）が同じ匿名の箱をそのまま使う
 * （下流のレイアウトのキャッシュが当たる）。表の構造を持たない親（大半）は {@link BoxChildren#hasTableInternal()} の O(チャンク数) の
 * 判定だけで抜け、確保しない。</p>
 */
public final class TableBoxes {
    private TableBoxes() { }

    /** 親 {@code node}（スタイル {@code style}・種類 {@code kind}）の並び {@code raw} を補った並び。補う必要が無ければ {@code raw}。 */
    static BoxChildren fixup(HtmlNode node, Style style, BoxKind kind, BoxChildren raw, BoxChildren prevFlow) {
        boolean tableParent = kind.tableContainer() || kind == BoxKind.TABLE_ROW_GROUP || kind == BoxKind.TABLE_ROW;
        if (raw.size() == 0 || kind == BoxKind.TEXT || kind == BoxKind.NONE || kind == BoxKind.CONTENTS || kind == BoxKind.TABLE_COLUMN) return raw;
        if (!tableParent && !raw.hasTableInternal()) return raw;
        List<BoxNode> items = raw.hasContents() ? raw.toList() : flat(raw);
        Fixer f = new Fixer(node, style, prevFlow);
        List<BoxNode> out;
        if (tableParent) {
            items = dropBlank(items);
            if (kind.tableContainer()) out = f.wrapRuns(items, c -> c.kind != BoxKind.NONE && !properTableChild(c), BoxKind.TABLE_ROW);
            else if (kind == BoxKind.TABLE_ROW_GROUP) out = f.wrapRuns(items, c -> c.kind != BoxKind.NONE && c.kind != BoxKind.TABLE_ROW, BoxKind.TABLE_ROW);
            else out = f.wrapRuns(items, c -> c.kind != BoxKind.NONE && c.kind != BoxKind.TABLE_CELL, BoxKind.TABLE_CELL);
        } else {
            List<BoxNode> withRows = f.wrapRuns(items, c -> c.kind == BoxKind.TABLE_CELL, BoxKind.TABLE_ROW);
            out = f.wrapRuns(withRows, TableBoxes::properTableChild, kind == BoxKind.INLINE ? BoxKind.INLINE_TABLE : BoxKind.TABLE);
        }
        if (same(out, raw)) return raw;
        if (prevFlow != null && same(out, prevFlow)) return prevFlow;
        return BoxChildren.of(out);
    }

    /**
     * セルの {@code colspan}（下位 16 ビット）と {@code rowspan}（上位 16 ビット。0 は行グループの最後まで）、列の {@code span}（下位 16 ビット）。
     * 属性が無い・読めない値は 1（HTML §4.9.11。{@code colspan} は 1〜1000、{@code rowspan} は 0〜65534、{@code span} は 1〜1000）。
     * 表のセル・列でなければ 0。
     */
    static int spanOf(HtmlNode node, BoxKind kind, BoxNode.Pseudo pseudo) {
        if (pseudo != BoxNode.Pseudo.NONE || node == null || node.attrs == null) return 0;
        if (kind == BoxKind.TABLE_CELL) return attr(node, "colspan", false, 1000) | attr(node, "rowspan", true, 65534) << 16;
        if (kind == BoxKind.TABLE_COLUMN) return attr(node, "span", false, 1000);
        return 0;
    }

    /** セルの {@code colspan}（{@link BoxNode#span}）。 */
    public static int colSpan(BoxNode cell) {
        return Math.max(1, cell.span & 0xFFFF);
    }

    /** セルの {@code rowspan}（0 は行グループの最後まで）。 */
    public static int rowSpan(BoxNode cell) {
        return cell.span == 0 ? 1 : cell.span >>> 16;
    }

    private static int attr(HtmlNode node, String name, boolean zero, int max) {
        String v = node.attrs.get(name);
        if (v == null) return 1;
        try {
            int n = Integer.parseInt(v.trim());
            return zero && n == 0 ? 0 : Math.max(1, Math.min(max, n));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 表の正しい子（行グループ・行・キャプション・列）か。 */
    private static boolean properTableChild(BoxNode b) {
        return b.kind == BoxKind.TABLE_ROW_GROUP || b.kind == BoxKind.TABLE_ROW || b.kind == BoxKind.TABLE_CAPTION || b.kind == BoxKind.TABLE_COLUMN;
    }

    private static List<BoxNode> flat(BoxChildren kids) {
        List<BoxNode> out = new ArrayList<>(kids.size());
        for (int c = 0, n = kids.chunkCount(); c < n; c++) {
            BoxChunk ch = kids.chunk(c);
            for (int i = 0; i < ch.size(); i++) out.add(ch.get(i));
        }
        return out;
    }

    private static List<BoxNode> dropBlank(List<BoxNode> items) {
        List<BoxNode> kept = new ArrayList<>(items.size());
        for (BoxNode b : items) if (b.kind != BoxKind.TEXT || !b.text.isBlank()) kept.add(b);
        return kept;
    }

    /** 並び {@code list} が {@code kids} と同じ箱の同じ順か。 */
    private static boolean same(List<BoxNode> list, BoxChildren kids) {
        if (list.size() != kids.size()) return false;
        int k = 0;
        for (int c = 0, n = kids.chunkCount(); c < n; c++) {
            BoxChunk ch = kids.chunk(c);
            for (int i = 0; i < ch.size(); i++) if (ch.get(i) != list.get(k++)) return false;
        }
        return true;
    }

    /** 1 つの親の組み直しの作業域（前回の匿名の箱の候補を持つ）。 */
    private static final class Fixer {
        private final HtmlNode owner;
        private final Style parentStyle;
        private final List<BoxNode> prevAnon = new ArrayList<>();

        Fixer(HtmlNode owner, Style parentStyle, BoxChildren prevFlow) {
            this.owner = owner;
            this.parentStyle = parentStyle;
            if (prevFlow != null) {
                for (int c = 0, n = prevFlow.chunkCount(); c < n; c++) {
                    BoxChunk ch = prevFlow.chunk(c);
                    for (int i = 0; i < ch.size(); i++) collectAnon(ch.get(i), 0);
                }
            }
        }

        private void collectAnon(BoxNode b, int depth) {
            if (b.pseudo != BoxNode.Pseudo.ANONYMOUS) return;
            prevAnon.add(b);
            if (depth == 0) for (int i = 0, n = b.children.size(); i < n; i++) collectAnon(b.children.get(i), 1); // 匿名の表の中の匿名の行
        }

        /** 条件を満たす子の連続を 1 つの匿名の箱で包む。並びの間・最後の並びの後ろの畳まれる空白は捨て、並びと他の子の間の空白は残す。 */
        List<BoxNode> wrapRuns(List<BoxNode> items, Predicate<BoxNode> inRun, BoxKind anonKind) {
            List<BoxNode> out = new ArrayList<>(items.size());
            List<BoxNode> run = new ArrayList<>(), pendingWs = new ArrayList<>();
            for (BoxNode item : items) {
                if (inRun.test(item)) {
                    pendingWs.clear();
                    run.add(item);
                } else if (!run.isEmpty() && item.collapsibleWhitespace()) {
                    pendingWs.add(item);
                } else {
                    flush(run, anonKind, out);
                    out.addAll(pendingWs);
                    pendingWs.clear();
                    out.add(item);
                }
            }
            flush(run, anonKind, out); // 最後の並びの後ろの畳まれる空白は箱を作らない（旧経路と同じ）
            return out;
        }

        private void flush(List<BoxNode> run, BoxKind kind, List<BoxNode> out) {
            if (run.isEmpty()) return;
            out.add(anonymous(kind, run));
            run.clear();
        }

        /** 匿名の箱。前回の匿名の箱で種類・スタイル・包んだ子が同じものがあればそれ。 */
        private BoxNode anonymous(BoxKind kind, List<BoxNode> run) {
            Style st = AnonymousStyles.of(parentStyle, display(kind));
            BoxNode sameKind = null;
            for (int i = 0; i < prevAnon.size(); i++) {
                BoxNode p = prevAnon.get(i);
                if (p.kind != kind) continue;
                if (same(run, p.children) && st.shareEqualGroups(p.style) == p.style) {
                    prevAnon.remove(i);
                    return p;
                }
                if (sameKind == null) sameKind = p;
            }
            if (sameKind != null) st = st.shareEqualGroups(sameKind.style);
            return new BoxNode(owner, st, kind, null, BoxChildren.of(run), BoxNode.Pseudo.ANONYMOUS, null, null, null, null,
                    MarkerBoxes.NO_ORDINAL, sameKind != null ? sameKind.flow : null);
        }

        private static String display(BoxKind kind) {
            return switch (kind) {
                case TABLE -> "table";
                case INLINE_TABLE -> "inline-table";
                case TABLE_ROW -> "table-row";
                default -> "table-cell";
            };
        }
    }
}
