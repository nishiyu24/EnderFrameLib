package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.paint.model.ElementType;

import java.util.ArrayList;
import java.util.List;

/**
 * シーンにある動画（{@code <video>}・{@code <audio>}・遠くの GIF）の一覧（M3b-4c-2b-2b）。ホストのメディアの管理（MOD の
 * {@code WebMediaManager}）が毎ティック読む。表示項目の列を組まずに、層の描画命令から種類と中身の指定を読む（以前はビューの列を走査していた）。
 */
public final class SceneMedia {
    private SceneMedia() {}

    /** シーン {@code scene} の動画の命令（描く順ではない。無ければ空）。 */
    public static List<SceneVideo> videos(DisplayScene scene) {
        if (scene == null) return List.of();
        List<SceneVideo> out = null;
        DrawOps.Cursor c = null;
        for (SceneLayer layer : scene.byNodeId().values()) {
            String tag = layer.node != null ? layer.node.tag : null;
            DrawOps ops = layer.ops();
            if (tag == null || ops == null || ops.count() == 0 || !(ElementTraits.isMediaPlayer(tag) || ElementTraits.isImage(tag))) continue;
            if (c == null) c = new DrawOps.Cursor();
            c.reset(ops);
            while (c.next()) {
                if (c.type() != ElementType.VIDEO || c.text() == null) continue;
                var ev = c.events();
                if (out == null) out = new ArrayList<>(2);
                out.add(new SceneVideo(c.text(), ev != null && ev.containsKey("media-loop"), ev != null && ev.containsKey("media-muted")));
            }
        }
        return out != null ? out : List.of();
    }
}
