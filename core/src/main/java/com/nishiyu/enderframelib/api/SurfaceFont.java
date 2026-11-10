package com.nishiyu.enderframelib.api;

import java.nio.file.Path;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 面に持ち込む書体 1 つ（TTF / OTF）。{@code font-family} にこの名前を書くと、同名の OS の書体より先に使われる。
 * {@code data} か {@code file} のどちらか 1 つを持つ。
 */
@ApiStatus.AvailableSince("1.0")
public record SurfaceFont(String family, byte @Nullable [] data, @Nullable Path file, int weight, boolean italic) {

    public SurfaceFont {
        if (family == null || family.isBlank()) throw new IllegalArgumentException("family");
        if ((data == null) == (file == null)) throw new IllegalArgumentException("exactly one of data / file");
        if (weight < 1 || weight > 1000) throw new IllegalArgumentException("weight must be 1..1000");
    }

    public static SurfaceFont of(String family, byte[] data) {
        return new SurfaceFont(family, java.util.Objects.requireNonNull(data, "data"), null, 400, false);
    }

    public static SurfaceFont of(String family, Path file) {
        return new SurfaceFont(family, null, java.util.Objects.requireNonNull(file, "file"), 400, false);
    }

    /** 太さと斜体を指定した版（同じ名前で太字・斜体の面を足すとき）。 */
    public SurfaceFont withStyle(int weight, boolean italic) {
        return new SurfaceFont(family, data, file, weight, italic);
    }
}
