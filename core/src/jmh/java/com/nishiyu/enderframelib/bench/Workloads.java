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
    /** 行ごとにデザイン（余白・色・枠・文字サイズ・文章の長さ・入れ子）が違う {@code varied-list-N}。共有できるスタイルがほぼ無い最悪側の比較用。 */
    public static final String VARIED_LIST = "varied-list";

    private static final Path FIXTURES = Path.of("src/main/resources/data/enderframelib/enderframe_ui");

    private Workloads() { }

    public static String html(String name) {
        try {
            return switch (name) {
                case SHOWCASE -> read("showcase.html");
                case GUIDEBOOK -> MarkdownParser.toHtml(read("guidebook.md"));
                case LARGE_LIST -> largeList(500);
                default -> {
                    if (name.startsWith(VARIED_LIST + "-")) yield variedList(Integer.parseInt(name.substring(VARIED_LIST.length() + 1)));
                    if (isList(name)) yield largeList(name.equals(LARGE_LIST) ? 500 : Integer.parseInt(name.substring(LARGE_LIST.length() + 1)));
                    throw new IllegalArgumentException("未知の文書: " + name);
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("文書を読めない（作業ディレクトリはリポジトリ直下か）: " + name, e);
        }
    }

    /** {@code large-list} または {@code large-list-N}（N 行）か。 */
    public static boolean isList(String name) { return name.equals(LARGE_LIST) || name.startsWith(LARGE_LIST + "-") || name.startsWith(VARIED_LIST + "-"); }

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

    private static final String[] PALETTE = {"#fff", "#eef", "#fee", "#efe", "#ffe", "#eff", "#fef", "#dde", "#edd", "#ded", "#eed"};
    private static final String[] BORDER = {"#ccc", "#99c", "#c99", "#9c9", "#cc9", "#9cc", "#c9c", "#888"};
    private static final String LOREM = "alpha beta gamma delta epsilon zeta eta theta iota kappa ";

    /**
     * 行ごとにデザインが違う {@code count} 行のリスト。インラインの style だけで決め（クラスでスタイルを共有しない）、
     * 余白・背景・左の枠・下の枠・文字サイズ（em で相対）・文章の長さ（折り返す行もある）・入れ子（バッジ・太字・サブ行）を行の番号から決める。
     * 行の先頭は文字（text シナリオが先頭の文字の節を書き換える）。Chrome 側（trace.js）はこの関数の出力のファイルを読む。
     */
    public static String variedList(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { border-bottom: 1px solid #ccc; } li:hover { color: #c00 !important; } li.fh { color: #c00 !important; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            int pad = 2 + (i * 7) % 9, mb = i % 5, bl = (i * 3) % 5, fs = 80 + (i * 5) % 6 * 10;
            sb.append("<li id=\"item-").append(i).append("\" style=\"padding:").append(pad).append("px ").append(pad + (i % 4)).append("px;margin-bottom:")
              .append(mb).append("px;background:").append(PALETTE[(i * 3) % PALETTE.length]).append(";border-left:").append(bl).append("px solid ")
              .append(BORDER[i % BORDER.length]).append(";border-bottom:").append(1 + i % 3).append("px solid ").append(BORDER[(i * 5) % BORDER.length])
              .append(";font-size:").append(fs).append("%").append(i % 6 == 0 ? ";color:#224" : "").append("\">");
            sb.append("Row item #").append(i).append(" ").append(LOREM.repeat(1 + (i * 13) % 6));
            if (i % 3 == 0) sb.append("<span style=\"background:").append(PALETTE[i % PALETTE.length]).append(";padding:1px ").append(2 + i % 5).append("px;border-radius:").append(i % 4).append("px\">tag").append(i % 7).append("</span>");
            if (i % 4 == 0) sb.append(" <b>bold ").append(i % 10).append("</b>");
            if (i % 7 == 0) sb.append("<div style=\"margin-top:").append(i % 5).append("px;padding-left:").append(4 + i % 11).append("px;color:#666;font-size:").append(70 + i % 4 * 10).append("%\">sub ").append(i).append(" ").append(LOREM.repeat(1 + i % 3)).append("</div>");
            sb.append("</li>");
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }

    /** {@code varied-list-N} などの文書を HTML ファイルに書く（ブラウザ側の計測が読む）。引数: 文書名 出力先。 */
    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of(args[1]), html(args[0]), StandardCharsets.UTF_8);
    }
}