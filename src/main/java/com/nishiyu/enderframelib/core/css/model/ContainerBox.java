package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import java.util.List;
import java.util.Map;

/**
 * 問い合わせコンテナの寸法・名前・カスタムプロパティの算出値。
 * 寸法の問い合わせに答えられるのは {@code container-type} が {@code normal} でない要素で、寸法はレイアウトの結果
 * （内容ボックス、px。測っていなければ NaN）。{@code style()} の問い合わせにはどの要素も答えられる。
 */
public record ContainerBox(float width, float height, ContainerType type, List<String> names,
                           Map<String, String> customProperties) {

    /** コンテナが無いときの寸法（幅も高さも NaN）。{@code cqw} などの単位はビューポートに戻る。 */
    public static final ContainerBox NONE_SIZE =
            new ContainerBox(Float.NaN, Float.NaN, ContainerType.NORMAL, List.of(), Map.of());

    /** 寸法の問い合わせ（{@code @container (min-width: …)}）に答えられるコンテナか。 */
    public boolean isSizeContainer() {
        return type != ContainerType.NORMAL;
    }

    public boolean hasName(String name) {
        return name.isEmpty() || names.contains(name);
    }
}
