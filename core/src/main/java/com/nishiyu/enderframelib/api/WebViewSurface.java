package com.nishiyu.enderframelib.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * HTML/CSS を「生きた画面」として動かす 1 枚の面（ウィンドウ・オフスクリーン・ヘッドレス・カスタムホストの
 * どこにマウントされていても同じ API）。{@link EnderEngine} で作る。
 *
 * <p>座標はすべて CSS px（文書の左上が原点）、キーは GLFW のキーコード。スレッドセーフではない
 * （1 つのスレッドから使う）。内部の DOM・レイアウト型はこの API に現れない。
 */
@ApiStatus.AvailableSince("1.0")
public interface WebViewSurface extends AutoCloseable {

    /** 面の幅（CSS px）。 */
    int width();

    /** 面の高さ（CSS px）。 */
    int height();

    /** 大きさを変える（次の {@link #frame} で再レイアウトされる）。 */
    void resize(int width, int height);

    /**
     * 時刻 {@code nowMs}（ミリ秒、単調増加）まで進めて、必要なら面へ描く。タイマー・アニメーション・再レイアウトもここで進む。
     *
     * @param force 変化が無くても描き直す
     * @return 描いたか
     */
    boolean frame(long nowMs, boolean force);

    // ---- 入力 ----

    void mouseMove(double x, double y);

    void mouseLeave();

    /** 主ボタン（左）の押下／解放。{@code mouseButton(MouseButton.LEFT, down)} と同じ。 */
    default void mouseButton(boolean down) {
        mouseButton(MouseButton.LEFT, down);
    }

    /**
     * ボタンの押下／解放。左は {@code click}（と {@code dblclick}・フォーカス・テキスト選択）、右は {@code contextmenu}、
     * 右と中は {@code auxclick} を押した要素の上で離したときに DOM へ届く（{@code dom().getElementById(..).addEventListener}）。
     */
    void mouseButton(MouseButton button, boolean down);

    /** テキスト欄が使うクリップボードを差し替える（{@code null} で面ごとのメモリ内へ戻す）。 */
    void setClipboard(@Nullable SurfaceClipboard clipboard);

    /** ホイール。文書が受け取ったら true。 */
    boolean scroll(double notchesX, double notchesY);

    void keyDown(int glfwKey, int mods);

    void keyUp(int glfwKey, int mods);

    /** 文字入力。受け取ったら true。 */
    boolean charTyped(int codePoint);

    /** 点が対話要素の上にあるか。ホストが、クリックを UI に渡すか自分で処理するかを決めるのに使う。 */
    boolean isPointerOverUi(double x, double y);

    /** フォーカス中の要素の id。無ければ null。 */
    @Nullable
    String focusedId();

    /** リンクの遷移要求（{@code href}）を受け取る。 */
    void onNavigate(Consumer<String> handler);

    // ---- ライフサイクル ----

    /** 文書を別の HTML に差し替える（状態 {@link #state()} は保たれ、新しい文書へ当て直される）。 */
    void load(String html);

    /** 変化が無くても次の {@link #frame} で描き直す（外の事情、画像の取得完了など）。 */
    void invalidate();

    /** 次のフレームで進めるべきこと（入力の反映待ち・アニメーション・タイマー）があるか。false なら描き直さなくてよい。 */
    boolean needsFrame();

    /** {@code #id} の要素が見えるよう、囲むスクロール箱を動かす。要素が無ければ false。 */
    boolean scrollToAnchor(String id);

    /** リアクティブな状態。 */
    SurfaceState state();

    /** 生存期間の通知を購読する。 */
    void addLifecycleListener(SurfaceLifecycleListener listener);

    void removeLifecycleListener(SurfaceLifecycleListener listener);

    /** {@link #close} 済みか。 */
    boolean isClosed();

    // ---- タイマー（実時計。{@link #frame} のたびに期限が来たものが実行される） ----

    /** {@code delayMs} ミリ秒後に 1 回実行する。戻り値は取り消し用の id。 */
    int setTimeout(Runnable task, long delayMs);

    boolean clearTimeout(int id);

    /** {@code intervalMs} ミリ秒ごとに実行する。 */
    int setInterval(Runnable task, long intervalMs);

    boolean clearInterval(int id);

    /** 次のフレームの直前に 1 回だけ、フレームの時刻（ミリ秒）つきで呼ぶ。 */
    int requestAnimationFrame(java.util.function.DoubleConsumer callback);

    boolean cancelAnimationFrame(int id);

    // ---- 文書・出力 ----

    /** 読み込まれている文書（簡易な読み取りと、標準 DOM への入口 {@link WebDocument#dom()}）。 */
    WebDocument document();

    /** 標準 DOM の {@code Document}（{@code document().dom()} の近道）。無ければ null。 */
    @Nullable
    default com.nishiyu.enderframelib.api.dom.Document dom() {
        return document().dom();
    }

    /** 直近のフレームの画素（事前乗算の ARGB、左上原点、{@link #pixelWidth()} × {@link #pixelHeight()}）。次の {@link #frame} まで有効。 */
    int[] pixels();

    int pixelWidth();

    int pixelHeight();

    /** 直近のフレームを PNG として書き出す（親のフォルダは作る）。 */
    void writePng(Path file) throws IOException;

    /** 保持している資源を解放する。 */
    @Override
    void close();
}
