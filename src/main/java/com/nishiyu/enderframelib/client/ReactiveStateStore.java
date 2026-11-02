package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.UiStateStore;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * リアクティブ状態のストア。実体はコアの {@link UiStateStore} で、ここは文書を持つセッションを
 * {@link ClientWebManager} の台帳から引く口と、下位互換用のヘルパー委譲だけを持つ。
 */
public class ReactiveStateStore extends UiStateStore {

    public ReactiveStateStore(String sessionId) {
        super(sessionId, () -> ClientWebManager.getSession(sessionId));
    }

    // ------------------------------------------------------------------
    // 下位互換用ヘルパー委譲（Bindings へ委譲）
    // ------------------------------------------------------------------

    public static List<HtmlNode> extractTemplateChildren(HtmlNode src) {
        return Bindings.extractTemplateChildren(src);
    }

    public static Boolean evaluateCondition(String expr, Map<String, String> scopeData) {
        return Bindings.evaluateCondition(expr, scopeData);
    }

    public static final class TemplateDeps {
        public static final String ALWAYS = Bindings.TemplateDeps.ALWAYS;
        private final Bindings.TemplateDeps delegate = new Bindings.TemplateDeps();

        public Set<String> index(HtmlNode root) { return delegate.index(root); }
        public Set<String> lookup(HtmlNode node) { return delegate.lookup(node); }
        public void clear() { delegate.clear(); }

        public static boolean canSkip(Set<String> deps, Set<String> changedKeys) {
            return Bindings.TemplateDeps.canSkip(deps, changedKeys);
        }
    }
}
