package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.network.DomMutationRecord;
import com.nishiyu.enderframelib.network.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * サーバーからの操作（{@code dom_mutations}）とクライアントからの操作（ローカル API・DOM API）が、
 * 差分レイアウトを通しても同じ描画になることを確かめる。
 *
 * <ul>
 *   <li>サーバー: {@code dom_sync} で受け取った文書に、変異パケットを当てる（差分レイアウト）</li>
 *   <li>クライアント: 同じ HTML をローカルに読み込み、同じ変化をセッションのローカル API か DOM API で起こす（差分レイアウト）</li>
 *   <li>参照: サーバーと同じ文書・同じ変異を、差分レイアウトを切って毎回全部作り直す</li>
 * </ul>
 * 毎回 3 つの描画要素の一覧が一致しなければならない。変化の選び方は乱数（種は固定）。
 */
public class ServerClientLayoutParityTest {

    private static final String SERVER = "parity-server";
    private static final String CLIENT = "parity-client";
    private static final String REFERENCE = "parity-reference";
    private static final int W = 800;
    private static final int H = 600;


    @BeforeEach
    public void setUp() {
        clearSessions();
    }

    @AfterEach
    public void tearDown() {
        clearSessions();
    }

    private static void clearSessions() {
        ClientWebManager.clearSession(SERVER);
        ClientWebManager.clearSession(CLIENT);
        ClientWebManager.clearSession(REFERENCE);
    }

    @Test
    public void serverMutationsAndClientOperationsRenderTheSame() {
        for (long seed = 1; seed <= 6; seed++) {
            clearSessions();
            runParity(KITCHEN_SINK, seed, 60);
        }
    }

