// ==========================================
// 新規: core/dom/NodeId.java
// ==========================================
package com.nishiyu.enderframelib.core.dom;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ノードIDの生成と管理。
 * プロセスグローバルの単調増加をやめ、ドキュメント/セッション内で安定したIDを提供します。
 */
public class NodeId {
    // セッションや再パース時にリセットできるローカルカウンタ（パーサのインスタンスに持たせるのが理想ですが、移行用として）
    private static final ThreadLocal<AtomicInteger> localAutoId = ThreadLocal.withInitial(() -> new AtomicInteger(0));

    public static void resetCounter() {
        localAutoId.get().set(0);
    }

    public static String generateId() {
        return "gen_" + localAutoId.get().incrementAndGet();
    }

    public static String generateTextId() {
        return "txt_" + localAutoId.get().incrementAndGet();
    }
}