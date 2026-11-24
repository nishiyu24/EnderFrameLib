package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.sample.SampleElements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A9: {@code api} だけをコンパイル依存にした外部の利用者のサンプル（{@code core/src/apiSample}）。
 * ソースが内部のパッケージを参照しないこと・そのまま SOFTWARE で描けること・利用者の宣言が台帳に載ることを固定する。
 */
class ApiSampleTest {

    @Test
    void sampleSourcesReferenceOnlyTheJdkAndThePublicApi() throws IOException {
        Path root = Path.of("core/src/apiSample/java");
        if (!Files.isDirectory(root)) root = Path.of("src/apiSample/java");
        List<String> offenders = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                files++;
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.startsWith("import ")) {
                        boolean ok = t.startsWith("import java.") || t.startsWith("import com.nishiyu.enderframelib.api.");
                        if (!ok) offenders.add(p.getFileName() + ": " + t);
                    }
                    if (!t.startsWith("*") && !t.startsWith("//") && !t.startsWith("import ")
                            && (t.contains("enderframelib.core.") || t.contains("enderframelib.common."))) {
                        offenders.add(p.getFileName() + ": " + t);
                    }
                }
            }
        }
        assertTrue(files > 0, "サンプルのソースが無い");
        assertEquals(List.of(), offenders, "サンプルは api.* と JDK だけを参照する");
    }

    @Test
    void sampleTagRendersWithTheCustomColorOnTheSoftwareBackend() {
        int w = 60;
        int[] px = SampleElements.renderBadge("#ff0000", w, 40);
        assertEquals(0xFFFF0000, px[10 * w + 10], "展開された箱が描かれる");
        assertEquals(0xFFFFFFFF, px[35 * w + 50], "箱の外は背景");
        int[] blue = SampleElements.renderBadge("#0000ff", w, 40);
        assertEquals(0xFF0000FF, blue[10 * w + 10]);
    }

    @Test
    void sampleReplacedElementIsRegisteredFromTheServiceFile() {
        assertTrue(ReplacedElements.isHostImageLike("x-avatar"));
        assertEquals("avatar:bob", ReplacedElements.painterOf("x-avatar").resolveSrc(java.util.Map.of("user", "bob"), ""));
        assertFalse(ReplacedElements.isHostScene("x-avatar"));
    }
}
