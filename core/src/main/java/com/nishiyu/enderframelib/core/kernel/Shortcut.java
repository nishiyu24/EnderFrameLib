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

    /** 切り分け用: 前回の箱が無い（新品の箱の）レイアウトでも、同じパスの中の同一入力の測定を使い回す。 （以前の {@code RenderPipeline.FRESH_LAYOUT_PASS_REUSE}） */
    FRESH_LAYOUT_PASS_REUSE(EngineFlags.FRESH_LAYOUT_PASS_REUSE),

    /** 切り分け用: {@code -Denderframe.layout.noresizeskip=true} で、幅変更でも毎回スタイルと箱の木を組み直す。 （以前の {@code RenderPipeline.RESIZE_SKIP_REBUILD}） */
    RESIZE_SKIP_REBUILD(EngineFlags.RESIZE_SKIP_REBUILD),

    /** 切り分け用: false ならテキストの節も要素と同じ経路（カスケード＋算出）で解く（結果は同じになるはず）。 （以前の {@code RenderPipeline.TEXT_STYLE_FROM_PARENT}） */
    TEXT_STYLE_FROM_PARENT(EngineFlags.TEXT_STYLE_FROM_PARENT),

    /** TEXT_PATCH を使うか。false なら文字の変更も全体の再レイアウトにする（切り分け・比較用）。 （以前の {@code UiView.textPatchEnabled}） */
    TEXT_PATCH(true),

    /** 切り分け用: false なら描画だけの属性（progress / meter の value など）も全体の再レイアウトにする。 （以前の {@code UiView.attrPatchEnabled}） */
    ATTR_PATCH(true),

    /** 切り分け用: false なら文字の局所パッチでも変更印を使わない。 （以前の {@code UiView.changeStampsEnabled}） */

    /** スクロールだけの更新で箱の位置の決め直しを後回しにするか（切り分け用。見た目は変わらない）。 （以前の {@code UiView.deferScrollLayout}） */
    DEFER_SCROLL_LAYOUT(true),

    /** 文字の変更が再スタイルと同じフレームに重なったとき、文字を局所パッチで済ませてから再スタイルを進めるか（切り分け用）。 （以前の {@code UiView.combinedTextPatchEnabled}） */
    COMBINED_TEXT_PATCH(EngineFlags.COMBINED_TEXT_PATCH),

    /** false なら子の増減は常に全体の再レイアウトにする（切り分け用。結果は同じでなければならない）。 （以前の {@code UiView.childPatchEnabled}） */
    CHILD_PATCH(EngineFlags.CHILD_PATCH),

    /** 切り分け用: false なら算出スタイルを共有しない（要素ごとに別のインスタンス）。 （以前の {@code ComputedStyle.SHARE_ENABLED}） */
    STYLE_SHARE(EngineFlags.STYLE_SHARE),

    /** {@link #containsFloats} の結果を箱ごとにメモするか（既定は有効）。切り分け用: {@code -Denderframe.layout.nofloatmemo=true}。 無効にすると従来どおり毎回子孫を走査する（結果は同じ）。 （以前の {@code BlockLayoutEngine.floatMemoEnabled}） */
    FLOAT_MEMO(EngineFlags.FLOAT_MEMO),

    /** 検証モード: メモを使える場面でも走査し直し、メモと食い違えば {@link AssertionError}（無効化の付け漏れの検出）。 {@code -Denderframe.verify.memo=true}（{@code core/build.gradle} の {@code test} で常時有効）。 （以前の {@code BlockLayoutEngine.verifyFloatMemo}） */
    VERIFY_MEMO(EngineFlags.VERIFY_MEMO),

    /** 検証モード: 項目を使い回す箱を、記憶を使わずに作り直して比べる（{@code OwnItemsVerifier}。{@code -Denderframe.verify.boxmemo=true}、{@code core/build.gradle} の {@code test} で有効）。再描画の費用が大きいので {@link #VERIFY_MEMO} と分けてある（計画の §6-124）。 */
    VERIFY_BOX_MEMO(EngineFlags.VERIFY_BOX_MEMO),

    /** 切り分け用: false なら、幅が同じでも shrink-to-fit の項目を必ず測り直す（従来の動き）。 （以前の {@code FlexLayoutEngine.skipSameWidthRemeasure}） */
    FLEX_SKIP_SAME_WIDTH(EngineFlags.FLEX_SKIP_SAME_WIDTH),

    /** 切り分け用: false なら、高さの % の基準が違う制約は常に別の入力として扱う（従来の動き）。 （以前の {@code LayoutBox.blockPercentFreeEnabled}） */
    BLOCK_PERCENT_FREE(EngineFlags.BLOCK_PERCENT_FREE),

    /** 親が寸法を決めた葉の確定測定を別枠で使い回す（{@link TentativeKind#DEFINITE}）。切り分け: {@code -Denderframe.layout.nodefiniteleaf=true}。 （以前の {@code LayoutBox.definiteLeafReuse}） */
    DEFINITE_LEAF_REUSE(EngineFlags.DEFINITE_LEAF_REUSE),

    /** 切り分け用: false なら仮の測定を常に本当に測る。 （以前の {@code LayoutBox.tentativeReuse}） */
    TENTATIVE_REUSE(EngineFlags.TENTATIVE_REUSE),

    /** 切り分け用: false なら外寸が変わらない子の変化を親で吸収しない（従来の祖先まで無効にする動き）。 （以前の {@code LayoutBox.cutoffEnabled}） */
    CUTOFF(EngineFlags.CUTOFF),

    /** 切り分け用: false なら 2 回目の measure パスで、1 回目に測った箱も測り直す（従来の動き）。 （以前の {@code LayoutBox.samePassReuse}） */
    SAME_PASS_REUSE(EngineFlags.SAME_PASS_REUSE),

    /** 幅だけが変わった（親が渡す使える幅・ビューポートの幅）ときに、前回の測定を使い回してよいか。箱自身の横の寸法が確定した長さで （{@link com.nishiyu.enderframelib.core.css.StyleFacts#horizontalFixed}）、子孫に箱の外の寸法（ビューポート／コンテナ単位・絶対配置）を 読むものが無い（{@link #measuredWidthFree}）なら、測定の結果は幅に依らない。 切り分け用: {@code -Denderframe.layout.nowidthreuse=true} で無効。 （以前の {@code LayoutBox.widthIndependentReuse}） */
    WIDTH_INDEPENDENT_REUSE(EngineFlags.WIDTH_INDEPENDENT_REUSE),

    /** ビューポートが前回の測定と同じとみなせるか。部分木が箱の外の寸法（ビューポート単位・絶対配置）を読まない （{@link #measuredOutsideFree}）なら、ビューポートの幅は測定の結果に効かない（ウィンドウの幅変更で、制約が同じ箱を測り直さない）。 高さと解像度比は、パーセントの高さ・画像の候補選びに効くので比べる。 切り分け用: {@code -Denderframe.layout.noviewportwidthfree=true} で無効。 （以前の {@code LayoutBox.viewportWidthFreeReuse}） */
    VIEWPORT_WIDTH_FREE_REUSE(EngineFlags.VIEWPORT_WIDTH_FREE_REUSE),

    /** 内容で寸法が決まる箱（{@code width: auto} の shrink-to-fit と、折り返さない 1 行の文字）の fit 再利用。 親が渡す使える幅が {@link #measuredFitNeed} 以上なら、前回の幅が何であっても測定の結果は同じ （使える幅に届かず折り返す・縮められる箱だけが、使える幅に依る）。Blink の LayoutNG が fit-content の結果を 使い回すのと同じ考え方で、flex の項目・インラインブロック・span のように、幅が 1px 変わっても中身が収まっている箱を 測り直さない。切り分け用: {@code -Denderframe.layout.nofitreuse=true} で無効。 （以前の {@code LayoutBox.fitReuseEnabled}） */
    FIT_REUSE(EngineFlags.FIT_REUSE),

    /** {@code -Denderframe.layout.noparallel=true} で並列実行を無効化する切り替え。 （以前の {@code LayoutTaskPool.parallelEnabled}） */
    LAYOUT_PARALLEL(EngineFlags.LAYOUT_PARALLEL),

    /** 切り分け用: false なら、大きい文書でも窓付きの表示リストにしない（従来の動き）。 （以前の {@code DisplayListBuilder.WINDOWED_ENABLED}） */
    WINDOWED(EngineFlags.WINDOWED),

    /** スクロールコンテナの中身を 1 つの節にまとめるか。まとめると、スクロールだけの更新は表示リストを作り直さず、その節の 移動量を更新するだけで済む（{@code UiView} が行う）。false なら従来どおり、中身の項目はコンテナの節の直下に並び、 スクロールのたびに作り直す（疑うときの切り分け用。{@code -Denderframe.paint.noscrollnode=true} でも切れる）。結果は同じでなければならない。 （以前の {@code DisplayListBuilder.scrollContentNodes}） */
    SCROLL_CONTENT_NODES(EngineFlags.SCROLL_CONTENT_NODES),


    /** 診断・切り分け用フラグ。 （以前の {@code GpuRenderBackend.rectTrees}） */
    GL_RECT_TREES(EngineFlags.GL_RECT_TREES),

    /** （以前の {@code GpuRenderBackend.boundedLayers}） */
    GL_BOUNDED_LAYERS(EngineFlags.GL_BOUNDED_LAYERS),

    /** （以前の {@code GpuRenderBackend.downsampledBlur}） */
    GL_DOWNSAMPLED_BLUR(EngineFlags.GL_DOWNSAMPLED_BLUR),

    /** （以前の {@code GpuRenderBackend.retainedScroll}） */
    GL_RETAINED_SCROLL(EngineFlags.GL_RETAINED_SCROLL),

    /** 損傷範囲による部分描画を使うか（{@code -Denderframe.gpu.nodamage=true} で無効）。 （以前の {@code GpuRenderBackend.damageEnabled}） */

    /** 再生を使うか（切り分け用: {@code -Denderframe.gl.noreplay=true} で無効）。 （以前の {@code RectStream.replayEnabled}） */
    GL_REPLAY(EngineFlags.GL_REPLAY),

    /** 切り分け用（テストが切り替える）: false なら sticky を含むコンテナは従来どおり節にまとめない（作り直す）。 （以前の {@code PaintOrder.STICKY_IN_SCROLL_NODE}） */
    STICKY_IN_SCROLL_NODE(true),

    /** 姿勢が動いていない項目の姿勢を、前のフレームのものから使い回すか。自分の変形・不透明度が恒等の葉の項目は、親の姿勢の実体を そのまま使う（値は親と同じ。動く節の下の全項目の作り直しを避ける。B12）。false なら毎フレーム項目ごとに作り直す （疑うときの切り分け用。絵は同じでなければならない）。 （以前の {@code PaintSceneDriver.reuseIdlePoses}） */
    REUSE_IDLE_POSES(true),

    /** 記録する保持層の範囲のうち、描かれないと確実に言える先頭・末尾の区間を走査から外すか。false なら全項目を走査する （疑うときの切り分け用。絵は同じでなければならない）。 （以前の {@code PaintSceneDriver.cullRecordedRanges}） */
    CULL_RECORDED_RANGES(EngineFlags.CULL_RECORDED_RANGES),

    /** スクロール層の保持を描画側へ問い合わせるか。false なら問い合わせず、毎フレーム全部走査する（疑うときの切り分け用。絵は同じでなければならない）。 （以前の {@code PaintSceneDriver.retainScrollLayers}） */
    RETAIN_SCROLL_LAYERS(true),

    /** 切り分け用: false なら、一般のリストの変化は常に索引の作り直しにする（従来の動き）。 （以前の {@code PaintSceneDriver.patchDiffIndex}） */

    /** 変更のあった項目の範囲だけを塗り直すか（{@code -Denderframe.soft.nodamage=true} で無効）。 無効にすると毎フレーム全面をクリアして描く（疑うときの切り分け用。絵は同じでなければならない）。 （以前の {@code SoftwareRenderBackend.damageEnabled}） */
    SOFT_DAMAGE(EngineFlags.SOFT_DAMAGE),

    /** （以前の {@code SoftwareRenderBackend.CLIP_SHARE}） */
    CLIP_SHARE(true),

    /** 照合・切り分け用: false なら、クリップの外にある項目も塗りの経路へ渡す（結果の画素は同じはず）。 （以前の {@code SoftwareRenderBackend.cullOffscreen}） */
    SOFT_CULL_OFFSCREEN(EngineFlags.SOFT_CULL_OFFSCREEN),

    /** 項目単位の再生を使うか（切り分け用: {@code -Denderframe.paint.noreplay=true} で無効）。 （以前の {@code SoftwareRenderBackend.glReplay}） */
    SOFT_GL_REPLAY(EngineFlags.SOFT_GL_REPLAY),

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
