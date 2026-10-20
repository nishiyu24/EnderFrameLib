package com.nishiyu.enderframelib.core.dom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web標準 (CommonMark / GitHub Flavored Markdown) 準拠のMarkdown→セマンティックHTML5変換エンジン。
 *
 * 変換されるHTML5要素:
 * - 見出し: <h1>〜<h6>
 * - 段落・改行: <p>, <br/>
 * - 強調・装飾: <strong>, <em>, <del>, <code>
 * - コードブロック: <pre><code class="language-xxx">...</code></pre>
 * - 引用・GitHub Alerts: <blockquote>, <blockquote class="markdown-alert markdown-alert-note" role="note">
 * - リスト・タスクリスト: <ul>, <ol>, <li>, <input type="checkbox" disabled>
 * - テーブル (GFM): <table>, <thead>, <tbody>, <tr>, <th>, <td>
 * - 画像・Minecraftアイテム埋め込み: <img src="item:minecraft:diamond" alt="Diamond" />
 * - リンク: <a href="..." onclick="...">...</a>
 * - 水平線: <hr />
 */
public final class MarkdownParser {

    private static final Pattern CLASS_ATTR_PATTERN = Pattern.compile(
            "(?i)\\bclass\\s*=\\s*([\"'])(.*?)\\1"
    );

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$");
    private static final Pattern HR_PATTERN = Pattern.compile("^(?:\\*\\s*\\*\\s*\\*[\\s*]*|-\\s*-\\s*-[\\s-]*|_\\s*_\\s*_[\\s_]*)$");
    private static final Pattern UL_PATTERN = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern OL_PATTERN = Pattern.compile("^\\s*\\d+\\.\\s+(.+)$");
    private static final Pattern TASK_ITEM_PATTERN = Pattern.compile("^\\[([ xX])]\\s+(.+)$");
    private static final Pattern ALERT_HEADER_PATTERN = Pattern.compile("^\\[!([A-Za-z]+)]\\s*(.*)$");

    private static final Pattern IMAGE_INLINE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");
    private static final Pattern LINK_INLINE = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");
    private static final Pattern CODE_INLINE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_AST = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UND = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE_INLINE = Pattern.compile("~~(.+?)~~");
    private static final Pattern ITALIC_AST = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");

    private MarkdownParser() {}

    /**
     * HTML内の <article data-markdown>, <section data-markdown>, <script type="text/markdown">, <mc-markdown>
     * または純粋なMarkdownテキスト全体をセマンティックHTML5へ展開する。
     */
    public static String processDocumentOrBlocks(String input) {
        if (input == null || input.isEmpty()) return "";
        String trimmed = input.trim();

        // ファイル全体がHTMLタグで始まっていない場合は純粋なMarkdownドキュメントとして変換
        if (looksLikeStandaloneMarkdown(trimmed)) {
            return "<article class=\"markdown-body\">" + toHtml(trimmed) + "</article>";
        }

        // C-5 修正: 以前はこの正規表現マッチャがすべての HTML に対して無条件に走っていた。
        // Markdown ブロックの目印が無ければ入力をそのまま返す。
        if (!hasMarkdownBlockMarker(input)) return input;

        // HTML内のMarkdownブロック要素を展開（元のclass, style, id等の属性を保持）
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        boolean anyReplaced = false;

        for (MarkdownBlock block = findNextBlock(input, cursor); block != null;
             block = findNextBlock(input, cursor)) {
            out.append(input, cursor, block.startIndex);
            out.append(renderBlock(block));
            cursor = block.endIndex;
            anyReplaced = true;
        }

        if (!anyReplaced) return input;
        out.append(input, cursor, input.length());
        return out.toString();
    }

    /** Markdown ブロックである目印。1 つも無ければブロック探索自体を省略できる。 */
    private static boolean hasMarkdownBlockMarker(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.contains("data-markdown") || lower.contains("mc-markdown") || lower.contains("text/markdown");
    }

    /** 展開対象になり得るタグ。 */
    private static final String[] MD_BLOCK_TAGS = {"mc-markdown", "article", "section", "div", "script"};

    /** 見つかった Markdown ブロック 1 つ。 */
    private record MarkdownBlock(String tag, String rawAttrs, String body, int startIndex, int endIndex) {}

