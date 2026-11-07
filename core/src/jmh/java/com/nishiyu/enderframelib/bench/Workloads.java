package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 計測の代表文書。{@code PerformanceBenchmarkTest} と同じ 3 文書を使い、数値を見比べられるようにする。
 * 作業ディレクトリはリポジトリ直下（Gradle タスクが設定する）。
 */
public final class Workloads {

    public static final int VIEWPORT_W = 800;
    public static final int VIEWPORT_H = 600;
    public static final String SHOWCASE = "showcase";
    public static final String GUIDEBOOK = "guidebook";
    public static final String LARGE_LIST = "large-list";

    private static final Path FIXTURES = Path.of("src/main/resources/data/enderframelib/enderframe_ui");

    private Workloads() { }

    public static String html(String name) {
        try {
            return switch (name) {
                case SHOWCASE -> read("showcase.html");
                case GUIDEBOOK -> MarkdownParser.toHtml(read("guidebook.md"));
                case LARGE_LIST -> largeList(500);
                default -> throw new IllegalArgumentException("未知の文書: " + name);
            };
        } catch (IOException e) {
            throw new IllegalStateException("文書を読めない（作業ディレクトリはリポジトリ直下か）: " + name, e);
        }
    }

    private static String read(String file) throws IOException {
        return Files.readString(FIXTURES.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** {@code count} 行のリストを {@code #scroller}（スクロールコンテナ）に入れた高密度文書。行の id は {@code item-N}。 */
    public static String largeList(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }
}
