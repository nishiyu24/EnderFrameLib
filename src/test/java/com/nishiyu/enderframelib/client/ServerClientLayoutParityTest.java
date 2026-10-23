package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
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

    private RenderPipeline.ILayoutBoxRenderer previousRenderer;

    @BeforeEach
    public void setUp() {
        previousRenderer = UiDocumentCompiler.clientBoxRenderer;
        UiDocumentCompiler.clientBoxRenderer = (rootBox, w, h, kf, ss) -> new DisplayListBuilder(w, h, kf, ss).render(rootBox);
        clearSessions();
    }

    @AfterEach
    public void tearDown() {
        clearSessions();
        RenderPipeline.incrementalLayout = true;
        UiDocumentCompiler.clientBoxRenderer = previousRenderer;
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
            runParity(IncrementalLayoutTest.KITCHEN_SINK, seed, 60);
        }
    }

    @Test
    public void draggingByStyleMatchesAcrossPaths() {
        String html = IncrementalLayoutTest.KITCHEN_SINK;
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
        RenderPipeline.incrementalLayout = false;
        try {
            session(REFERENCE).flushInvalidation();
        } finally {
            RenderPipeline.incrementalLayout = true;
        }
        List<DisplayItem> reference = session(REFERENCE).renderCache();
        for (String id : new String[]{SERVER, CLIENT}) {
            if (!reference.equals(session(id).renderCache())) {
                history.add(id + " box tree diff: " + IncrementalLayoutTest.firstBoxDifference(
                        session(REFERENCE).layoutRoot(), session(id).layoutRoot()));
            }
        }
        IncrementalLayoutTest.assertSameDisplayList(reference, session(SERVER).renderCache(), label + " server", history);
        IncrementalLayoutTest.assertSameDisplayList(reference, session(CLIENT).renderCache(), label + " client", history);
    }

    private static List<String> elementIds(HtmlNode root) {
        List<String> ids = new ArrayList<>();
        collectIds(root, ids, false);
        return ids;
    }

    private static void collectIds(HtmlNode node, List<String> out, boolean inBody) {
        boolean body = inBody || "body".equals(node.tag);
        if (node.nodeType != NodeType.TEXT && body && node.attrs.containsKey("id") && !node.getId().startsWith("gen_")) {
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
}
