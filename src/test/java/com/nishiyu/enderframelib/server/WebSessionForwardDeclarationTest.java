package com.nishiyu.enderframelib.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3: サーバーは、自分が送出した DOM が宣言したアクション名だけを受け付ける。
 * {@code data-forward-action} も、従来の {@code onclick="server:…"} と同じ検証の対象になる。
 */
public class WebSessionForwardDeclarationTest {

    @Test
    void actionsDeclaredByForwardAttributesAndServerSugarAreAccepted() {
        WebSession session = new WebSession("forward-declaration", ignored -> {});
        session.loadHtml("<body>"
                + "<button id=\"buy\" data-forward=\"click\" data-forward-action=\"buy:sword\">buy</button>"
                + "<button id=\"sell\" onclick=\"server:sell\">sell</button>"
                + "<button id=\"local\" onclick=\"close\">close</button>"
                + "</body>");

        assertTrue(session.declaresAction("buy"));
        assertTrue(session.declaresAction("buy:sword"), "name:arg は name 部分だけを見る");
        assertTrue(session.declaresAction("sell"));
        assertFalse(session.declaresAction("close"), "server: の付かないアクションは送出アクションではない");
        assertFalse(session.declaresAction("drop_database"), "送出 DOM に無い名前は拒否する");
    }
}