    @Test
    public void draggingByStyleMatchesAcrossPaths() {
        String html = KITCHEN_SINK;
        open(html);
        List<String> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String left = (10 + i * 7) + "px";
            String top = (20 + (i * 13) % 120) + "px";
            history.add("drag #win to " + left + "," + top);
            server(DomMutationRecord.style("win", "left", left), DomMutationRecord.style("win", "top", top));
            if (i % 2 == 0) {
                client().updateNodeStyleLocally("win", "left", left);
                client().updateNodeStyleLocally("win", "top", top);
            } else {
                node(CLIENT, "win").setStyleProperty("left", left);
                node(CLIENT, "win").setStyleProperty("top", top);
            }
            flushAndCompare("drag step " + i, history);
        }
    }

    // ------------------------------------------------------------------

    void runParity(String html, long seed, int steps) {
        Random random = new Random(seed);
        open(html);
        List<String> history = new ArrayList<>();
        flushAndCompare("seed=" + seed + " initial", history);

        int inserted = 0;
        for (int step = 0; step < steps; step++) {
            List<String> ids = elementIds(session(SERVER).rootNode());
            String id = ids.get(random.nextInt(ids.size()));
            boolean viaDomApi = random.nextBoolean();
            switch (random.nextInt(9)) {
                case 0, 1 -> {
                    String[][] styles = {{"width", "150px"}, {"width", "auto"}, {"padding", "10px"}, {"display", "none"},
                            {"display", "block"}, {"display", "flex"}, {"font-size", "20px"}, {"left", "40px"},
                            {"top", "90px"}, {"color", "#f00"}, {"float", "left"}, {"position", "relative"},
                            {"margin", "8px"}, {"white-space", "nowrap"}};
                    String[] style = styles[random.nextInt(styles.length)];
                    history.add("style " + style[0] + ": " + style[1] + " on #" + id + (viaDomApi ? " (DOM API)" : ""));
                    server(DomMutationRecord.style(id, style[0], style[1]));
                    if (viaDomApi) node(CLIENT, id).setStyleProperty(style[0], style[1]);
                    else client().updateNodeStyleLocally(id, style[0], style[1]);
                }
                case 2 -> {
                    String[] classes = {"hidden", "big", "wide", "rel", "card", "row"};
                    String cls = classes[random.nextInt(classes.length)];
                    boolean add = !node(SERVER, id).hasClass(cls);
                    history.add((add ? "add" : "remove") + " class " + cls + " on #" + id);
                    server(DomMutationRecord.className(id, cls, add ? "add" : "remove"));
                    if (add) node(CLIENT, id).addClass(cls);
                    else node(CLIENT, id).removeClass(cls);
                }
                case 3 -> {
                    if (!hasOnlyText(node(SERVER, id))) continue;
                    String text = random.nextBoolean() ? "short" : "a much longer replacement text that wraps onto more lines";
                    history.add("text \"" + text + "\" on #" + id);
                    server(DomMutationRecord.text(id, text));
                    node(CLIENT, id).setTextContent(text);
                }
                case 4 -> {
                    String attr = random.nextBoolean() ? "hidden" : "data-tag";
                    boolean remove = random.nextBoolean();
                    String value = remove ? null : "v" + random.nextInt(4);
                    history.add("attr " + attr + "=" + value + " on #" + id + (viaDomApi ? " (DOM API)" : ""));
                    server(DomMutationRecord.attribute(id, attr, value, remove));
                    if (viaDomApi) {
                        if (remove) node(CLIENT, id).removeAttribute(attr);
                        else node(CLIENT, id).setAttribute(attr, value);
                    } else {
                        client().updateNodeAttributeLocally(id, attr, value, remove);
                    }
                }
                case 5 -> {
                    String fragment = "<b id=\"ih" + step + "\">inner</b> html " + step;
                    history.add("innerHTML on #" + id + (viaDomApi ? " (DOM API)" : ""));
                    server(DomMutationRecord.innerHtml(id, fragment));
                    if (viaDomApi) node(CLIENT, id).setInnerHTML(fragment);
                    else client().updateNodeInnerHtmlLocally(id, fragment);
                }
                case 6 -> {
                    String newId = "ins" + (inserted++);
                    history.add("append #" + newId + " to #" + id);
                    server(DomMutationRecord.insertChild(id, null, HtmlNodeSerializer.serialize(paragraph(newId))));
                    // クライアントのスクリプトと同じく文書の API で作る
                    com.nishiyu.enderframelib.api.dom.Document dom = client().domDocument();
                    com.nishiyu.enderframelib.api.dom.Element p = dom.createElement("p");
                    p.setId(newId);
                    p.setTextContent("inserted paragraph " + newId);
                    node(CLIENT, id).appendChild(p);
                }
                case 7 -> {
                    HtmlNode target = node(SERVER, id);
                    if (target.parent == null || "body".equals(target.tag) || "html".equals(target.tag)
                            || target.parent.getId().isEmpty()) continue;
                    String parentId = target.parent.getId();
                    history.add("remove #" + id);
                    server(DomMutationRecord.removeChild(parentId, id));
                    HtmlNode clientTarget = node(CLIENT, id);
                    clientTarget.parent.removeChild(clientTarget);
                }
                default -> {
                    if (session(SERVER).rootNode().getNodeById("det") == null) continue;
                    boolean open = !node(SERVER, "det").hasAttribute("open");
                    history.add((open ? "open" : "close") + " #det");
                    server(DomMutationRecord.attribute("det", "open", open ? "" : null, !open));
                    if (open) node(CLIENT, "det").setAttribute("open", "");
                    else node(CLIENT, "det").removeAttribute("open");
                }
            }
            flushAndCompare("seed=" + seed + " step=" + step, history);
        }
    }

    /** サーバーが dom_sync で送る形（サーバーで解析した木と CSS）で 2 つのセッションを開き、クライアントはローカルに読み込む。 */
    private void open(String html) {
        Document serverDoc = new UiDocumentCompiler().parseDocument(html, "", W, H);
        CompoundTag sync = new CompoundTag();
        sync.put("DomTree", HtmlNodeSerializer.serialize(serverDoc.root));
        sync.putString("CssString", serverDoc.authorCss);
        sync.putInt("RootW", W);
        sync.putInt("RootH", H);
        ClientWebManager.getOrCreateSession(SERVER).setRootW(W);
        ClientWebManager.getOrCreateSession(SERVER).setRootH(H);
        ClientWebManager.getOrCreateSession(SERVER).applySync(sync.copy());
        ClientWebManager.getOrCreateSession(REFERENCE).setRootW(W);
        ClientWebManager.getOrCreateSession(REFERENCE).setRootH(H);
        ClientWebManager.getOrCreateSession(REFERENCE).applySync(sync.copy());
        ClientWebManager.getOrCreateSession(CLIENT).loadLocalHtml(html, W, H);
    }

    private static void server(DomMutationRecord... records) {
        ListTag list = new ListTag();
        for (DomMutationRecord record : records) list.add(record.toTag());
        CompoundTag packet = new CompoundTag();
        packet.put("Mutations", list);
        session(SERVER).applyMutations(packet.copy());
        session(REFERENCE).applyMutations(packet.copy());
    }

    private static SessionContext client() {
        return session(CLIENT);
    }

    private static SessionContext session(String id) {
        SessionContext session = ClientWebManager.getSession(id);
        assertNotNull(session, "セッション " + id + " があること");
        return session;
    }

    private static HtmlNode node(String sessionId, String id) {
        HtmlNode found = session(sessionId).rootNode().getNodeById(id);
        assertNotNull(found, sessionId + " に #" + id + " があること");
        return found;
    }

    private static HtmlNode paragraph(String id) {
        HtmlNode p = new HtmlNode("p");
        p.setId(id);
        HtmlNode text = new HtmlText("inserted paragraph " + id);
        text.setId(id + "_txt");
        p.appendChild(text);
        return p;
    }

    private static void flushAndCompare(String label, List<String> history) {
        session(SERVER).flushInvalidation();
        session(CLIENT).flushInvalidation();
        session(REFERENCE).markInvalidation(com.nishiyu.enderframelib.common.UiView.InvalidationLevel.RELAYOUT); // 参照は毎回、前回の木と記憶を捨てて全体を作り直す
        session(REFERENCE).flushInvalidation();
        List<DisplayItem> reference = session(REFERENCE).relativeElements();
        assertSameDisplayList(reference, session(SERVER).relativeElements(), label + " server", history);
        assertSameDisplayList(reference, session(CLIENT).relativeElements(), label + " client", history);
    }

    private static List<String> elementIds(HtmlNode root) {
        List<String> ids = new ArrayList<>();
        collectIds(root, ids, false);
        return ids;
    }

    private static void collectIds(HtmlNode node, List<String> out, boolean inBody) {
        boolean body = inBody || "body".equals(node.tag);
        if (node.nodeType != NodeType.TEXT && body && node.attrs.containsKey("id")) {
            out.add(node.getId());
        }
        for (HtmlNode child : node.children) collectIds(child, out, body);
    }

    private static boolean hasOnlyText(HtmlNode node) {
        for (HtmlNode child : node.children) {
            if (child.nodeType != NodeType.TEXT) return false;
        }
        return true;
    }

    /** 多くの整形の種類を 1 つに混ぜた文書（M2c-3 で消した IncrementalLayoutTest から移した）。 */
    static final String KITCHEN_SINK = """
            <html><head><style>
              body { margin: 6px; font-size: 14px; counter-reset: sec; }
              .row { display: flex; gap: 6px; align-items: center; }
              .row > .grow { flex-grow: 1; }
              .col { display: flex; flex-direction: column; }
              .grid { display: grid; grid-template-columns: 80px 1fr 2fr; gap: 4px; }
              .float-l { float: left; width: 60px; height: 40px; background: #345; }
              .card { border: 2px solid #888; padding: 6px; margin: 4px 0; border-radius: 4px; }
              .wide { width: 70%; }
              .hidden { display: none; }
              .big { font-size: 22px; padding: 10px; }
              .abs { position: absolute; left: 30px; top: 50px; width: 120px; background: #522; }
              .rel { position: relative; left: 5px; top: 3px; }
              .sticky { position: sticky; top: 0; background: #224; }
              .scroller { height: 90px; overflow-y: auto; }
              h2::before { counter-increment: sec; content: counter(sec) ". "; }
              .tag::after { content: " [" attr(data-tag) "]"; }
              ol li::marker { color: #f80; }
              .cq { container-type: inline-size; width: 50%; }
              @container (min-width: 200px) { .cq-inner { padding: 12px; } }
              table { border-collapse: collapse; }
              td { border: 1px solid #999; padding: 3px; }
              pre { white-space: pre; }
              .upper { text-transform: uppercase; }
              .ib { display: inline-block; width: 50px; height: 20px; background: #363; }
            </style></head><body>
              <div id="desk" style="position: relative; height: 200px;">
                <div id="win" class="abs card"><div class="row"><span id="title" class="grow">Window title</span><button id="close">x</button></div>
                  <p id="win-body">Body text that is long enough to wrap inside the window box.</p></div>
                <span id="badge" class="rel">badge</span>
              </div>
              <h2 id="h-a">First section</h2>
              <div id="flex" class="row card"><div id="f1" class="grow">grow one</div><div id="f2">fixed two</div><div id="f3" class="grow big">three</div></div>
              <div id="cols" class="col card"><div id="c1">column a</div><div id="c2" class="wide">column b wide</div></div>
              <h2 id="h-b" class="tag" data-tag="beta">Second section</h2>
              <div id="grid" class="grid card"><div id="g1">g1</div><div id="g2">g2 longer text here</div><div id="g3">g3</div><div id="g4">g4</div></div>
              <div id="floaty" class="card"><div id="fl" class="float-l"></div><p id="wrap">Text wrapping around the float with <b id="bold">bold</b> and <i>italic</i> words, enough to need several lines of text.</p></div>
              <ol id="list"><li id="li1">one</li><li id="li2">two <span class="ib"></span></li><li id="li3" class="upper">three</li></ol>
              <ul id="ul"><li id="u1">alpha</li><li id="u2">beta</li></ul>
              <div id="scroller" class="scroller card"><div id="pin" class="sticky">pinned</div><p id="s1">line 1</p><p id="s2">line 2</p><p id="s3">line 3</p><p id="s4">line 4</p></div>
              <table id="tbl"><tr><td id="t1">a</td><td id="t2">bb</td></tr><tr><td id="t3">ccc</td><td id="t4">d</td></tr></table>
              <details id="det" open><summary id="sum">More</summary><p id="det-p">details body</p></details>
              <div id="cq" class="cq card"><div id="cq-inner" class="cq-inner">container query</div></div>
              <pre id="pre">pre  formatted
              text</pre>
              <form id="form"><input id="inp" value="hello" placeholder="type"><textarea id="ta">area</textarea><select id="sel"><option>one</option><option>two</option></select></form>
              <p id="last">Trailing paragraph with an <span id="inline-span">inline span</span>.</p>
            </body></html>
            """;

    /** 表示リストが同じか（M2c-3 で消した IncrementalLayoutTest から移した）。 */
    static void assertSameDisplayList(List<DisplayItem> expected, List<DisplayItem> actual, String label, List<String> history) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                org.junit.jupiter.api.Assertions.fail(label + ": " + i + " 番目の描画要素が違う\n  期待: " + expected.get(i)
                        + "\n  実際: " + actual.get(i) + "\n  変異の履歴:\n    " + String.join("\n    ", tail(history)));
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected.size(), actual.size(), label + ": 描画要素の数が違う\n  変異の履歴:\n    "
                + String.join("\n    ", tail(history)));
    }

    private static List<String> tail(List<String> history) {
        return history.subList(Math.max(0, history.size() - 12), history.size());
    }
}
