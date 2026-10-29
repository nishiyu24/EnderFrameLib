package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * media_test.html のレイアウトと DisplayList を固定する。
 * 動画・音声・GIF が意図した要素種別とメディア属性で出力されることを確かめる。
 */
public class MediaTestSiteTest {

    private static final Path MEDIA_TEST_PATH =
            Path.of("src/main/resources/data/enderframelib/enderframe_ui/media_test.html");

    private static List<DisplayItem> render() throws Exception {
        String html = Files.readString(MEDIA_TEST_PATH, StandardCharsets.UTF_8);
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 854, 480);
        assertNotNull(doc.root);

        ScrollState scrollState = new ScrollState();
        LayoutBox rootBox = compiler.layoutDocument(doc, 854, 480, Map.of(), scrollState,
                FontResolver.FALLBACK, new InteractionState());
        assertNotNull(rootBox);
        return new DisplayListBuilder(854, 480, doc.sheet.keyframes, scrollState).render(rootBox);
    }

    private static List<DisplayItem> videos(List<DisplayItem> items) {
        return items.stream().filter(i -> i.type() == ElementType.VIDEO).toList();
    }

    @Test
    @DisplayName("<audio> は音声ファイルを指す 1px の動画要素になり、loop / muted がメディア属性として渡る")
    void audioTagsBecomeSizelessMediaElements() throws Exception {
        List<DisplayItem> audio = videos(render()).stream()
                .filter(i -> i.text() != null && (i.text().endsWith(".mp3")
                        || i.text().endsWith(".ogg") || i.text().endsWith(".wav")))
                .toList();
        assertEquals(3, audio.size(), "mp3 / ogg / wav の 3 要素が出力される");
        for (DisplayItem item : audio) {
            assertEquals(1, item.width(), item.text());
            assertEquals(1, item.height(), item.text());
        }

        DisplayItem mp3 = audio.stream().filter(i -> i.text().endsWith(".mp3")).findFirst().orElseThrow();
        assertTrue(mp3.events().containsKey("media-loop"), "mp3 は loop 指定");
        assertTrue(!mp3.events().containsKey("media-muted"));

        DisplayItem wav = audio.stream().filter(i -> i.text().endsWith(".wav")).findFirst().orElseThrow();
        assertTrue(wav.events().containsKey("media-muted"), "wav は muted 指定");
        assertTrue(!wav.events().containsKey("media-loop"));
    }

    @Test
    @DisplayName("http(s) の .gif は動画要素になり、ミュートとループが付く。PNG は静止画のまま")
    void gifImagesAreRoutedToTheVideoDecoder() throws Exception {
        List<DisplayItem> items = render();

        List<DisplayItem> gifs = videos(items).stream()
                .filter(i -> i.text() != null && i.text().endsWith(".gif")).toList();
        assertEquals(4, gifs.size(), "GIF の 4 要素（3 カード + 寸法自動）");
        for (DisplayItem gif : gifs) {
            assertTrue(gif.events().containsKey("media-loop"), "GIF は繰り返す");
            assertTrue(gif.events().containsKey("media-muted"), "GIF は音を出さない");
        }

        assertTrue(items.stream().anyMatch(i -> i.type() == ElementType.IMAGE
                        && i.text() != null && i.text().endsWith("PNG_transparency_demonstration_1.png")),
                "PNG は従来どおり IMAGE");
        assertTrue(items.stream().noneMatch(i -> i.type() == ElementType.IMAGE
                        && i.text() != null && i.text().endsWith(".gif")),
                ".gif が静止画経路に残っていない");
    }

    @Test
    @DisplayName("video の loop / muted 属性は HTML から読まれ、属性の無い動画には付かない")
    void videoAttributesAreReadFromHtml() throws Exception {
        List<DisplayItem> cdn = videos(render()).stream()
                .filter(i -> i.text() != null && i.text().endsWith("ForBiggerBlazes.mp4")).toList();
        assertTrue(cdn.stream().anyMatch(i -> i.events().containsKey("media-loop")
                && i.events().containsKey("media-muted")), "loop muted の動画がある");
        assertTrue(cdn.stream().anyMatch(i -> !i.events().containsKey("media-loop")
                && !i.events().containsKey("media-muted")), "属性なしの動画がある");
    }
}
