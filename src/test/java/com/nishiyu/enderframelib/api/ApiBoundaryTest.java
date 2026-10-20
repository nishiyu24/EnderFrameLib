package com.nishiyu.enderframelib.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * api/ が実装（server・common・network・client）に依存しないことを守る番人。
 *
 * <p>以前は {@code WebSessionProvider} が具象 {@code server.WebSession} を返し、
 * {@code EnderFrameScriptingBridge} が server を 5 本・network を 1 本 import していた。
 * 下流 Mod は api だけを見てビルドするので、ここが漏れると内部変更が全て破壊的変更になる。</p>
 */
public class ApiBoundaryTest {

    private static final Path API = Paths.get("src/main/java/com/nishiyu/enderframelib/api");

    /** api/ から参照してはいけないパッケージ（import でも完全修飾名でも禁止） */
    private static final String[] FORBIDDEN = {
            "com.nishiyu.enderframelib.server.",
            "com.nishiyu.enderframelib.client.",
            "com.nishiyu.enderframelib.common.",
            "com.nishiyu.enderframelib.network.",
            "com.nishiyu.enderframelib.core.",
    };

    @Test
    public void testApiDoesNotDependOnImplementation() throws IOException {
        assertTrue(Files.isDirectory(API),
                "api パッケージが見つかりません: " + API.toAbsolutePath()
                        + " (テストはプロジェクトルートを作業ディレクトリとして実行してください)");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(API)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                // Javadoc は実装クラスに言及して良い（下流に「ここが基底クラス」と伝える必要がある）
                String content = stripComments(Files.readString(path));
                for (String forbidden : FORBIDDEN) {
                    if (content.contains(forbidden)) {
                        violations.add(API.relativize(path) + " → " + forbidden);
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("【API 境界違反】 api/ が実装パッケージに依存しています:\n  "
                    + String.join("\n  ", violations)
                    + "\napi 側にインターフェースを定義し、実装から注入してください"
                    + "（EnderFrameAPI / WebSessionHandle と同じ形）。");
        }
    }

    /** ブロックコメントと行コメントを落とす（文字列リテラル中の "//" は対象外でも実害が無い）。 */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("(?m)//.*$", "");
    }
}
