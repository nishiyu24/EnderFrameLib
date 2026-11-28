package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.RenderFrame;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.SceneSource;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;

/**
 * テスト・ベンチの道具（M3b-4c-2b-3b-2b-3）: ビュー・1 回だけのフレームのシーンから、表示項目の列（親相対。中身を 1 つの節にまとめるスクロール
 * コンテナはスクロール 0 で組む。カリングしない）をその場で組む。本体はこの列を持たず読まない（描画器は準備でシーンから組み、ホストはシーンの問い合わせと
 * 準備の値を読む）。以前の {@code UiView#relativeElements}・{@code FrameRender.Frame#items}・{@code SoftwareRendering.displayList}・
 * {@code UiDocumentCompiler#renderDocument} を置き換えた。{@code DisplayItem} とともに M3b-4c-3 で消す。
 */
public final class TestItems {
    private TestItems() { }

    /** ビュー {@code ui} の直近のシーンの列（シーンが無ければ空。必要なら先に環境変数と描画キューを更新する）。 */
    public static List<DisplayItem> of(UiView ui) {
        RenderFrame f = ui.renderFrame(List.of(), null, 0L);
        return f.scene() != null ? of(f.scene()) : List.of();
    }

    /** スタンドアロンのビュー {@code v} の直近のシーンの列。 */
    public static List<DisplayItem> of(StandaloneView v) {
        return of(v.ui());
    }

    /** 1 回だけ組んだフレーム {@code f} の列（シーンが無ければ空）。 */
    public static List<DisplayItem> of(FrameRender.Frame f) {
        return f.source() != null ? of(f.source()) : List.of();
    }

    /** シーン {@code s}（動的な値・キーフレーム・装置の画素比）から組んだ列。 */
    public static List<DisplayItem> of(SceneSource s) {
        return adapter(s).emitUnscrolled(s.scene(), s.dynamic());
    }

    /**
     * ビュー {@code ui} の列（{@link #of(UiView)}）から画面の上の位置を読むときに足す描画上の平行移動: 利用者の分に、列で中身を 1 つの節にまとめた
     * スクロールコンテナの中身の今のスクロール量での移動を足す（描画器の {@code ScrollTranslation} と同じ。{@code DisplayItem#resolveAbsolute}）。
     */
    public static com.nishiyu.enderframelib.core.paint.model.PaintOffsets offsets(UiView ui) {
        return com.nishiyu.enderframelib.core.paint.ScrollTranslation.apply(ui.paintOffsets(), contents(ui), ui.scrollState());
    }

    /** ビュー {@code ui} の列で中身を 1 つの節にまとめたスクロールコンテナ（シーンが無ければ空）。 */
    public static List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> contents(UiView ui) {
        RenderFrame f = ui.renderFrame(List.of(), null, 0L);
        if (f.scene() == null) return List.of();
        SceneDisplayListAdapter adapter = adapter(f.scene());
        adapter.emitUnscrolled(f.scene().scene(), f.scene().dynamic());
        return adapter.scrollContents();
    }

    private static SceneDisplayListAdapter adapter(SceneSource s) {
        DynamicSceneState dyn = s.dynamic();
        SceneDisplayListAdapter adapter = new SceneDisplayListAdapter(Math.round(dyn.viewportW), Math.round(dyn.viewportH), s.keyframes());
        adapter.setDevicePixelRatio(s.devicePixelRatio());
        return adapter;
    }

    /** HTML をレイアウトして組んだ列（アニメーションの補間前。{@link SoftwareRendering#frame}）。 */
    public static List<DisplayItem> displayList(String html, int width, int height, FontResolver fonts) {
        return of(SoftwareRendering.frame(html, width, height, fonts));
    }

    /** システムフォントで {@link #displayList(String, int, int, FontResolver)}。 */
    public static List<DisplayItem> displayList(String html, int width, int height) {
        return displayList(html, width, height, SystemFonts.system());
    }

    /** 文書を 1 度だけ組んだ列（{@link FrameRender#document}）。 */
    public static List<DisplayItem> document(Document doc, int w, int h, Map<String, String> env, ScrollState scroll, FontResolver fonts) {
        return document(doc, w, h, env, scroll, fonts, new InteractionState());
    }

    public static List<DisplayItem> document(Document doc, int w, int h, Map<String, String> env, ScrollState scroll, FontResolver fonts,
                                             InteractionState state) {
        return of(FrameRender.document(doc, w, h, env, scroll, fonts, state));
    }
}
