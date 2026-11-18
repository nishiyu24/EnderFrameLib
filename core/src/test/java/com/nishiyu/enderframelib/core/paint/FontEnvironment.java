package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * OS の書体に依る画素のゴールデン（{@code GoldenFrameTest}・{@code RealDocumentFramesTest}）の、書体の環境の指紋。
 *
 * <p>SOFTWARE の描画は OS の書体（{@link SystemFonts#defaultFontDirectories()}）を使うので、同じ OS でも入っている書体が違うと
 * 画素が変わる（GitHub の ubuntu-latest〔Ubuntu 26・絵文字書体あり〕と WSL の Ubuntu 24 で、全フレームの画素が違った）。
 * 画素のファイルに記録したときの指紋を書き、今の環境の指紋と違えば画素の比較だけを飛ばす（表示リストとヒットテストは
 * OS に依らないので常に比べる）。指紋は書体のフォルダの中の書体ファイルの相対パスと大きさの一覧の CRC32。</p>
 */
public final class FontEnvironment {
    private FontEnvironment() { }

    /** 画素のファイルの指紋の行の頭。 */
    public static final String HEADER = "# fonts ";

    private static volatile String cached;

    /** 今の環境の指紋（16 進）。 */
    public static String fingerprint() {
        String c = cached;
        if (c != null) return c;
        List<String> entries = new ArrayList<>();
        for (Path dir : SystemFonts.defaultFontDirectories()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc") || name.endsWith(".otc"))) return;
                    try {
                        entries.add(dir.relativize(p).toString().replace('\\', '/') + " " + Files.size(p));
                    } catch (IOException ignored) {
                        entries.add(dir.relativize(p).toString().replace('\\', '/') + " ?");
                    }
                });
            } catch (IOException e) {
                entries.add(dir + " unreadable");
            }
        }
        Collections.sort(entries);
        CRC32 crc = new CRC32();
        for (String e : entries) crc.update((e + "\n").getBytes(StandardCharsets.UTF_8));
        return cached = Long.toHexString(crc.getValue()) + "-" + entries.size();
    }

    /** 画素のファイルの本文から、記録したときの指紋（無ければ null）。 */
    public static String recorded(String fileText) {
        if (fileText == null) return null;
        for (String line : fileText.split("\n")) if (line.startsWith(HEADER)) return line.substring(HEADER.length()).trim();
        return null;
    }

    /**
     * 画素を比べてよいか。指紋の無い古いファイルは比べる（従来どおり）。違えば理由を標準出力に書いて false
     * （取り直すときは、その環境で {@code -Dgolden.update=true}）。
     */
    public static boolean pixelsComparable(String fileText, String what) {
        String rec = recorded(fileText);
        if (rec == null || rec.equals(fingerprint())) return true;
        System.out.println(what + ": 書体の環境が記録と違う（記録 " + rec + "・今 " + fingerprint() + "）ので画素は比べない。表示リストとヒットテストは比べる");
        return false;
    }
}
