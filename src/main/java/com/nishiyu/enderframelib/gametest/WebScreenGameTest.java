package com.nishiyu.enderframelib.gametest;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.EnderFrameAPI;
import com.nishiyu.enderframelib.api.WebDocument;
import com.nishiyu.enderframelib.api.WebSessionHandle;
import com.nishiyu.enderframelib.server.EnderFrameServerState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * サーバー側のセッション寿命のゲームテスト。
 *
 * <p>{@code build.gradle} は 3 つの run 設定で {@code enabledGameTestNamespaces} を
 * 有効にしていたが、中身が 0 件だった。ここで検証するのは
 * 「画面を開く → サーバー側に文書が立つ → プレイヤーが落ちる → static マップが空に戻る」
 * という I-6（リーク）の修正そのもので、ユニットテストでは Minecraft が要るため届かず、
 * 手動確認では「空に戻ったこと」を見落としやすかった部分。</p>
 *
 * <p>描画そのものはクライアントが要るのでここでは検証できない（{@code runGameTestServer}
 * は専用サーバー）。サーバー側で確認できる「描画の手前」として、文書が実際に
 * パースされ、要素が id で引けることを見る。</p>
 *
 * <p>実行:</p>
 * <pre>./gradlew runGameTestServer</pre>
 */
@GameTestHolder(EnderFrameLib.MODID)
@PrefixGameTestTemplate(false)
public final class WebScreenGameTest {

    /** テストが置かれる 3x1x3 の石床。中身は使わないが、ゲームテストは構造物を要求する。 */
    private static final String TEMPLATE = "empty_floor";

    // 同一バッチ内のテストは並列に走り、サーバー側の static 状態を共有してしまう。
    // ここで見たいのは「状態が空に戻るか」なので、1 テスト 1 バッチにして直列化する。
    // バッチはバッチ単位で順に実行されるため、前のテストの取りこぼしは
    // 次のテストの開始前チェックで検出される。
    private static final String BATCH_LIFECYCLE = "enderframe_lifecycle";
    private static final String BATCH_DOCUMENT = "enderframe_document";

    private WebScreenGameTest() {}

    /**
     * 画面を開くとセッションが立ち、ログアウトで全ての static 状態が空に戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_LIFECYCLE, timeoutTicks = 200)
    public static void screenSessionIsDroppedOnLogout(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");

        ServerPlayer player = mockPlayer(helper);
        String screenId = "script_" + player.getStringUUID();
        String hudId = "custom_hud_" + player.getStringUUID();

        EnderFrameAPI api = EnderFrameAPI.get();
        api.openScreen(player, null, "<div id='root'>hello</div>", 320, 200);
        api.setHud(player, null, "<div id='hud'>hud</div>");

        if (api.findSession(screenId) == null) {
            helper.fail("openScreen 後にセッションが見つかりません: " + screenId);
        }
        if (api.findSession(hudId) == null) {
            helper.fail("setHud 後に HUD セッションが見つかりません: " + hudId);
        }
        if (EnderFrameServerState.isEmpty()) {
            helper.fail("セッションを 2 つ開いたのに状態が空と報告されました: "
                    + EnderFrameServerState.describeState());
        }

        // PlayerLoggedOutEvent のリスナが呼ぶのと同じ経路。イベントを合成すると
        // 他の Mod のリスナまで巻き込むので、破棄の契約だけを直接叩く。
        EnderFrameServerState.onPlayerLogout(player);
        player.connection.disconnect(Component.literal("gametest done"));

        if (api.findSession(screenId) != null) {
            helper.fail("ログアウト後もセッションが残っています: " + screenId);
        }
        if (api.findSession(hudId) != null) {
            helper.fail("ログアウト後も HUD セッションが残っています: " + hudId);
        }
        requireCleanState(helper, "ログアウト後");

        helper.succeed();
    }

    /**
     * サーバー側で HTML が実際に文書になり、id で要素が引けて、patch が通る。
     * 描画の一歩手前までをここで押さえる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_DOCUMENT, timeoutTicks = 200)
    public static void screenHtmlBecomesADocument(GameTestHelper helper) {
        requireCleanState(helper, "テスト開始前");

        ServerPlayer player = mockPlayer(helper);
        EnderFrameAPI api = EnderFrameAPI.get();
        api.openScreen(player, null, """
                <style>#count { color: #ff0000; }</style>
                <p>Diamonds: <span id="count" class="value">0</span></p>
                """, 320, 200);

        WebSessionHandle session = api.findSession("script_" + player.getStringUUID());
        if (session == null) {
            helper.fail("openScreen 後にセッションが見つかりません");
            return;
        }

        WebDocument document = session.getDocument();
        if (!document.hasElement("count")) {
            helper.fail("id=count の要素が文書にありません（HTML がパースされていない）");
        }
        if (!"span".equals(document.tagOf("count"))) {
            helper.fail("id=count のタグが span ではありません: " + document.tagOf("count"));
        }
        if (!"value".equals(document.attributeOf("count", "class"))) {
            helper.fail("id=count の class が拾えていません: " + document.attributeOf("count", "class"));
        }
        if (!document.authorCss().contains("#count")) {
            helper.fail("インライン <style> が作者 CSS に取り込まれていません");
        }

        // 文書が差分更新できること。ここで落ちると patch 経路が壊れている。
        session.patchText("count", "42");
        session.patchAttribute("count", "data-ready", "1", false);
        if (!"1".equals(session.getDocument().attributeOf("count", "data-ready"))) {
            helper.fail("patchAttribute が文書に反映されていません");
        }

        EnderFrameServerState.onPlayerLogout(player);
        player.connection.disconnect(Component.literal("gametest done"));
        requireCleanState(helper, "ログアウト後");

        helper.succeed();
    }

    /**
     * 擬似プレイヤーを 1 人作ってプレイヤーリストに入れる。
     * 専用サーバーのゲームテストには本物の接続が無いため、これが唯一の手段。
     */
    @SuppressWarnings({"deprecation", "removal"}) // makeMockServerPlayerInLevel は 1.21.1 時点でこれしかない
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /**
     * static 状態が空であることを要求する。
     * 前のテストの取りこぼしを次のテストの失敗として報告しないため、前後の両方で見る。
     */
    private static void requireCleanState(GameTestHelper helper, String whenDescription) {
        if (!EnderFrameServerState.isEmpty()) {
            helper.fail(whenDescription + "のサーバー状態が空ではありません: "
                    + EnderFrameServerState.describeState());
        }
    }
}
