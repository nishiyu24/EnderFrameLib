package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入力欄と select の内容の高さが Chrome（Windows、Arial）と一致する。
 *
 * 期待値は Chrome で padding / border を 0 にして測った高さ:
 * テキストの入力欄は max(line-height, 書体の normal の行の高さ)、select は line-height によらず ascent + descent + 2px。
 * 何が壊れていたか: 以前は両方とも「行の高さ + 8px」で、padding を付けた入力欄が Chrome より 8px 高かった。
 */
public class FormControlHeightTest {

    /** {font-size, line-height(0 = normal), input の高さ, select の高さ}（Chrome の実測）。 */
    private static final float[][] CHROME = {
            {10, 0, 11, 13}, {10, 20, 20, 13},
            {11.05f, 0, 12, 14}, {11.05f, 20, 20, 14},
            {13.333f, 0, 15, 17}, {13.333f, 20, 20, 17},
            {16, 0, 18, 19}, {16, 20, 20, 19},
            {20, 0, 23, 24}, {20, 20, 23, 24},
            {24, 0, 28, 29}, {24, 20, 28, 29},
    };

    private static LayoutBox byId(LayoutBox b, String id) {
        if (b.node != null && id.equals(b.node.attrs.get("id"))) return b;
        for (LayoutBox c : b.children) {
            LayoutBox found = byId(c, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void heightsMatchChrome() {
        SystemFonts system = SystemFonts.system();
        Assumptions.assumeTrue(system.hasFamily("Arial"), "Arial が無い環境");
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < CHROME.length; i++) {
            String style = "font-family:Arial;font-size:" + CHROME[i][0] + "px;line-height:"
                    + (CHROME[i][1] == 0 ? "normal" : CHROME[i][1] + "px") + ";padding:0;border:0;box-sizing:content-box";
            html.append("<div><input id=\"in").append(i).append("\" type=\"text\" style=\"").append(style).append("\">")
                    .append("<select id=\"se").append(i).append("\" style=\"").append(style).append("\"><option>Ab</option></select></div>");
        }
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html.toString(), "", 800, 600);
        LayoutBox root = compiler.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(),
                system.withLocale(Locale.JAPANESE), new InteractionState());

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < CHROME.length; i++) {
            LayoutBox in = byId(root, "in" + i), se = byId(root, "se" + i);
            if (Math.abs(in.h - CHROME[i][2]) > 0.01f) failures.add("input " + CHROME[i][0] + "px lh=" + CHROME[i][1] + ": " + in.h + " / chrome " + CHROME[i][2]);
            if (Math.abs(se.h - CHROME[i][3]) > 0.01f) failures.add("select " + CHROME[i][0] + "px lh=" + CHROME[i][1] + ": " + se.h + " / chrome " + CHROME[i][3]);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
