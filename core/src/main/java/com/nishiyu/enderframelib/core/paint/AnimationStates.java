package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 1 つのビュー（セッション）の補間の状態（{@link CssAnimator.AnimState}）を、<b>節ごと</b>に持つ表（M3b-4b-2）。
 *
 * <p>CSS の遷移は要素ごとなので、状態は節の番号（{@link DisplayItem#nodeHandleOf} で項目の鍵から引く）ごとにまとめ、その中で部品
 * （項目の ID。背景・枠線・文字の断片など）ごとに持つ。部品の鍵（{@code 節 << 16 | 部品}）は項目ごとに一意でない（キャンバスの背景と
 * body の背景が同じ鍵、背景のタイルは 16 枚を超えると折り返す）ので、節の中では項目の ID で引く。節を持たない項目（文字列から作った鍵）は
 * 番号 0 の節にまとめる。</p>
 *
 * <p>後片付けは実時間でなく印で行う（{@link #retainNodesOf}）: 項目の列の構造が変わったとき、列にある節に印を付け、印の無い節の状態を
 * まとめて捨てる。状態の寿命は「節が列にある間」で、節が残る限り、いったん消えた部品の状態も残る（同じ部品が戻ったときに遷移が続く）。</p>
 *
 * <p>描く側と当たり判定の側が同じ表を引くので、表の出し入れは同期する（状態そのものの読み書きは呼び出し側の責任）。</p>
 */
public final class AnimationStates {
    /** 1 つの節の部品ごとの状態。部品は数個なので、線形に探す。 */
    private static final class NodeStates {
        String[] ids = new String[2];
        CssAnimator.AnimState[] states = new CssAnimator.AnimState[2];
        int size;
        int mark;

        CssAnimator.AnimState get(String id) {
            for (int i = 0; i < size; i++) {
                String k = ids[i];
                if (k == id || k.equals(id)) return states[i];
            }
            return null;
        }

        void put(String id, CssAnimator.AnimState st) {
            if (size == ids.length) {
                ids = java.util.Arrays.copyOf(ids, size * 2);
                states = java.util.Arrays.copyOf(states, size * 2);
            }
            ids[size] = id;
            states[size] = st;
            size++;
        }
    }

    private final Int2ObjectOpenHashMap<NodeStates> byNode = new Int2ObjectOpenHashMap<>();
    private int sweepMark;
    private int stateCount;

    /** 項目 {@code el} の持ち主の節の番号（文字列から作った鍵・鍵の無い項目は 0）。 */
    static int nodeOf(DisplayItem el) {
        long key = el.key();
        return key == 0L || (key & DisplayItem.STRING_KEY_BIT) != 0 ? 0 : DisplayItem.nodeHandleOf(key);
    }

    private static String partOf(DisplayItem el) {
        String id = el.id();
        return id != null ? id : "";
    }

    /** 状態が 1 つも無いか。 */
    public synchronized boolean isEmpty() {
        return stateCount == 0;
    }

    /** 状態の数（診断・テスト用）。 */
    public synchronized int size() {
        return stateCount;
    }

    /** 状態を持つ節の数（診断・テスト用。節を持たない項目の置き場も 1 つと数える）。 */
    public synchronized int nodeCount() {
        return byNode.size();
    }

    /** 項目 {@code el} の状態。無ければ null（作らない）。 */
    public synchronized CssAnimator.AnimState peek(DisplayItem el) {
        NodeStates ns = byNode.get(nodeOf(el));
        return ns != null ? ns.get(partOf(el)) : null;
    }

    /** 節 {@code nodeHandle} の部品 {@code id} の状態。無ければ null（作らない）。当たり判定が節の錨（部品の ID は節の鍵）を引く。 */
    public synchronized CssAnimator.AnimState peek(int nodeHandle, String id) {
        NodeStates ns = byNode.get(Math.max(0, nodeHandle));
        return ns != null && id != null ? ns.get(id) : null;
    }

    /** 項目 {@code el} の状態。無ければ時刻 {@code now} の目標で作る（目標の更新は {@link CssAnimator#stateFor}）。 */
    synchronized CssAnimator.AnimState getOrCreate(DisplayItem el, long now) {
        int node = nodeOf(el);
        NodeStates ns = byNode.get(node);
        if (ns == null) {
            ns = new NodeStates();
            ns.mark = sweepMark;
            byNode.put(node, ns);
        }
        String id = partOf(el);
        CssAnimator.AnimState st = ns.get(id);
        if (st == null) {
            st = new CssAnimator.AnimState(el, now);
            ns.put(id, st);
            stateCount++;
        }
        return st;
    }

    /**
     * 列 {@code items} にある節の状態だけを残し、他の節の状態を捨てる（列の構造が変わったときに描く側が呼ぶ。実時間は見ない）。
     */
    public synchronized void retainNodesOf(List<DisplayItem> items) {
        if (byNode.isEmpty()) return;
        int mark = ++sweepMark;
        for (int i = 0, n = items.size(); i < n; i++) {
            NodeStates ns = byNode.get(nodeOf(items.get(i)));
            if (ns != null) ns.mark = mark;
        }
        for (Iterator<NodeStates> it = byNode.values().iterator(); it.hasNext(); ) {
            NodeStates ns = it.next();
            if (ns.mark != mark) {
                stateCount -= ns.size;
                it.remove();
            }
        }
    }

    /** 全部捨てる（セッションを閉じたとき）。 */
    public synchronized void clear() {
        byNode.clear();
        stateCount = 0;
    }

    /** 診断用: 節ごとの部品の ID（テストが状態の置き場所を確かめる）。 */
    synchronized Map<Integer, List<String>> partsByNode() {
        Map<Integer, List<String>> out = new HashMap<>();
        for (var e : byNode.int2ObjectEntrySet()) {
            NodeStates ns = e.getValue();
            out.put(e.getIntKey(), List.of(java.util.Arrays.copyOf(ns.ids, ns.size)));
        }
        return out;
    }
}