    /**
     * {@code from} 以降にある最初の Markdown ブロックを探す。
     *
     * 以前は {@code <(tag)([^>]*)>(.*?)</>} という正規表現だったため、
     * 同じタグが入れ子になっていると閉じタグを取り違えていた。ここでは開始タグを走査して
     * 同名タグの深さを数えながら対応する閉じタグを見つける。
     */
    private static MarkdownBlock findNextBlock(String input, int from) {
        int i = from;
        while (i < input.length()) {
            int lt = input.indexOf('<', i);
            if (lt < 0) return null;

            String matchedTag = matchTagNameAt(input, lt);
            if (matchedTag == null) {
                i = lt + 1;
                continue;
            }

            int attrsEnd = findTagEnd(input, lt);
            if (attrsEnd < 0) return null;

            String rawAttrs = input.substring(lt + 1 + matchedTag.length(), attrsEnd);
            boolean selfClosing = rawAttrs.trim().endsWith("/");
            String lowerAttrs = rawAttrs.toLowerCase(Locale.ROOT);

            boolean isMdBlock = !selfClosing && ("mc-markdown".equals(matchedTag)
                    || lowerAttrs.contains("data-markdown")
                    || lowerAttrs.contains("text/markdown"));
            if (!isMdBlock) {
                i = attrsEnd + 1;
                continue;
            }

            int bodyStart = attrsEnd + 1;
            int bodyEnd = findMatchingClose(input, matchedTag, bodyStart);
            if (bodyEnd < 0) {
                i = attrsEnd + 1;
                continue;
            }

            int blockEnd = bodyEnd + matchedTag.length() + 3; // "</" + tag + ">"
            return new MarkdownBlock(matchedTag, rawAttrs, input.substring(bodyStart, bodyEnd), lt,
                    Math.min(blockEnd, input.length()));
        }
        return null;
    }

    /** 位置 lt の "<" が MD_BLOCK_TAGS のいずれかの開始タグなら、そのタグ名を返す。 */
    private static String matchTagNameAt(String input, int lt) {
        for (String tag : MD_BLOCK_TAGS) {
            int nameEnd = lt + 1 + tag.length();
            if (nameEnd > input.length()) continue;
            if (!input.regionMatches(true, lt + 1, tag, 0, tag.length())) continue;
            if (nameEnd == input.length()) continue;
            char after = input.charAt(nameEnd);
            if (Character.isWhitespace(after) || after == '>' || after == '/') return tag;
        }
        return null;
    }

