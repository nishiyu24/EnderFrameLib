package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.List;

/**
 * 前回の箱の木から、DOM の節ごとの箱を O(1) 配列で引ける索引表。
 * 構築パス自身が次の構築用に埋めることで、再構築前の全木走査（{@code collectReusableBoxes}）と
 * {@code IdentityHashMap} 確保を省く。旧経路の部分構築だけが使う（M2c ② で {@link LayoutBox} の入れ子から出した。M2b-6 で削除）。
 */
public final class ReusableTable {
    final com.nishiyu.enderframelib.core.kernel.memory.DocumentArena arena;
    private LayoutBox[] byId;
    private LayoutBox[] spareById;
    private int maxUsedId = -1;
    private java.util.IdentityHashMap<HtmlNode, LayoutBox> unindexed;

    ReusableTable(com.nishiyu.enderframelib.core.kernel.memory.DocumentArena arena,
                          LayoutBox[] recycled, int cap) {
        this.arena = arena;
        if (arena != null) {
            this.byId = (recycled != null && recycled.length >= cap)
                    ? recycled : new LayoutBox[Math.max(64, cap)];
        } else {
            this.byId = null;
        }
    }

    public static ReusableTable createForBuild(HtmlNode rootNode, ReusableTable previous) {
        var arena = rootNode != null && rootNode.hasNodeId() ? rootNode.arena() : null;
        int cap = arena != null ? Math.max(64, arena.highWater()) : 0;
        LayoutBox[] spare = (previous != null && previous.arena == arena) ? previous.spareById : null;
        if (previous != null && previous.arena == arena) previous.spareById = null;
        return new ReusableTable(arena, spare, cap);
    }

    public boolean matchesRoot(HtmlNode rootNode) {
        var rootArena = rootNode != null && rootNode.hasNodeId() ? rootNode.arena() : null;
        return arena == rootArena;
    }

    public void putIfAbsent(HtmlNode node, LayoutBox box) {
        if (node == null || box == null) return;
        if (arena != null && node.hasNodeId() && node.arena() == arena) {
            int id = node.nodeId();
            if (id >= byId.length) {
                byId = java.util.Arrays.copyOf(byId, Math.max(byId.length * 2, id + 1));
            }
            if (byId[id] == null) {
                byId[id] = box;
                if (id > maxUsedId) maxUsedId = id;
            }
        } else {
            if (unindexed == null) unindexed = new java.util.IdentityHashMap<>();
            unindexed.putIfAbsent(node, box);
        }
    }

    public LayoutBox take(HtmlNode node) {
        if (node == null) return null;
        if (arena != null && node.hasNodeId() && node.arena() == arena) {
            int id = node.nodeId();
            if (id < byId.length) {
                LayoutBox b = byId[id];
                if (b != null) {
                    byId[id] = null;
                    return b.node == node ? b : null;
                }
            }
            return null;
        }
        return unindexed != null ? unindexed.remove(node) : null;
    }

    public void remove(HtmlNode node) {
        take(node);
    }

    /** 外さずに引く（部分構築が、辿らない部分木の箱を表に残したまま確かめる）。 */
    public LayoutBox peek(HtmlNode node) {
        if (node == null) return null;
        if (arena != null && node.hasNodeId() && node.arena() == arena) {
            int id = node.nodeId();
            LayoutBox b = id < byId.length ? byId[id] : null;
            return b != null && b.node == node ? b : null;
        }
        return unindexed != null ? unindexed.get(node) : null;
    }

    public void releaseAfterBuild(ReusableTable previous) {
        if (previous == null || previous == this || previous.arena != this.arena) return;
        if (previous.byId != null) {
            if (previous.maxUsedId >= 0) {
                int limit = Math.min(previous.maxUsedId + 1, previous.byId.length);
                java.util.Arrays.fill(previous.byId, 0, limit, null);
                previous.maxUsedId = -1;
            }
            this.spareById = previous.byId;
            previous.byId = null;
        }
        if (previous.unindexed != null) previous.unindexed.clear();
    }

    /** 外側の table/inline-table 以外の表内部ボックス（行・セル・キャプション等）か（使い回さない箱）。 */
    public static boolean isInternalTableBox(LayoutBox box) {
        return LayoutBox.isTableBox(box) && box.display().inner() != LayoutBox.Display.Inner.TABLE;
    }

    /** 根の箱 {@code rootBox} に、次のフレームの部分構築で使う表を持たせる。 */
    public static void attach(LayoutBox rootBox, ReusableTable table) {
        rootBox.reusableTable = table;
    }

    /** 根の箱に持たせた表を外して返す（無い・文書が違えば前回の木を辿って作る）。 */
    public static ReusableTable acquire(LayoutBox rootBox, HtmlNode docRoot) {
        if (rootBox == null) return null;
        ReusableTable existing = rootBox.reusableTable;
        rootBox.reusableTable = null;
        if (existing != null && existing.matchesRoot(docRoot)) {
            return existing;
        }
        ReusableTable collected = ReusableTable.createForBuild(docRoot, null);
        collect(rootBox, collected);
        return collected;
    }

    /**
     * 差分ビルド中に {@code box} を次フレーム用の再利用テーブルへ登録し、表スコープ（子孫を再利用不可にする境界）に入るかを返す。
     */
    public static boolean record(ReusableTable out, LayoutBox box) {
        boolean table = LayoutBox.isTableBox(box);
        if (out != null && !box.isAnonymous && !box.node.isPseudoNode
                && !isInternalTableBox(box)) {
            out.putIfAbsent(box.node, box);
        }
        return table || box.measureMemo.everLaidOutAsTable();
    }

    private static void collect(LayoutBox box, ReusableTable out) {
        if (box.isAnonymous || box.node.isPseudoNode) return;
        boolean table = LayoutBox.isTableBox(box);
        if (isInternalTableBox(box)) return;
        out.putIfAbsent(box.node, box);
        if (table || box.measureMemo.everLaidOutAsTable()) return;
        List<LayoutBox> kids = box.elementChildren;
        for (int i = 0, n = kids.size(); i < n; i++) collect(kids.get(i), out);
    }
}
