package com.nishiyu.enderframelib.core.dom.node;

import java.util.List;
import java.util.ServiceLoader;

/**
 * ノードが、上の層（セレクタ・HTML の解析・対話状態）へ頼む口（SPI）。実装は {@code core.dom.DomNodeServices}（同梱）で、
 * {@link ServiceLoader} で見つける。ノードの型がセレクタ・算出スタイル・パーサの型を知らずに済むようにするため
 * （S5・S8: {@code dom.node} を最下層にする）。
 */
public interface NodeServices {
    /** {@code root} の下で最初に {@code selectors} に当たるノード（無ければ null）。 */
    HtmlNode querySelector(HtmlNode root, String selectors);

    /** {@code root} の下で {@code selectors} に当たるノード（文書順）。 */
    List<HtmlNode> querySelectorAll(HtmlNode root, String selectors);

    /** {@code element} 自身が {@code selectors} に当たるか。 */
    boolean matches(HtmlElement element, String selectors);

    /** 新しい文書の対話状態。 */
    DocumentState newDocumentState();

    /** HTML の断片を、文脈の要素 {@code contextTag} の下の木として解析する（{@code innerHTML} の既定）。 */
    HtmlNode parseFragment(String html, String contextTag);

    /** 実装（同梱の実装が無い環境では、使った時点で例外）。 */
    static NodeServices get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final NodeServices INSTANCE = load();

        private static NodeServices load() {
            for (NodeServices s : ServiceLoader.load(NodeServices.class, NodeServices.class.getClassLoader())) return s;
            throw new IllegalStateException("NodeServices の実装（core.dom.DomNodeServices）が見つかりません（META-INF/services を確認）");
        }

        private Holder() {}
    }
}