    /** 開始タグの ">" の位置。引用符の中の ">" は数えない。 */
    private static int findTagEnd(String input, int lt) {
        int i = lt + 1;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '"' || c == '\'') {
                int close = input.indexOf(c, i + 1);
                if (close < 0) return -1;
                i = close + 1;
                continue;
            }
            if (c == '>') return i;
            i++;
        }
        return -1;
    }

    /** 同名タグの入れ子を数えて、対応する閉じタグの "<" の位置を返す。 */
    private static int findMatchingClose(String input, String tag, int from) {
        int depth = 1;
        int i = from;
        String open = "<" + tag;
        String close = "</" + tag;

        while (i < input.length()) {
            int nextOpen = indexOfTag(input, open, i);
            int nextClose = indexOfTag(input, close, i);
            if (nextClose < 0) return -1;

            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + open.length();
            } else {
                depth--;
                if (depth == 0) return nextClose;
                i = nextClose + close.length();
            }
        }
        return -1;
    }

    /** タグ名の直後が区切り文字である出現位置だけを探す（article と articles を区別する）。 */
    private static int indexOfTag(String input, String needle, int from) {
        int i = from;
        while (i < input.length()) {
            int found = input.toLowerCase(Locale.ROOT).indexOf(needle, i);
            if (found < 0) return -1;
            int after = found + needle.length();
            if (after >= input.length()) return -1;
            char c = input.charAt(after);
            if (Character.isWhitespace(c) || c == '>' || c == '/') return found;
            i = found + 1;
        }
        return -1;
    }

    /** ブロックを Markdown として展開した HTML に変換する。 */
    private static String renderBlock(MarkdownBlock block) {
        String outTag = ("mc-markdown".equals(block.tag) || "script".equals(block.tag)) ? "article" : block.tag;
        String cleanedAttrs = block.rawAttrs
                .replaceAll("(?i)\\bdata-markdown(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?", "")
                .replaceAll("(?i)\\btype\\s*=\\s*[\"']text/markdown[\"']", "")
                .trim();

        Matcher cm = CLASS_ATTR_PATTERN.matcher(cleanedAttrs);
        if (cm.find()) {
            String existingClasses = cm.group(2).trim();
            String mergedClasses = existingClasses.contains("markdown-body")
                    ? existingClasses
                    : (existingClasses + " markdown-body").trim();
            cleanedAttrs = cm.replaceFirst("class=\"" + Matcher.quoteReplacement(mergedClasses) + "\"");
        } else {
            cleanedAttrs = ("class=\"markdown-body\" " + cleanedAttrs).trim();
        }

        String openTag = "<" + outTag + (cleanedAttrs.isEmpty() ? "" : " " + cleanedAttrs) + ">";
        return openTag + toHtml(dedent(block.body)) + "</" + outTag + ">";
    }

    private static boolean looksLikeStandaloneMarkdown(String trimmed) {
        if (trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<head")
                || lower.startsWith("<body") || lower.startsWith("<div") || lower.startsWith("<main")
                || lower.startsWith("<section") || lower.startsWith("<article") || lower.startsWith("<form")
                || lower.startsWith("<style") || lower.startsWith("<link")) {
            return false;
        }
        return trimmed.startsWith("#") || trimmed.startsWith("> ") || trimmed.startsWith("- ")
                || trimmed.startsWith("* ") || trimmed.startsWith("```") || trimmed.startsWith("|");
    }

    /**
     * Markdown文字列をW3C標準セマンティックHTML5フラグメントに変換する。
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = lines.length;

        while (i < n) {
            String line = lines[i];
            String trimmed = line.trim();

            // 1. 空行スキップ
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            // 2. フェンスドコードブロック (```lang ... ```)
            if (trimmed.startsWith("```")) {
                String lang = trimmed.substring(3).trim();
                StringBuilder codeBuf = new StringBuilder();
                i++;
                while (i < n && !lines[i].trim().startsWith("```")) {
                    if (codeBuf.length() > 0) codeBuf.append('\n');
                    codeBuf.append(escapeHtml(lines[i]));
                    i++;
                }
                if (i < n) i++; // closing ```
                String langAttr = lang.isEmpty() ? "" : " class=\"language-" + escapeAttr(lang) + "\" data-syntax=\"" + escapeAttr(lang) + "\"";
                out.append("<pre class=\"md-pre\"><code").append(langAttr).append(">")
                        .append(codeBuf)
                        .append("</code></pre>\n");
                continue;
            }

            // 3. 水平線 (---, ***, ___)
            if (HR_PATTERN.matcher(trimmed).matches()) {
                out.append("<hr />\n");
                i++;
                continue;
            }

            // 4. 見出し (# 〜 ######)
            Matcher hm = HEADING_PATTERN.matcher(trimmed);
            if (hm.matches()) {
                int level = hm.group(1).length();
                String content = hm.group(2).trim();
                String slug = content.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9faf\\-_ ]", "")
                        .trim()
                        .replaceAll("\\s+", "-");
                out.append("<h").append(level);
                if (!slug.isEmpty()) {
                    out.append(" id=\"").append(escapeAttr(slug)).append("\"");
                }
                out.append(">").append(parseInline(content)).append("</h").append(level).append(">\n");
                i++;
                continue;
            }

            // 5. 引用 (>) および GitHub Alerts (> [!NOTE] 等)
            if (trimmed.startsWith(">")) {
                List<String> quoteLines = new ArrayList<>();
                while (i < n && lines[i].trim().startsWith(">")) {
                    String qLine = lines[i].trim().substring(1);
                    if (qLine.startsWith(" ")) qLine = qLine.substring(1);
                    quoteLines.add(qLine);
                    i++;
                }
                renderBlockquote(out, quoteLines);
                continue;
            }

            // 6. GFM テーブル (| A | B | \n |---|---|)
            if (trimmed.startsWith("|") && i + 1 < n && isTableSeparatorLine(lines[i + 1].trim())) {
                List<String> tableLines = new ArrayList<>();
                while (i < n && lines[i].trim().startsWith("|")) {
                    tableLines.add(lines[i].trim());
                    i++;
                }
                renderTable(out, tableLines);
                continue;
            }

            // 7. 箇条書きリスト (-, *, +) およびタスクリスト (- [x])
            // LIMITATION(制約): リストは平坦。字下げした入れ子リストは兄弟の <li> になる。
            // 入れ子が必要なら HTML で書く。README の "Not supported" と対応。
            if (UL_PATTERN.matcher(line).matches()) {
                out.append("<ul>\n");
                while (i < n) {
                    Matcher ulm = UL_PATTERN.matcher(lines[i]);
                    if (!ulm.matches()) break;
                    String itemContent = ulm.group(1).trim();
                    Matcher taskM = TASK_ITEM_PATTERN.matcher(itemContent);
                    if (taskM.matches()) {
                        boolean checked = !" ".equals(taskM.group(1));
                        out.append("<li class=\"task-list-item\"><input type=\"checkbox\" disabled")
                                .append(checked ? " checked" : "")
                                .append(" /> <span>")
                                .append(parseInline(taskM.group(2)))
                                .append("</span></li>\n");
                    } else {
                        out.append("<li>").append(parseInline(itemContent)).append("</li>\n");
                    }
                    i++;
                }
                out.append("</ul>\n");
                continue;
            }

            // 8. 番号付きリスト (1., 2.)
            if (OL_PATTERN.matcher(line).matches()) {
                out.append("<ol>\n");
                while (i < n) {
                    Matcher olm = OL_PATTERN.matcher(lines[i]);
                    if (!olm.matches()) break;
                    out.append("<li>").append(parseInline(olm.group(1).trim())).append("</li>\n");
                    i++;
                }
                out.append("</ol>\n");
                continue;
            }

            // 9. インラインHTMLブロックがそのまま書かれている場合
            if (trimmed.startsWith("<") && !trimmed.startsWith("<kbd>") && !trimmed.startsWith("<code>")
                    && !trimmed.startsWith("<strong>") && !trimmed.startsWith("<em>") && !trimmed.startsWith("<span")) {
                out.append(line).append("\n");
                i++;
                continue;
            }

            // 10. 通常の段落 (<p>)
            StringBuilder para = new StringBuilder();
            while (i < n) {
                String pLine = lines[i];
                String pTrim = pLine.trim();
                if (pTrim.isEmpty()
                        || pTrim.startsWith("#")
                        || pTrim.startsWith("```")
                        || pTrim.startsWith(">")
                        || (pTrim.startsWith("|") && i + 1 < n && isTableSeparatorLine(lines[i + 1].trim()))
                        || HR_PATTERN.matcher(pTrim).matches()
                        || UL_PATTERN.matcher(pLine).matches()
                        || OL_PATTERN.matcher(pLine).matches()) {
                    break;
                }
                if (para.length() > 0) para.append(" ");
                para.append(pTrim);
                i++;
            }
            out.append("<p>").append(parseInline(para.toString())).append("</p>\n");
        }

        return out.toString();
    }

    private static void renderBlockquote(StringBuilder out, List<String> quoteLines) {
        if (quoteLines.isEmpty()) return;
        String first = quoteLines.getFirst().trim();
        Matcher alertM = ALERT_HEADER_PATTERN.matcher(first);
        if (alertM.matches()) {
            String alertType = alertM.group(1).toLowerCase(Locale.ROOT);
            String inlineRest = alertM.group(2).trim();
            String title = switch (alertType) {
                case "note" -> "Note";
                case "tip" -> "Tip";
                case "important" -> "Important";
                case "warning" -> "Warning";
                case "caution" -> "Caution";
                default -> alertType.toUpperCase(Locale.ROOT);
            };
            out.append("<blockquote class=\"markdown-alert markdown-alert-").append(escapeAttr(alertType)).append("\" role=\"note\">\n");
            out.append("<div class=\"markdown-alert-title\">").append(title).append("</div>\n");
            StringBuilder body = new StringBuilder();
            if (!inlineRest.isEmpty()) body.append(inlineRest).append("\n");
            for (int j = 1; j < quoteLines.size(); j++) {
                body.append(quoteLines.get(j)).append("\n");
            }
            out.append(toHtml(body.toString()));
            out.append("</blockquote>\n");
            return;
        }

        out.append("<blockquote>\n");
        out.append(toHtml(String.join("\n", quoteLines)));
        out.append("</blockquote>\n");
    }

    private static boolean isTableSeparatorLine(String line) {
        if (!line.startsWith("|")) return false;
        String inner = line.replace("|", "").replace(":", "").replace("-", "").trim();
        return inner.isEmpty() && line.contains("-");
    }

    private static void renderTable(StringBuilder out, List<String> tableLines) {
        if (tableLines.size() < 2) return;
        List<String> headers = splitTableRow(tableLines.get(0));
        List<String> alignRow = splitTableRow(tableLines.get(1));
        List<String> alignments = new ArrayList<>();
        for (String cell : alignRow) {
            String c = cell.trim();
            if (c.startsWith(":") && c.endsWith(":")) alignments.add("center");
            else if (c.endsWith(":")) alignments.add("right");
            else alignments.add("left");
        }

        out.append("<table>\n<thead>\n<tr>");
        for (int c = 0; c < headers.size(); c++) {
            String align = c < alignments.size() ? alignments.get(c) : "left";
            out.append("<th style=\"text-align:").append(align).append(";\">")
                    .append(parseInline(headers.get(c).trim()))
                    .append("</th>");
        }
        out.append("</tr>\n</thead>\n<tbody>\n");

        for (int r = 2; r < tableLines.size(); r++) {
            List<String> cells = splitTableRow(tableLines.get(r));
            out.append("<tr>");
            for (int c = 0; c < headers.size(); c++) {
                String align = c < alignments.size() ? alignments.get(c) : "left";
                String val = c < cells.size() ? cells.get(c).trim() : "";
                out.append("<td style=\"text-align:").append(align).append(";\">")
                        .append(parseInline(val))
                        .append("</td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    private static List<String> splitTableRow(String row) {
        String s = row.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) list.add(p);
        return list;
    }

    /**
     * インラインMarkdown要素（画像、リンク、インラインコード、太字、斜体、打消し線）をHTML5へ変換する。
     */
    public static String parseInline(String text) {
        if (text == null || text.isEmpty()) return "";

        // 1. インラインコード (`code`) を先に退避して内部が装飾されないようにする
        List<String> codeSnippets = new ArrayList<>();
        Matcher cm = CODE_INLINE.matcher(text);
        StringBuilder temp = new StringBuilder();
        while (cm.find()) {
            String placeholder = "\u0001CODE_" + codeSnippets.size() + "\u0001";
            codeSnippets.add("<code>" + escapeHtml(cm.group(1)) + "</code>");
            cm.appendReplacement(temp, Matcher.quoteReplacement(placeholder));
        }
        cm.appendTail(temp);
        String s = temp.toString();

        // 2. 画像 ![alt](src) - item:minecraft:xxx 等の場合は data-item も自動付与してJEI/REI連携も有効化
        Matcher im = IMAGE_INLINE.matcher(s);
        temp.setLength(0);
        while (im.find()) {
            String alt = escapeAttr(im.group(1));
            String src = escapeAttr(im.group(2));
            String extraAttr = "";
            if (src.startsWith("item:")) {
                extraAttr = " data-item=\"" + src.substring(5) + "\" style=\"width:16px;height:16px;vertical-align:middle;\"";
            } else if (src.startsWith("fluid:")) {
                extraAttr = " data-fluid=\"" + src.substring(6) + "\" style=\"width:16px;height:16px;vertical-align:middle;\"";
            }
            String imgHtml = "<img src=\"" + src + "\" alt=\"" + alt + "\"" + extraAttr + " />";
            im.appendReplacement(temp, Matcher.quoteReplacement(imgHtml));
        }
        im.appendTail(temp);
        s = temp.toString();

        // 3. リンク [label](href)
        Matcher lm = LINK_INLINE.matcher(s);
        temp.setLength(0);
        while (lm.find()) {
            String label = lm.group(1);
            String href = escapeAttr(lm.group(2));
            String onclick = href.startsWith("#") ? ("scroll:" + href.substring(1))
                    : (href.startsWith("action:") ? href.substring(7) : ("link:" + href));
            String aHtml = "<a href=\"" + href + "\" onclick=\"" + onclick + "\">" + label + "</a>";
            lm.appendReplacement(temp, Matcher.quoteReplacement(aHtml));
        }
        lm.appendTail(temp);
        s = temp.toString();

        // 4. 太字・斜体・打ち消し線
        s = BOLD_AST.matcher(s).replaceAll("<strong>$1</strong>");
        s = BOLD_UND.matcher(s).replaceAll("<strong>$1</strong>");
        s = STRIKE_INLINE.matcher(s).replaceAll("<del>$1</del>");
        s = ITALIC_AST.matcher(s).replaceAll("<em>$1</em>");

        // 5. 退避したインラインコードを復元
        for (int idx = 0; idx < codeSnippets.size(); idx++) {
            s = s.replace("\u0001CODE_" + idx + "\u0001", codeSnippets.get(idx));
        }
        return s;
    }

    private static String dedent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.length() >= minIndent) sb.append(l.substring(minIndent));
            else sb.append(l.trim());
            if (i + 1 < lines.length) sb.append('\n');
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escapeHtml(s).replace("\"", "&quot;");
    }
}