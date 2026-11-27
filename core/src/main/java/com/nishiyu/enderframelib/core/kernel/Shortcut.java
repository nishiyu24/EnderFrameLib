package com.nishiyu.enderframelib.core.kernel;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 近道・切り分け・検証の切り替え（ENGINE_REARCHITECTURE_PLAN S1 ③）。以前は各クラスの {@code static volatile boolean} の欄 51 個で、
 * テストがそれぞれを直接書き換えていた。ここに 1 つの表としてまとめ、読みは {@link #on()}、切り替えは {@link #set} か
 * 範囲つきの {@link #with}（終わったら元へ戻す）にする。
 *
 * <p>状態は 1 つの不変の配列で、切り替えは配列ごと差し替える（読む側は 1 回の volatile 読みで、途中の状態を見ない）。初期値は
 * {@link EngineFlags}（起動時のシステムプロパティ）。プロセス全体で 1 つなので、面ごとに変えるのは A13（文脈を面が持つ）で行う。
 * 近道の切り替えは結果を変えてはならない（{@code :core:shortcutMatrix}・{@code IncrementalVsFullTest} が見張る）。</p>
 */
public enum Shortcut {
    /** 解析済みのスタイルシートの雛形（UA + ユーザー + 作者の CSS とビューポートの大きさが同じなら同じ結果）。 画面を開き直すたびに UA シート（約 11 KB）と作者の CSS を解析し直さず、雛形の写しを渡す。 切り分け用: {@code -Denderframe.sheetcache.off=true} で無効。 （以前の {@code DocumentLoader.sheetTemplateCache}） */
    SHEET_CACHE(EngineFlags.SHEET_CACHE),

    /** 診断・切り分け用フラグ。 （以前の {@code GpuRenderBackend.rectTrees}） */
    GL_RECT_TREES(EngineFlags.GL_RECT_TREES),

    /** （以前の {@code GpuRenderBackend.boundedLayers}） */
    GL_BOUNDED_LAYERS(EngineFlags.GL_BOUNDED_LAYERS),

    /** （以前の {@code GpuRenderBackend.downsampledBlur}） */
    GL_DOWNSAMPLED_BLUR(EngineFlags.GL_DOWNSAMPLED_BLUR),

    /** （以前の {@code GpuRenderBackend.retainedScroll}） */
    GL_RETAINED_SCROLL(EngineFlags.GL_RETAINED_SCROLL),

    /** （以前の {@code SoftwareRenderBackend.CLIP_SHARE}） */
    CLIP_SHARE(true),

    /** 照合・切り分け用: false なら、クリップの外にある項目も塗りの経路へ渡す（結果の画素は同じはず）。 （以前の {@code SoftwareRenderBackend.cullOffscreen}） */
    SOFT_CULL_OFFSCREEN(EngineFlags.SOFT_CULL_OFFSCREEN),

    /** 照合・切り分け用: false なら {@link #paint} の行ごとの塗りを使わず、全画素を評価する。 （以前の {@code SoftwareRenderBackend.fastSolid}） */
    SOFT_FAST_SOLID(EngineFlags.SOFT_FAST_SOLID),

    /** 変わらない文字項目の下ごしらえを使い回す（切り分け用: {@code -Denderframe.paint.notextsetup=true} で無効）。 （以前の {@code SoftwareRenderBackend.textSetupReuse}） */
    TEXT_SETUP_REUSE(EngineFlags.TEXT_SETUP_REUSE),

    /** タイル単位の遅延実行を使うか（{@code -Denderframe.soft.notiled=true} で無効＝命令ごとにその場で全範囲を実行）。 （以前の {@code TileQueue.tiled}） */
    SOFT_TILED(EngineFlags.SOFT_TILED);

    private final boolean initial;
    private static volatile boolean[] state = initialState();

    Shortcut(boolean initial) {
        this.initial = initial;
    }

    private static boolean[] initialState() {
        Shortcut[] all = values();
        boolean[] s = new boolean[all.length];
        for (Shortcut x : all) s[x.ordinal()] = x.initial;
        return s;
    }

    /** 今の値。 */
    public boolean on() {
        return state[ordinal()];
    }

    /** 起動時の値（{@link EngineFlags} から）。 */
    public boolean initial() {
        return initial;
    }

    /** 値を変える（配列ごと差し替える）。テストと切り分け用。戻すのは呼び出し側の責任（{@link #with} なら自動）。 */
    public void set(boolean value) {
        synchronized (Shortcut.class) {
            if (state[ordinal()] == value) return;
            boolean[] next = state.clone();
            next[ordinal()] = value;
            state = next;
        }
    }

    /** {@code values} の切り替えを当てて {@code body} を走らせ、終わったら（例外でも）全部を前の値へ戻す。 */
    public static <T> T with(Map<Shortcut, Boolean> values, Supplier<T> body) {
        boolean[] saved;
        synchronized (Shortcut.class) {
            saved = state;
            boolean[] next = saved.clone();
            values.forEach((k, v) -> next[k.ordinal()] = v);
            state = next;
        }
        try {
            return body.get();
        } finally {
            synchronized (Shortcut.class) {
                state = saved;
            }
        }
    }

    /** {@link #with(Map, Supplier)} の戻り値の無い形。 */
    public static void with(Map<Shortcut, Boolean> values, Runnable body) {
        with(values, () -> {
            body.run();
            return null;
        });
    }

    /** 全部を起動時の値へ戻す。 */
    public static void resetAll() {
        synchronized (Shortcut.class) {
            state = initialState();
        }
    }
}
