package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.MutationObserver.Callback;
import com.nishiyu.enderframelib.api.dom.MutationObserver;
import com.nishiyu.enderframelib.api.dom.MutationObserver.Init;
import com.nishiyu.enderframelib.api.dom.MutationObserver.Record;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.IdentityHashMap;

/**
 * MutationObserver の通知管理と配信キュー。
 *
 * <p>DOM Standard §4.3 に準拠し、DOM変異（childList, attributes, characterData）を
 * 監視中の各 {@link MutationObserver} へ振り分ける。</p>
 *
 * <p>LIMITATION(制約): JavaScript のマイクロタスクを持たないため、
 * 変異通知はクライアント tick（再スタイルの直前）または手動 flush() でまとめて配信する。
 * README の "Not supported" に対応。</p>
 */
public final class MutationObserverManager {

    static {
        // API 側のファクトリプロバイダを初期化
        MutationObserver.Factory.provider = HtmlMutationObserver::new;
    }

    /** 節が属する文書。文書に繋がっていない節は null。 */
    @Nullable
    private static HtmlDocument documentOf(@Nullable Node node) {
        if (node instanceof HtmlDocument doc) return doc;
        return node != null && node.getOwnerDocument() instanceof HtmlDocument doc ? doc : null;
    }

    /**
     * 対象の節が属する文書を監視中の observer へ通知キューイングする。
     */
    public static void queue(Record record) {
        if (record == null) return;
        HtmlDocument doc = documentOf(record.getTarget());
        if (doc == null) return;
        for (HtmlMutationObserver obs : doc.mutationObservers()) {
            obs.tryEnqueue(record);
        }
    }

    /**
     * childList 変異レコードを作成してキューイングする。
     */
    public static void queueChildList(Node target, List<Node> addedNodes, List<Node> removedNodes,
                                      @Nullable Node previousSibling, @Nullable Node nextSibling) {
        HtmlDocument doc = documentOf(target);
        if (doc == null || doc.mutationObservers().isEmpty()) return;
        Record record = new HtmlMutationRecord(
                "childList",
                target,
                new HtmlCollections.StaticNodeList(addedNodes),
                new HtmlCollections.StaticNodeList(removedNodes),
                previousSibling,
                nextSibling,
                null,
                null
        );
        queue(record);
    }

    /**
     * attributes 変異レコードを作成してキューイングする。
     */
    public static void queueAttributes(Node target, String attributeName, @Nullable String oldValue) {
        HtmlDocument doc = documentOf(target);
        if (doc == null || doc.mutationObservers().isEmpty()) return;
        Record record = new HtmlMutationRecord(
                "attributes",
                target,
                new HtmlCollections.StaticNodeList(Collections.emptyList()),
                new HtmlCollections.StaticNodeList(Collections.emptyList()),
                null,
                null,
                attributeName,
                oldValue
        );
        queue(record);
    }

    /**
     * characterData 変異レコードを作成してキューイングする。
     */
    public static void queueCharacterData(Node target, @Nullable String oldValue) {
        HtmlDocument doc = documentOf(target);
        if (doc == null || doc.mutationObservers().isEmpty()) return;
        Record record = new HtmlMutationRecord(
                "characterData",
                target,
                new HtmlCollections.StaticNodeList(Collections.emptyList()),
                new HtmlCollections.StaticNodeList(Collections.emptyList()),
                null,
                null,
                null,
                oldValue
        );
        queue(record);
    }

    /**
     * 保留中の変異レコードを各 observer のコールバックに配信する。
     */
    public static void flush(HtmlDocument doc) {
        for (HtmlMutationObserver obs : doc.mutationObservers()) {
            obs.deliver();
        }
    }

    /**
     * 文書の observer を切断し、キューをクリアする（テスト用／セッション破棄用）。
     */
    public static void clearAll(HtmlDocument doc) {
        for (HtmlMutationObserver obs : doc.mutationObservers()) {
            obs.disconnect();
        }
        doc.mutationObservers().clear();
    }

    // ==========================================
    // HtmlMutationRecord
    // ==========================================

    public static final class HtmlMutationRecord implements Record {
        private final String type;
        private final Node target;
        private final NodeList addedNodes;
        private final NodeList removedNodes;
        private final Node previousSibling;
        private final Node nextSibling;
        private final String attributeName;
        private final String oldValue;

        public HtmlMutationRecord(String type, Node target, NodeList addedNodes, NodeList removedNodes,
                                  @Nullable Node previousSibling, @Nullable Node nextSibling,
                                  @Nullable String attributeName, @Nullable String oldValue) {
            this.type = type;
            this.target = target;
            this.addedNodes = addedNodes;
            this.removedNodes = removedNodes;
            this.previousSibling = previousSibling;
            this.nextSibling = nextSibling;
            this.attributeName = attributeName;
            this.oldValue = oldValue;
        }

