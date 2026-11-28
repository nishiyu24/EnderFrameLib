package com.nishiyu.enderframelib.core.paint.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * シーンの動画の一覧（M3b-4c-2b-2b。MOD のメディアの管理が毎ティック読む）: 表示項目の列を組まずに層の命令から読み、列の動画の項目と
 * 同じ指定・繰り返し・消音を返す。
 */
class SceneMediaTest {
    private static final String HTML = "<html><body style='margin:0'>"
            + "<video src='https://example.com/a.mp4' loop muted style='display:block;width:40px;height:30px'></video>"
            + "<video src='https://example.com/b.mp4' style='display:block;width:40px;height:30px'></video>"
            + "<img src='https://example.com/c.png' style='display:block;width:20px;height:20px'>"
            + "<div style='width:10px;height:10px;background:#f00'></div></body></html>";

    @Test
    void videosAreReadFromTheSceneOpsAndMatchTheListItems() {
        try (UiView v = new UiView("scene-media")) {
            v.loadLocalHtml(HTML, 200, 200);
            List<SceneVideo> videos = v.videos();
            assertEquals(Set.of(new SceneVideo("https://example.com/a.mp4", true, true), new SceneVideo("https://example.com/b.mp4", false, false)),
                    Set.copyOf(videos), "動画だけを、指定と属性つきで");
            Set<String> fromList = v.relativeElements().stream().filter(i -> i.type() == ElementType.VIDEO).map(DisplayItem::text)
                    .collect(Collectors.toSet());
            assertEquals(fromList, videos.stream().map(SceneVideo::src).collect(Collectors.toSet()), "列の動画の項目と同じ");
            assertTrue(SceneMedia.videos(null).isEmpty());
        }
    }
}
