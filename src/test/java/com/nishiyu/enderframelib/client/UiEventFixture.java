package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 入力経路のテスト用の足場。文書を解析・レイアウト・描画してセッションに載せ、
 * 実際のヒットテストを通して押下・解放・ホバーを駆動し、DOM のリスナーとアクション名の呼び出しを
 * 同じ順序つきのログに積む。{@link #close()} でグローバルな設定とセッションを元に戻す。
 */
public final class UiEventFixture implements AutoCloseable {

    public static final int MC_LEFT = 0;
    public static final int MC_RIGHT = 1;
    public static final int MC_MIDDLE = 2;

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;

    private final String sessionId;
    private final List<String> log = new ArrayList<>();
    private final ClientScreenInteractionManager.IClientEventCallback actionRecorder =
            (session, elementId, eventName, actionName, value) -> {
                log.add("action:" + eventName + "=" + actionName + "@" + elementId + "[" + value + "]");
                return true;
            };
    private UiDocumentCompiler.Document document;
    private List<DisplayItem> elements = List.of();

    public UiEventFixture(String sessionId) {
        this.sessionId = sessionId;
        ClientScreenInteractionManager.setClientEventCallback(actionRecorder);
        // 効果音は Minecraft のサウンドマネージャを要るので、ヘッドレスのテストでは鳴らさない。
        ClientScreenInteractionManager.setEnableDefaultUiSounds(false);
    }

    public String sessionId() {
        return sessionId;
    }

    public List<String> log() {
        return log;
    }

    public UiDocumentCompiler.Document document() {
        return document;
    }

    public void load(String bodyHtml) {
        String html = "<html><body style=\"margin:0\">" + bodyHtml + "</body></html>";
        UiDocumentCompiler parser = new UiDocumentCompiler();
        document = parser.parseDocument(html, "", WIDTH, HEIGHT);
        var rootBox = parser.layoutDocument(document, WIDTH, HEIGHT, Map.of(), new ScrollState(),
                FontFace.FontResolver.FALLBACK, new InteractionState());
        elements = new DisplayListBuilder(WIDTH, HEIGHT, document.sheet.keyframes, new ScrollState()).render(rootBox);
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        session.setDocument(document);
        session.setRenderCache(elements);
    }

    public HtmlNode node(String id) {
        HtmlNode found = document.root.getElementById(id);
        assertNotNull(found, "要素 #" + id + " が文書にあること");
        return found;
    }

    /** 基底 ID が {@code id} の最大の描画要素の中心。 */
    public double[] center(String id) {
        DisplayItem element = elements.stream()
                .filter(e -> id.equals(e.baseId()))
                .max((a, b) -> Float.compare(a.width() * a.height(), b.width() * b.height()))
                .orElseThrow(() -> new AssertionError("描画要素 #" + id + " が無い"));
        return new double[]{element.x() + element.width() / 2.0, element.y() + element.height() / 2.0};
    }

    /** {@code id} の左上から内側へ {@code inset} だけ入った点。子要素に覆われない余白の位置を指す。 */
    public double[] insetCorner(String id, double inset) {
        DisplayItem element = elements.stream()
                .filter(e -> id.equals(e.baseId()))
                .max((a, b) -> Float.compare(a.width() * a.height(), b.width() * b.height()))
                .orElseThrow(() -> new AssertionError("描画要素 #" + id + " が無い"));
        return new double[]{element.x() + inset, element.y() + inset};
    }

    /** 各イベント種別のリスナーを付け、発火のたびに {@code name:type} をログへ積む。 */
    public void record(HtmlNode target, String name, String... types) {
        for (String type : types) {
            target.addEventListener(type, (Event event) -> log.add(name + ":" + type));
        }
    }

    public void hover(String id) {
        hoverAt(center(id));
    }

    public void hoverAt(double[] point) {
        ClientScreenInteractionManager.handleHover(sessionId, point[0], point[1]);
    }

    /** どの要素の上にもない位置（文書の外）へポインタを動かす。 */
    public void hoverOutside() {
        ClientScreenInteractionManager.handleHover(sessionId, -1, -1);
    }

    public void press(String id, int button) {
        double[] point = center(id);
        hoverAt(point);
        ClientScreenInteractionManager.handleClick(sessionId, point[0], point[1], button);
    }

    public void release(String id, int button) {
        double[] point = center(id);
        hoverAt(point);
        ClientScreenInteractionManager.handleRelease(sessionId, point[0], point[1], button, true);
    }

    public void click(String id, int button) {
        press(id, button);
        release(id, button);
    }

    @Override
    public void close() {
        ClientScreenInteractionManager.removeClientEventCallback(actionRecorder);
        ClientScreenInteractionManager.setEnableDefaultUiSounds(true);
        ClientWebManager.clearSession(sessionId);
    }
}