        @Override
        public String getType() { return type; }

        @Override
        public Node getTarget() { return target; }

        @Override
        public NodeList getAddedNodes() { return addedNodes; }

        @Override
        public NodeList getRemovedNodes() { return removedNodes; }

        @Nullable
        @Override
        public Node getPreviousSibling() { return previousSibling; }

        @Nullable
        @Override
        public Node getNextSibling() { return nextSibling; }

        @Nullable
        @Override
        public String getAttributeName() { return attributeName; }

        @Nullable
        @Override
        public String getOldValue() { return oldValue; }
    }

    // ==========================================
    // HtmlMutationObserver
    // ==========================================

    public static final class HtmlMutationObserver implements MutationObserver {

        private record TargetRegistration(Node target, Init options) {}

        private final Callback callback;
        private final List<TargetRegistration> registrations = new ArrayList<>();
        private final Queue<Record> queue = new ArrayDeque<>();
        private final Set<HtmlDocument> documents = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean disconnected = false;

        public HtmlMutationObserver(Callback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Callback must not be null");
            }
            this.callback = callback;
        }

        @Override
        public void observe(Node target, Init options) {
            if (target == null) throw new IllegalArgumentException("Target node must not be null");
            if (options == null) throw new IllegalArgumentException("Init must not be null");

            if (!options.isChildList() && !options.isAttributes() && !options.isCharacterData()) {
                throw new DOMException("At least one of childList, attributes, or characterData must be true.", "TypeError");
            }
            if (options.isAttributeOldValue() && !options.isAttributes()) {
                throw new DOMException("attributeOldValue is true but attributes is false.", "TypeError");
            }
            if (options.getAttributeFilter() != null && !options.isAttributes()) {
                throw new DOMException("attributeFilter is present but attributes is false.", "TypeError");
            }
            if (options.isCharacterDataOldValue() && !options.isCharacterData()) {
                throw new DOMException("characterDataOldValue is true but characterData is false.", "TypeError");
            }

            disconnected = false;
            // 既存の同一ターゲットの登録を置き換え
            registrations.removeIf(reg -> reg.target() == target);
            registrations.add(new TargetRegistration(target, options));
            HtmlDocument doc = documentOf(target);
            if (doc != null && documents.add(doc)) {
                doc.mutationObservers().add(this);
            }
        }

        @Override
        public void disconnect() {
            disconnected = true;
            registrations.clear();
            queue.clear();
            for (HtmlDocument doc : documents) {
                doc.mutationObservers().remove(this);
            }
            documents.clear();
        }

        @Override
        public List<Record> takeRecords() {
            List<Record> snapshot = new ArrayList<>(queue);
            queue.clear();
            return snapshot;
        }

        void tryEnqueue(Record record) {
            if (disconnected || registrations.isEmpty()) return;

            for (TargetRegistration reg : registrations) {
                Node regTarget = reg.target();
                Init opts = reg.options();

                boolean isTarget = (regTarget == record.getTarget());
                boolean inSubtree = opts.isSubtree() && regTarget.contains(record.getTarget());

                if (!isTarget && !inSubtree) continue;

                String type = record.getType();
                if ("childList".equals(type) && opts.isChildList()) {
                    queue.add(record);
                    break;
                } else if ("attributes".equals(type) && opts.isAttributes()) {
                    if (opts.getAttributeFilter() != null && !opts.getAttributeFilter().contains(record.getAttributeName())) {
                        continue;
                    }
                    String oldVal = opts.isAttributeOldValue() ? record.getOldValue() : null;
                    queue.add(new HtmlMutationRecord(
                            "attributes",
                            record.getTarget(),
                            record.getAddedNodes(),
                            record.getRemovedNodes(),
                            null,
                            null,
                            record.getAttributeName(),
                            oldVal
                    ));
                    break;
                } else if ("characterData".equals(type) && opts.isCharacterData()) {
                    String oldVal = opts.isCharacterDataOldValue() ? record.getOldValue() : null;
                    queue.add(new HtmlMutationRecord(
                            "characterData",
                            record.getTarget(),
                            record.getAddedNodes(),
                            record.getRemovedNodes(),
                            null,
                            null,
                            null,
                            oldVal
                    ));
                    break;
                }
            }
        }

        void deliver() {
            if (disconnected || queue.isEmpty()) return;
            List<Record> copy = new ArrayList<>(queue);
            queue.clear();
            try {
                callback.handleMutations(copy, this);
            } catch (Exception e) {
                // コールバックのエラーは握りつぶさずにログや標準エラー等に出すが配信処理を継続
                e.printStackTrace();
            }
        }
    }
}
