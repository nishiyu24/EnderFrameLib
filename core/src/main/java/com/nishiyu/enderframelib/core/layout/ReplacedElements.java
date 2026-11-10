package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.ReplacedKind;
import com.nishiyu.enderframelib.core.dom.CustomElementPainter;
import com.nishiyu.enderframelib.core.dom.HostElementProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 置換要素（replaced element）のタグ台帳。レイアウト・描画の各所に散らばっていた
 * ホスト固有タグの直書きを 1 か所へ集約する。
 *
 * <p>HTML 標準の置換要素（img・canvas・svg・フォーム部品など）は固定、ホストが持ち込む置換要素
 * （Minecraft なら mc-fluid 等）は {@link #register} で登録する。エンジン本体は具体的なタグ名を知らない。
 * 読み取りは不変スナップショットの参照 1 回（ロック・確保なし）。
 */
public final class ReplacedElements {
    private static volatile Map<String, CustomElementPainter> host = defaults();

    private ReplacedElements() {}

    /** 性質だけを持つ最小の差し込み（参照先の書き換え・寸法は既定）。 */
    private record Plain(ReplacedKind kind) implements CustomElementPainter {}

    /** {@link HostElementProvider}（ServiceLoader）が宣言した置換要素。何も無ければ空（エンジンは具体名を持たない）。 */
    private static Map<String, CustomElementPainter> defaults() {
        Map<String, CustomElementPainter> m = new HashMap<>();
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

    /** ホスト置換要素を性質だけで登録する（同名は上書き）。 */
    public static void register(String tag, ReplacedKind kind) {
        if (kind == null) throw new IllegalArgumentException("tag/kind");
        register(tag, new Plain(kind));
    }

    /** ホスト置換要素を採寸・描画の差し込み付きで登録する（同名は上書き）。 */
    public static synchronized void register(String tag, CustomElementPainter painter) {
        if (tag == null || tag.isEmpty() || painter == null || painter.kind() == null) {
            throw new IllegalArgumentException("tag/painter");
        }
        Map<String, CustomElementPainter> m = new HashMap<>(host);
        m.put(tag, painter);
        host = Map.copyOf(m);
    }

    /** 登録された差し込み。無ければ null。 */
    public static CustomElementPainter painterOf(String tag) {
        return host.get(tag);
    }

    /** プロバイダ（SPI）の宣言だけの状態へ戻す。テストの後始末用。 */
    public static synchronized void resetToDefaults() {
        host = defaults();
    }

    /** ホストが登録した置換要素か。 */
    public static boolean isHost(String tag) {
        return host.containsKey(tag);
    }

    /** ホスト置換要素のうち画像として採寸されるもの。 */
    public static boolean isHostImageLike(String tag) {
        CustomElementPainter p = host.get(tag);
        return p != null && p.kind() == ReplacedKind.IMAGE_LIKE;
    }

    public static boolean isHostScene(String tag) {
        CustomElementPainter p = host.get(tag);
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
