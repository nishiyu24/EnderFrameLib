package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.ReplacedKind;
import com.nishiyu.enderframelib.core.dom.node.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.node.HostElementProvider;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 置換要素（replaced element）のタグ台帳。レイアウト・描画の各所に散らばっていた
 * ホスト固有タグの直書きを 1 か所へ集約する。
 *
 * <p>HTML 標準の置換要素（img・canvas・svg・フォーム部品など）は固定、ホストが持ち込む置換要素
 * （Minecraft なら mc-fluid 等）は {@code HostServices#withReplacedElement}（文脈）で登録する。エンジン本体は具体的なタグ名を知らない。
 * 読み取りは不変スナップショットの参照 1 回（ロック・確保なし）。
 */
public final class ReplacedElements {
    /** ビルトインの 3D ビューポート {@code <scene-3d>}: 固有サイズ 300×150 の SCENE（幅は親へ広がらない）。 */
    private static final CustomElementPainter SCENE_3D = new CustomElementPainter() {
        @Override public ReplacedKind kind() { return ReplacedKind.SCENE; }
        @Override public boolean sceneFillsWidth() { return false; }
        @Override public int sceneMinWidth() { return Scene3DTags.DEFAULT_WIDTH; }
        @Override public int sceneHeight() { return Scene3DTags.DEFAULT_HEIGHT; }
    };

    /** SPI（ServiceLoader）が宣言した置換要素。起動後は変わらない。 */
    private static final Map<String, CustomElementPainter> SPI = defaults();

    /** 文脈が差し込む置換要素（A13。以前は static の登録表）。null なら無し。 */
    public interface Source {
        Map<String, CustomElementPainter> replacedElements();
    }

    private static volatile java.util.function.Supplier<Source> source = () -> () -> null;

    /** 「このスレッドの文脈の値」を引く口を結ぶ（{@code EngineContext} が 1 回だけ呼ぶ）。 */
    public static void bindSource(java.util.function.Supplier<Source> contextSource) {
        source = contextSource == null ? () -> () -> null : contextSource;
    }

    private static CustomElementPainter lookup(String tag) {
        Map<String, CustomElementPainter> ctx = source.get().replacedElements();
        CustomElementPainter p = ctx == null ? null : ctx.get(tag);
        return p != null ? p : SPI.get(tag);
    }

    private ReplacedElements() {}

    /** 性質だけを持つ最小の差し込み（参照先の書き換え・寸法は既定）。 */
    private record Plain(ReplacedKind kind) implements CustomElementPainter {}

    /**
     * ビルトイン（{@code <scene-3d>}）＋ {@link HostElementProvider}（ServiceLoader）が宣言した置換要素。
     * ホスト固有の具体名（{@code mc-*}）はエンジンが持たない。
     */
    private static Map<String, CustomElementPainter> defaults() {
        Map<String, CustomElementPainter> m = new HashMap<>();
        m.put(Scene3DTags.SCENE, SCENE_3D);
        for (HostElementProvider p : ServiceLoader.load(HostElementProvider.class, HostElementProvider.class.getClassLoader())) {
            for (Map.Entry<String, CustomElementPainter> e : p.replacedElements().entrySet()) {
                CustomElementPainter cp = e.getValue();
                if (e.getKey() == null || e.getKey().isEmpty() || cp == null || cp.kind() == null) {
                    throw new IllegalStateException(p.getClass().getName() + " の置換要素の宣言が不正です: " + e.getKey());
                }
                m.put(e.getKey(), cp);
            }
        }
        return Map.copyOf(m);
    }

    /** 登録された差し込み。無ければ null。 */
    public static CustomElementPainter painterOf(String tag) {
        return lookup(tag);
    }

    /** ホストが登録した置換要素か。 */
    public static boolean isHost(String tag) {
        return lookup(tag) != null;
    }

    /** ホスト置換要素のうち画像として採寸されるもの。 */
    public static boolean isHostImageLike(String tag) {
        CustomElementPainter p = lookup(tag);
        return p != null && p.kind() == ReplacedKind.IMAGE_LIKE;
    }

    public static boolean isHostScene(String tag) {
        CustomElementPainter p = lookup(tag);
        return p != null && p.kind() == ReplacedKind.SCENE;
    }

    /** 置換要素として箱を作るタグか（HTML 標準のフォーム部品・画像類 ＋ ホスト登録分）。 */
    public static boolean isReplacedTag(String tag) {
        return switch (tag) {
            case "img", "canvas", "svg", "input", "select", "button", "progress" -> true;
            default -> isHost(tag);
        };
    }
}
