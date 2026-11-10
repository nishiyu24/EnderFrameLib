package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 入力 API のボタン番号はホスト（GLFW 規約）の番号であり、Minecraft 固有の名前を持たない。 */
class UiInputHostButtonTest {

    @Test
    void hostButtonsMapToDomButtons() {
        assertEquals(0, UiInput.domButton(0), "左");
        assertEquals(2, UiInput.domButton(1), "右 → DOM 2");
        assertEquals(1, UiInput.domButton(2), "中 → DOM 1");
        assertEquals(7, UiInput.domButton(7), "未知のボタンはそのまま");
    }

    @Test
    void buttonsMaskFollowsDomButtons() {
        assertEquals(1, UiInput.buttonsMask(UiInput.domButton(0)));
        assertEquals(2, UiInput.buttonsMask(UiInput.domButton(1)));
        assertEquals(4, UiInput.buttonsMask(UiInput.domButton(2)));
    }

    @Test
    void pressResultExposesHostButton() throws Exception {
        var m = UiInput.PressResult.class.getRecordComponents();
        boolean found = false;
        for (var c : m) {
            assertNotEquals("minecraftButton", c.getName());
            if (c.getName().equals("hostButton")) found = true;
        }
        assertTrue(found);
        var r = new UiInput.PressResult(null, null, null, 1, false);
        assertEquals(1, r.hostButton());
    }

    @Test
    void coreInputSourceHasNoMinecraftButtonName() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib/common");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/nishiyu/enderframelib/common");
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                assertFalse(Files.readString(p).contains("minecraftButton"), p.toString());
            }
        }
    }
}
