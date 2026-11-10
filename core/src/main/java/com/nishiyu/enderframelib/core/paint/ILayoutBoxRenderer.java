package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.List;
import java.util.Map;

/**
 * 箱の木から表示リストを作る関数。ホスト（Minecraft など）が差し替えられるよう、エンジンの文脈
 * （{@link com.nishiyu.enderframelib.core.EngineContext#boxRenderer}）に差し込む。
 */
@FunctionalInterface
public interface ILayoutBoxRenderer {

    /**
     * 既定は {@link DisplayListBuilder}。Minecraft 無しでも補間前の表示リストまで組める。
     * 呼び出しごとに新しいビルダーを作るので、箱の記憶は効かない（ビューが既定のときだけ自前のビルダーを持ち回す）。
     */
    ILayoutBoxRenderer DEFAULT = (rootBox, rootW, rootH, keyframes, scrollState) ->
            new DisplayListBuilder(rootW, rootH, keyframes, scrollState).render(rootBox);

    List<DisplayItem> render(LayoutBox rootBox, int rootW, int rootH,
                             Map<String, Keyframes> keyframes,
                             ScrollState scrollState);
}
