package com.nishiyu.enderframelib.core.layout;

import java.util.HashMap;
import java.util.Map;

/**
 * 置換要素（replaced element）のタグ台帳。レイアウト・描画の各所に散らばっていた
 * {@code "mc-fluid" / "mc-entity" / "mc-scene"} の直書きを 1 か所へ集約する。
 *
 * <p>HTML 標準の置換要素（img・canvas・svg・フォーム部品など）は固定、ホストが持ち込む置換要素
 * （Minecraft なら mc-fluid 等）は {@link #register} で登録する。エンジン本体は具体的なタグ名を知らない。
 * 読み取りは不変スナップショットの参照 1 回（ロック・確保なし）。
 */
public final class ReplacedElements {
    /** ホスト置換要素の性質。 */
    public enum Kind {
        /** 画像と同様に固有サイズを持ち、画像として採寸される（流体・エンティティなど）。 */
        IMAGE_LIKE,
        /** 属性だけで描かれ、固有サイズは別途解決される（3D シーンなど）。 */
        SCENE
    }

    private static volatile Map<String, Kind> host = defaults();

    private ReplacedElements() {}

    private static Map<String, Kind> defaults() {
        Map<String, Kind> m = new HashMap<>();
        m.put("mc-fluid", Kind.IMAGE_LIKE);
        m.put("mc-entity", Kind.IMAGE_LIKE);
        m.put("mc-scene", Kind.SCENE);
        return Map.copyOf(m);
    }

    /** ホスト置換要素を登録する（同名は上書き）。 */
    public static synchronized void register(String tag, Kind kind) {
        if (tag == null || tag.isEmpty() || kind == null) throw new IllegalArgumentException("tag/kind");
        Map<String, Kind> m = new HashMap<>(host);
        m.put(tag, kind);
        host = Map.copyOf(m);
    }

    /** 既定（Minecraft 3 種）へ戻す。テストの後始末用。 */
    public static synchronized void resetToDefaults() {
        host = defaults();
    }

    /** ホストが登録した置換要素か。 */
    public static boolean isHost(String tag) {
        return host.containsKey(tag);
    }

    /** ホスト置換要素のうち画像として採寸されるもの。 */
    public static boolean isHostImageLike(String tag) {
        return host.get(tag) == Kind.IMAGE_LIKE;
    }

    public static boolean isHostScene(String tag) {
        return host.get(tag) == Kind.SCENE;
    }

    /** 置換要素として箱を作るタグか（HTML 標準のフォーム部品・画像類 ＋ ホスト登録分）。 */
    public static boolean isReplacedTag(String tag) {
        return switch (tag) {
            case "img", "canvas", "svg", "input", "select", "button", "progress" -> true;
            default -> isHost(tag);
        };
    }
}
