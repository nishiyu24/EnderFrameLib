package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 部分木の所有者。同じ DOM をクライアントのコードとサーバーの変異レコードの両方が触れるので、
 * 衝突したときにどちらが正かを部分木ごとに決める。
 *
 * <ul>
 *   <li>{@link #SERVER}: サーバーが正。クライアントのコードは書けるが、次のサーバー変異（と再同期）で上書きされる。</li>
 *   <li>{@link #CLIENT}: クライアントが正。サーバー変異は届いても適用されず、再同期でも作り直されない。</li>
 * </ul>
 *
 * <p>要素の {@code data-owner="server|client"} 属性で宣言し、子孫へ継承される。
 * 属性が無い部分木は、文書の既定（{@link HtmlDocument#getDefaultOwnership()}）に従う。
 * サーバーと同期している文書は {@link #SERVER}、クライアントだけで開いた文書は {@link #CLIENT} が既定。</p>
 */
public enum Ownership {
    SERVER,
    CLIENT;

    /** 所有者を宣言する属性名。 */
    public static final String ATTRIBUTE = "data-owner";

    /** 属性値（大文字小文字は問わない）から所有者を引く。未知の値や null は null。 */
    @Nullable
    public static Ownership parse(@Nullable String attributeValue) {
        if (attributeValue == null) return null;
        return switch (attributeValue.trim().toLowerCase(Locale.ROOT)) {
            case "server" -> SERVER;
            case "client" -> CLIENT;
            default -> null;
        };
    }
}
