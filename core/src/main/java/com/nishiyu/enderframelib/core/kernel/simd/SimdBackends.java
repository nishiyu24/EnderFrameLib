package com.nishiyu.enderframelib.core.kernel.simd;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 使う {@link SimdBackend} を選ぶ。{@link ServiceLoader} で見つかった利用可能なプラグインのうち
 * 最優先のものを使い、無ければ（または読み込みに失敗すれば）{@link ScalarSimd}。
 * プラグインの失敗は握りつぶして既定へ戻す（JVM フラグが無い環境でも必ず動く）。
 */
public final class SimdBackends {
    private static volatile SimdBackend current;

    private SimdBackends() {}

    public static SimdBackend current() {
        SimdBackend c = current;
        if (c == null) {
            c = choose(discover());
            current = c;
        }
        return c;
    }

    /** 候補から、利用可能で最優先のものを選ぶ。無ければ既定実装。 */
    public static SimdBackend choose(List<? extends SimdBackend> candidates) {
        SimdBackend best = ScalarSimd.INSTANCE;
        for (SimdBackend b : candidates) {
            boolean ok;
            try {
                ok = b.isAvailable();
            } catch (Throwable t) {
                ok = false;
            }
            if (ok && b.priority() > best.priority()) best = b;
        }
        return best;
    }

    private static List<SimdBackend> discover() {
        List<SimdBackend> found = new ArrayList<>();
        try {
            var it = ServiceLoader.load(SimdBackend.class, SimdBackends.class.getClassLoader()).iterator();
            while (true) {
                try {
                    if (!it.hasNext()) break;
                    found.add(it.next());
                } catch (Throwable t) { // ServiceConfigurationError / NoClassDefFoundError（モジュール欠如）
                    // 壊れたプラグインは飛ばす
                }
            }
        } catch (Throwable ignored) {
            // 発見自体の失敗は既定実装へ
        }
        return found;
    }
}
