package com.nishiyu.enderframelib.core.dom.node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 節の番号（{@link NodeIds}）の振り方。{@link HtmlNode} から分けた（M5-1）。
 *
 * <p>番号の空間は木の根が 1 つ持つ（文書は最初の子が付いたとき、切り離された木は番号を問われたときに作る）。
 * 子の並びに加わった部分木には番号を振り、外れた部分木の番号は手放す（静的領域の番号は覚えておき、同じ文書へ
 * 戻れば同じ鍵になる）。番号を持つのは子の並びに載った節だけで、描画が作る仮想の節は持たない。</p>
 */
final class NodeNumbering {
    private NodeNumbering() {}

    /** {@code n} が {@code ids} 上の自分の番号を持っているか。 */
    static boolean numberedIn(HtmlNode n, NodeIds ids) {
        return ids != null && n.ids == ids && n.nodeId >= 0 && ids.owner(n.nodeId) == n;
    }

    /** {@code child} の部分木が {@code parent} の子の並びに加わった。木に番号の空間があれば番号を振る。 */
    static void attached(HtmlNode parent, HtmlNode child) {
        NodeIds ids = treeIds(parent, false);
        if (ids == null) return;
        // 文書の根は最初の子が付いたときに番号の空間を持つ。親にも番号が要る
        if (!numberedIn(parent, ids)) assign(parent, ids, false);
        assign(child, ids, true);
    }

    /** {@code child} の部分木が子の並びから外れた。同じ木の中の移動でなければ番号を手放す。 */
    static void detached(HtmlNode child) {
        if (numberedIn(child, child.ids) && !child.movingInTree) release(child);
    }

    private static void release(HtmlNode n) {
        NodeIds ids = n.ids;
        if (numberedIn(n, ids)) {
            // 静的領域の番号は nodeId を覚えておく（同じ文書へ戻れば同じ鍵）。動的領域の番号は手放す
            boolean keep = n.nodeId < ids.staticCount();
            ids.free(n.nodeId);
            n.derivedKey = null;
            if (!keep) { n.nodeId = -1; n.ids = null; }
        }
        for (HtmlNode c : n.children) release(c);
    }

    /** {@code n} の木（根まで辿った先）の番号の空間。文書の根は常に作る。断片の根は {@code create} のときだけ作り、木ごと番号を振る。 */
    static NodeIds treeIds(HtmlNode n, boolean create) {
        HtmlNode top = n.treeTop();
        if (top.rootIds() == null && (create || top instanceof HtmlDocument)) {
            NodeIds ids = top.ensureRootIds();
            if (!(top instanceof HtmlDocument)) numberTree(top, ids);
        }
        return top.rootIds();
    }

    /** 木全体を行きがけ順に番号付けする（既存の番号は捨てて振り直す）。 */
    private static void numberTree(HtmlNode top, NodeIds ids) {
        ids.reset();
        assign(top, ids, true);
        ids.sealStatic();
    }

    private static void assign(HtmlNode n, NodeIds ids, boolean recurse) {
        if (n.ids == ids && n.nodeId >= 0 && (ids.owner(n.nodeId) == n || ids.reclaimStatic(n.nodeId, n))) {
            // 取り戻した番号をそのまま使う
        } else {
            NodeIds old = n.ids;
            int oldId = n.nodeId;
            boolean migrate = old != null && old != ids && oldId >= 0 && old.owner(oldId) == n;
            n.nodeId = ids.allocate(n);
            n.ids = ids;
            n.derivedKey = null;
            if (migrate) {
                old.migrateSlots(oldId, ids, n.nodeId);
                old.free(oldId);
            }
        }
        if (recurse) for (HtmlNode c : n.children) assign(c, ids, true);
    }

    /** 番号。無ければ（切り離された木でも）ここで木ごと番号を振る。 */
    static int idOf(HtmlNode n) {
        if (numberedIn(n, n.ids)) return n.nodeId;
        NodeIds tree = treeIds(n, true);
        if (!numberedIn(n, tree)) assign(n, tree, false);
        return n.nodeId;
    }

