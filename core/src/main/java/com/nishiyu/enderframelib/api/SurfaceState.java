package com.nishiyu.enderframelib.api;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * 面のリアクティブな状態。文書の {@code {{key}}} / {@code data-if} / {@code data-for} / {@code data-bind} などが読む値で、
 * 変えると次の {@link WebViewSurface#frame} までに DOM へ反映される。値は文字列として保持する。
 */
@ApiStatus.AvailableSince("1.0")
public interface SurfaceState {

    /** 値を設定する（変わったときだけ反映・通知される）。{@code null} は空文字。 */
    SurfaceState set(String key, Object value);

    /** 値。未設定なら空文字。 */
    String get(String key);

    /** リスト（{@code data-for} / {@code data-list} の行）。 */
    SurfaceState setList(String key, List<Map<String, String>> rows);

    /** 値の変化を購読する（新しい値が渡る）。 */
    SurfaceState watch(String key, Consumer<String> listener);

    /** 値を供給元から引く（同期のたびに読み直される）。 */
    SurfaceState bind(String key, Supplier<?> supplier);

    /** 値が変わっていなくても、全体を当て直させる。 */
    void forceSync();
}