    /** 明示した {@code id} があればそれ、無ければ番号から導く {@code ~番号}（テキストは {@code ~t番号}）。 */
    static String key(HtmlNode n) {
        String id = n.attrs.get("id");
        if (id != null && !id.isEmpty()) return id;
        int number = idOf(n);
        String k = n.derivedKey;
        if (k == null) {
            n.derivedKey = k = (n.nodeType == NodeType.TEXT ? HtmlNode.DERIVED_TEXT_KEY_PREFIX : "" + HtmlNode.DERIVED_KEY_PREFIX) + number;
        }
        return k;
    }

    /** {@link #key} に {@code "_scrollc"} を付けたスクロール内容ノード用の鍵（鍵が同じ間は同じ文字列）。 */
    static String scrollContentKey(HtmlNode n) {
        String k = key(n);
        HtmlNode.Rare r = n.rare();
        if (r.scrollContentKeyBase == k && r.scrollContentKeyCache != null) return r.scrollContentKeyCache;
        r.scrollContentKeyBase = k;
        return r.scrollContentKeyCache = k + "_scrollc";
    }

    /** 描画部品の鍵（{@code 鍵 + suffix}。スロット 0..25。鍵が同じ間は同じ文字列）。 */
    static String partKey(HtmlNode n, int slot, String suffix) {
        String k = key(n);
        HtmlNode.Rare r = n.rare();
        String[] cache = r.partKeyCache;
        if (r.partKeyBase != k || cache == null) {
            r.partKeyBase = k;
            r.partKeyCache = cache = new String[26];
        }
        String hit = cache[slot];
        return hit != null ? hit : (cache[slot] = k + suffix);
    }

    /** {@code ~番号} の鍵が指す節を {@code scope} の木の番号の空間から引く。鍵が派生鍵の形でなければ null。 */
    static HtmlNode resolveDerivedKey(HtmlNode scope, String key) {
        if (key.length() < 2 || key.length() > 12 || key.charAt(0) != HtmlNode.DERIVED_KEY_PREFIX) return null;
        long value = 0;
        for (int i = key.charAt(1) == 't' ? 2 : 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') return null;
            value = value * 10 + (c - '0');
        }
        if (value > Integer.MAX_VALUE) return null;
        NodeIds ids = scope.treeTop().rootIds();
        return ids != null && ids.owner((int) value) instanceof HtmlNode found ? found : null;
    }

    /** 文書のパースが終わった。番号を行きがけ順に振り直し、静的領域として閉じる（サーバーとクライアントで鍵が揃う）。 */
    static void seal(HtmlNode any) {
        HtmlNode top = any.treeTop();
        numberTree(top, top.ensureRootIds());
    }

    /**
     * ネットワーク越しに受け取った番号で、木の番号を振り直す（サーバーの鍵とクライアントの鍵を揃える）。
     * {@code idOf} が負を返す節には、空いている番号を新しく振る。
     */
    static void restore(HtmlNode any, ToIntFunction<HtmlNode> idOf, int staticCount) {
        HtmlNode top = any.treeTop();
        NodeIds ids = top.ensureRootIds();
        ids.reset();
        List<HtmlNode> unnumbered = new ArrayList<>();
        restoreWalk(top, ids, idOf, unnumbered);
        ids.finishRestore(staticCount);
        for (HtmlNode n : unnumbered) {
            n.nodeId = ids.allocate(n);
            n.ids = ids;
        }
    }

    private static void restoreWalk(HtmlNode n, NodeIds ids, ToIntFunction<HtmlNode> idOf, List<HtmlNode> unnumbered) {
        int want = idOf.applyAsInt(n);
        n.derivedKey = null;
        if (want >= 0 && (want >= ids.highWater() || ids.owner(want) == null)) {
            ids.restore(want, n);
            n.nodeId = want;
            n.ids = ids;
        } else {
            n.nodeId = -1;
            n.ids = null;
            unnumbered.add(n);
        }
        for (HtmlNode c : n.children) restoreWalk(c, ids, idOf, unnumbered);
    }
}
