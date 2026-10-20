package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.layout.TextSelection;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.core.layout.DisplayItem.RenderKeys;
import com.nishiyu.enderframelib.core.layout.DisplayItem.ElementType;
import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.common.EnderFrameBuiltInConfig;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.Ownership;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import com.nishiyu.enderframelib.network.DomMutationRecord.HtmlNodeSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Objects;

public class ClientWebManager {

    public static Consumer<String> applyOverridesCallback = null;

    private static final Map<String, SessionContext> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<String> DIRTY_SESSIONS = ConcurrentHashMap.newKeySet();

    public static SessionContext getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        return SESSIONS.get(sessionId);
    }

    /** 現在生きている全セッションのスナップショット。描画キューや HUD の横断処理だけが使う。 */
    public static Collection<SessionContext> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    public static SessionContext getOrCreateSession(String sessionId) {
        String safeId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        return SESSIONS.computeIfAbsent(safeId, SessionContext::new);
    }

    /**
     * フォーカス中の入力要素のレンダー ID。セッション単位で保持されるため、
     * GUI 画面とブロック画面が同時に開いていてもフォーカスは混ざらない。
     */
    public static String getActiveInputId(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.activeInputId() : "";
    }

    public static void setActiveInputId(String sessionId, String id) {
        // クリアだけのために空のセッションを作らない。
        SessionContext session = (id == null || id.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session != null) {
            session.setActiveInputId(id);
            String baseId = getBaseNodeId(id);
            if (!baseId.isEmpty()
                    && session.interactionState().setFocusedNode(resolveInteractiveNode(sessionId, baseId), false)) {
                requestRestyle(sessionId);
            }
        }
    }

    public static boolean isActiveInput(String sessionId, String renderId) {
        String active = getActiveInputId(sessionId);
        return !active.isEmpty() && active.equals(renderId);
    }

    /** 押下中（:active）の要素の基底ノード ID。セッション単位。 */
    public static String getActivePressedId(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.activePressedId() : "";
    }

    public static void setActivePressedId(String sessionId, String id) {
        SessionContext session = (id == null || id.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session != null) {
            if (session.interactionState().setActiveNode(resolveInteractiveNode(sessionId, getBaseNodeId(id)))) {
                requestRestyle(sessionId);
            }
        }
    }

    /** ホバー中（:hover）の要素の基底ノード ID を更新し、変化があれば再スタイルを要求する。 */
    public static void setHoveredElement(String sessionId, String baseId) {
        SessionContext session = (baseId == null || baseId.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session != null) {
            if (session.interactionState().setHoveredNode(resolveInteractiveNode(sessionId, baseId))) {
                requestRestyle(sessionId);
            }
        }
    }

    /** フォーカス中（:focus / :focus-visible / :focus-within）の要素を更新し、変化があれば再スタイルを要求する。 */
    public static void setFocusedElement(String sessionId, String baseId, boolean focusVisible) {
        SessionContext session = (baseId == null || baseId.isEmpty()) ? getSession(sessionId) : getOrCreateSession(sessionId);
        if (session == null) return;
        HtmlNode targetNode = resolveInteractiveNode(sessionId, baseId);
        boolean changed;
        if (getRootNode(sessionId) instanceof com.nishiyu.enderframelib.core.dom.HtmlDocument doc) {
            // 文書の activeElement は同じ InteractionState から読むので、焦点イベントを伴う更新は文書に任せる
            changed = doc.setFocusedElement(targetNode instanceof com.nishiyu.enderframelib.core.dom.HtmlElement el ? el : null, focusVisible);
        } else {
            changed = session.interactionState().setFocusedNode(targetNode, focusVisible);
        }
        if (changed) {
            requestRestyle(sessionId);
        }
    }

    /** 現在フォーカスされている要素の基底 ID を取得する。無ければ空文字。 */
    public static String getFocusedElementBaseId(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.focusedId() : "";
    }

    /** テキスト選択状態モデルを取得する。無ければ null。 */
    public static TextSelection getTextSelection(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.textSelection() : null;
    }

    /**
     * 描画 ID・ネットワーク ID が指す節。ID を節へ解決する境界はここ（と {@link HtmlNode#getNodeById}）だけで、
     * 状態や座標はこの先ずっとノードをキーにして持つ。文書が無い・該当が無いときは null。
     */
    public static HtmlNode findNode(String sessionId, String nodeId) {
        HtmlNode root = getRootNode(sessionId);
        return root != null ? root.getNodeById(nodeId) : null;
    }

    /**
     * 対話状態（hover / active / focus）の持ち主になる節。擬似要素（{@code x_before}）とその中の文字は
     * DOM に無いので、状態を持つ要素（{@code x}）へ読み替える。
     */
    public static HtmlNode findInteractiveNode(String sessionId, String baseId) {
        return resolveInteractiveNode(sessionId, baseId);
    }

    private static HtmlNode resolveInteractiveNode(String sessionId, String baseId) {
        if (baseId == null || baseId.isEmpty()) return null;
        HtmlNode node = findNode(sessionId, baseId);
        return node != null ? node : findNode(sessionId, generatedContentOwnerId(baseId));
    }

    private static String generatedContentOwnerId(String id) {
        String base = id.endsWith("_txt") ? id.substring(0, id.length() - "_txt".length()) : id;
        for (String suffix : new String[]{"_before", "_after", "_marker"}) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }

    /** フォーカス中の要素または入力の基底ノード ID が {@code baseId} と一致するか。 */
    public static boolean isFocusedBase(String sessionId, String baseId) {
        if (baseId == null || baseId.isEmpty()) return false;
        SessionContext session = getSession(sessionId);
        if (session != null && baseId.equals(session.focusedId())) {
            return true;
        }
        String active = getActiveInputId(sessionId);
        return !active.isEmpty() && getBaseNodeId(active).equals(baseId);
    }

    /** 押下中の要素の基底ノード ID が {@code baseId} と一致するか。 */
    public static boolean isPressedBase(String sessionId, String baseId) {
        String pressed = getActivePressedId(sessionId);
        return !pressed.isEmpty() && getBaseNodeId(pressed).equals(baseId);
    }

    /**
     * 測定に使う書体の出所。
     * 換算（{@code 16/12} の倍率）と太字の {@code §l} 前置は
     * {@link com.nishiyu.enderframelib.client.renderer.WebFontManager.McFontResolver} の内側に移した。
     */
    public static final FontResolver CLIENT_FONTS =
            com.nishiyu.enderframelib.client.renderer.WebFontManager.McFontResolver.INSTANCE;

    private static final String[] RENDER_SUFFIXES = {
            "_ghost_img", "_b_bottom", "_b_round", "_b_right", "_textarea", "_progress", "_b_left", "_select", "_canvas",
            "_b_top", "_o_bottom", "_o_right", "_o_left", "_o_top", "_sb_vt", "_sb_ht", "_scene", "_input", "_sb_v", "_sb_h",
            "_img", "_cb", "_bg", "_ph"
    };

    /**
     * 番号の付く部品: 線種で分けた枠線・輪郭線の 2 つ目以降の片（{@code _b_top_3}）、インラインボックスの
     * 断片ごとの枠線（{@code _b_left_7}・{@code _b_round_8}）、背景のレイヤーのタイル
     * （{@code _bgl0_5}）、影の層（{@code _shadow_2}、内側の影は {@code _shadow_in4}）。
     * 数が決まっていないので接尾辞の一覧では表せない。
     *
     * 何が壊れていたか: 以前は影の {@code _shadow_1..3} を剥がせず、影にカーソルが乗ると
     * 存在しないノード id でホバーとクリックが起きていた。
     */
    private static final Pattern NUMBERED_RENDER_PART = Pattern.compile(
            "_(?:[bo]_(?:top|right|bottom|left)_\\d+|b_round_\\d+|bgl\\d+_\\d+|shadow_(?:in)?\\d+)$");

    public static boolean initOrBindSession(String sessionId, String rawHtml, ResourceLocation htmlResource, int width, int height, boolean forceReload) {
        int targetW = width > 0 ? width : 854;
        int targetH = height > 0 ? height : 480;

        SessionContext ctx = getSession(sessionId);
        if (!forceReload && ctx != null && ctx.isServerManaged() && ctx.document() != null) {
            if (ctx.rootW() != targetW || ctx.rootH() != targetH) {
                recomputeLayout(sessionId, targetW, targetH);
            } else {
                requestRender(sessionId);
            }
            return false;
        }

        String resolvedHtml = rawHtml;
        if ((resolvedHtml == null || resolvedHtml.isEmpty()) && htmlResource != null) {
            resolvedHtml = UiDocumentCompiler.resourceTextResolver != null
                    ? UiDocumentCompiler.resourceTextResolver.apply(htmlResource.toString())
                    : "";
        }

        if (resolvedHtml != null && !resolvedHtml.isEmpty()) {
            loadLocalHtml(sessionId, resolvedHtml, targetW, targetH);
            return true;
        }

        SessionContext session = getOrCreateSession(sessionId);
        session.setServerManaged(true);
        ClientScreenInteractionManager.ensureSynced(sessionId);
        if (session.document() != null && (session.rootW() != targetW || session.rootH() != targetH)) {
            recomputeLayout(sessionId, targetW, targetH);
        }
        return false;
    }

    public static boolean isServerManagedSession(String sessionId) {
        SessionContext ctx = getSession(sessionId);
        return ctx != null && ctx.isServerManaged();
    }

    public static void recomputeAllLayouts() {
        for (SessionContext ctx : SESSIONS.values()) {
            if (ctx.document() != null) {
                recomputeLayout(ctx.sessionId(), ctx.rootW(), ctx.rootH());
            }
        }
    }

    public static void handleSyncPacket(String sessionId, CompoundTag tag) {
        if (tag.getBoolean("IsEmpty")) {
            clearSession(sessionId);
        } else if (tag.contains("DomTree")) {
            SessionContext session = getOrCreateSession(sessionId);
            session.setServerManaged(true);
            CompoundTag domTag = tag.getCompound("DomTree");
            String cssString = tag.getString("CssString");

            int currentHash = Objects.hash(domTag.hashCode(), cssString.hashCode());
            if (session.domHash() == currentHash && session.document() != null) {
                session.setRequested(false);
                return;
            }
            session.setDomHash(currentHash);

            int rootW = session.rootW() > 0 ? session.rootW() : (tag.contains("RootW") ? tag.getInt("RootW") : 854);
            int rootH = session.rootH() > 0 ? session.rootH() : (tag.contains("RootH") ? tag.getInt("RootH") : 480);

            HtmlNode rootNode = HtmlNodeSerializer.deserialize(domTag, null);
            installDocumentInternal(sessionId, rootNode, cssString, rootW, rootH, true);
        }

        SessionContext session = getSession(sessionId);
        if (session != null) {
            session.setRequested(false);
        }
    }

    public static void loadLocalHtml(String sessionId, String html, int width, int height) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        UiDocumentCompiler.Document parsedDoc = parser.parseDocument(html, "", width, height);
        installDocumentInternal(sessionId, parsedDoc.root, parsedDoc.authorCss, width, height, false);
    }

    private static void installDocumentInternal(String sessionId, HtmlNode rootNode, String authorCss, int rootW, int rootH, boolean fromServer) {
        SessionContext session = getOrCreateSession(sessionId);
        boolean existingServerDoc = fromServer && session.document() != null;

        // サーバーからの dom_sync で既存セッションがある場合は session.close() を呼ばず、
        // CssAnimator の補間状態や Canvas2DContext のピクセルバッファを維持したまま差分更新する。
        if (!existingServerDoc) {
            session.close();
        }

        int oldRootW = session.rootW();
        int oldRootH = session.rootH();
        session.setRootW(rootW);
        session.setRootH(rootH);
        if (fromServer) session.setServerManaged(true);

        UiDocumentCompiler parser = new UiDocumentCompiler();
        UiDocumentCompiler.Document doc = session.document();
        if (doc == null || !existingServerDoc) {
            StyleSheet sheet = parser.buildStyleSheet(authorCss, rootW, rootH);
            doc = new UiDocumentCompiler.Document(rootNode, sheet, authorCss);
            session.setDocument(doc);
        } else {
            if (!Objects.equals(doc.authorCss, authorCss) || doc.sheet == null) {
                doc.sheet = parser.buildStyleSheet(authorCss, rootW, rootH);
                doc.authorCss = authorCss;
            } else if (oldRootW != rootW || oldRootH != rootH) {
                // ビューポートだけが変わったときは CSS を読み直さず、@media の条件だけ評価し直す
                doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH));
            }
            adoptServerTree(doc.root, rootNode);
        }

        DeclarativeBindingManager.initializeBindings(sessionId, doc.root);
        ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, doc.root, doc.root);
        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);

        session.interactionState().markAllDirty();
        com.nishiyu.enderframelib.core.layout.LayoutBox rootBox = parser.layoutDocument(
                doc, rootW, rootH, session.env(), session.scrollState(), CLIENT_FONTS, session.interactionState());
        session.setLayoutRoot(rootBox);
        List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer != null
                ? UiDocumentCompiler.clientBoxRenderer.render(rootBox, rootW, rootH, doc.sheet.keyframes, session.scrollState())
                : new ArrayList<>();
        session.setRenderCache(elements);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
    }

    /**
     * 再同期で届いた全木を、生きている文書の中身にする。文書のハンドル（{@code doc.root}）は変えない。
     * クライアント所有（{@code data-owner="client"}）の部分木は捨てず、新しい木の同じ id の位置へ付け替える。
     * 新しい木に同じ id の要素が無ければ、その持ち主がサーバー側で消えたのでクライアント所有の部分木も一緒に消える。
     */
    private static void adoptServerTree(HtmlNode liveRoot, HtmlNode freshRoot) {
        if (liveRoot == freshRoot) return;

        Map<String, HtmlNode> clientIslands = new LinkedHashMap<>();
        collectClientOwnedIslands(liveRoot, clientIslands);

        for (HtmlNode stale : new ArrayList<>(liveRoot.children)) {
            liveRoot.removeChild(stale);
        }
        for (HtmlNode fresh : new ArrayList<>(freshRoot.children)) {
            liveRoot.appendChild(fresh);
        }

        for (Map.Entry<String, HtmlNode> island : clientIslands.entrySet()) {
            HtmlNode placeholder = liveRoot.getNodeById(island.getKey());
            if (placeholder != null && placeholder.parent != null) {
                placeholder.parent.replaceChild(island.getValue(), placeholder);
            }
        }
    }

    /** 最上位のクライアント所有の部分木（id を持つもの）を集める。その内側は島ごと運ぶので降りない。 */
    private static void collectClientOwnedIslands(HtmlNode node, Map<String, HtmlNode> islands) {
        for (HtmlNode child : node.children) {
            boolean declaredClientOwned = Ownership.parse(child.attrs.get(Ownership.ATTRIBUTE)) == Ownership.CLIENT;
            if (declaredClientOwned && !child.getId().isEmpty()) {
                islands.put(child.getId(), child);
            } else {
                collectClientOwnedIslands(child, islands);
            }
        }
    }

    public static void handlePatchPacket(String sessionId, CompoundTag data) {
        if (data == null) return;
        SessionContext session = getSession(sessionId);
        if (session == null || session.document() == null || session.document().root == null) return;
        HtmlNode root = session.document().root;

        List<com.nishiyu.enderframelib.network.DomMutationRecord> mutations = new ArrayList<>();
        if (data.contains("Mutations", Tag.TAG_LIST)) {
            ListTag list = data.getList("Mutations", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(com.nishiyu.enderframelib.network.DomMutationRecord.fromTag(list.getCompound(i)));
            }
        } else if (data.contains("Patches", Tag.TAG_LIST)) {
            ListTag list = data.getList("Patches", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                mutations.add(com.nishiyu.enderframelib.network.DomMutationRecord.fromTag(list.getCompound(i)));
            }
        }

        boolean anyChanged = false;
        for (com.nishiyu.enderframelib.network.DomMutationRecord mut : mutations) {
            // クライアント所有の部分木はサーバー変異の宛先にならない。サーバー所有なら、クライアントの書き込みを上書きして正になる。
            HtmlNode mutationTarget = root.getNodeById(mut.getTargetId());
            if (mutationTarget != null && mutationTarget.ownership() == Ownership.CLIENT) continue;

            String targetId = getBaseNodeId(mut.getTargetId());
            boolean applied = mut.applyTo(root, session.interactionState()::markDirty);
            if (applied) {
                anyChanged = true;
                HtmlNode target = root.getNodeById(targetId);
                if (mut.getType() == com.nishiyu.enderframelib.network.DomMutationRecord.Type.ATTRIBUTE) {
                    if ("value".equalsIgnoreCase(mut.getName())) {
                        ClientScreenInteractionManager.syncServerValueOverride(sessionId, targetId,
                                "remove".equalsIgnoreCase(mut.getMode()) ? "" : mut.getValue());
                    }
                    syncAttributeToInteractionState(session, target, mut.getName(), !"remove".equalsIgnoreCase(mut.getMode()));
                } else if (mut.getType() == com.nishiyu.enderframelib.network.DomMutationRecord.Type.INNER_HTML && target != null) {
                    DeclarativeBindingManager.initializeBindings(sessionId, target);
                }
            }
        }

        if (anyChanged) {
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, root, root);
            requestRender(sessionId);
        }
    }

    private static void syncAttributeToInteractionState(SessionContext session, HtmlNode node, String attrName, boolean present) {
        if (session == null || node == null || attrName == null) return;
        String lower = attrName.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "checked" -> session.interactionState().setChecked(node, present);
            case "disabled" -> session.interactionState().setDisabled(node, present);
            case "indeterminate" -> session.interactionState().setIndeterminate(node, present);
            case "open" -> session.interactionState().setOpen(node, present);
        }
    }

    public static HtmlNode getRootNode(String sessionId) {
        SessionContext session = getSession(sessionId);
        return (session != null && session.document() != null) ? session.document().root : null;
    }

    public static com.nishiyu.enderframelib.api.dom.Document getDomDocument(String sessionId) {
        SessionContext session = getSession(sessionId);
        if (session != null && session.document() != null && session.document().root instanceof com.nishiyu.enderframelib.api.dom.Document doc) {
            return doc;
        }
        return null;
    }

    /** 状態変化に伴う部分木の再スタイル（必要に応じて再レイアウトまたは再描画へ分岐）を要求する。 */
    public static void requestRestyle(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        SessionContext session = getSession(sessionId);
        if (session != null) {
            session.markInvalidation(SessionContext.InvalidationLevel.RESTYLE);
        }
        DIRTY_SESSIONS.add(sessionId);
    }

    /** DOM 変更やビューポート変更に伴う再レイアウトを要求する。 */
    public static void requestRender(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        SessionContext session = getSession(sessionId);
        if (session != null) {
            session.markInvalidation(SessionContext.InvalidationLevel.RELAYOUT);
        }
        DIRTY_SESSIONS.add(sessionId);
    }

    public static void processRenderQueue() {
        ClientScheduler.onRenderFrame(System.currentTimeMillis());
        if (DIRTY_SESSIONS.isEmpty()) return;
        for (String sessionId : new ArrayList<>(DIRTY_SESSIONS)) {
            DIRTY_SESSIONS.remove(sessionId);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
            SessionContext session = getSession(sessionId);
            if (session == null || session.document() == null || session.document().root == null) {
                continue;
            }

            SessionContext.InvalidationLevel level = session.consumeInvalidationLevel();
            if (session.layoutRoot() == null) {
                level = SessionContext.InvalidationLevel.RELAYOUT;
                session.interactionState().markAllDirty();
            }

            switch (level) {
                case RELAYOUT -> recomputeLayout(sessionId, session.rootW(), session.rootH());
                case RESTYLE -> {
                    UiDocumentCompiler parser = new UiDocumentCompiler();
                    UiDocumentCompiler.RestyleResult res = parser.restyleLayoutTree(
                            session.layoutRoot(), session.document(),
                            session.rootW(), session.rootH(),
                            session.env(), session.interactionState());
                    if (res.layoutRequired()) {
                        recomputeLayout(sessionId, session.rootW(), session.rootH());
                    } else if (res.styleChanged()) {
                        repaintFromLayout(session);
                    }
                }
                case REPAINT -> repaintFromLayout(session);
                case NONE -> {
                    if (session.interactionState().hasDirtyNodes()) {
                        UiDocumentCompiler parser = new UiDocumentCompiler();
                        UiDocumentCompiler.RestyleResult res = parser.restyleLayoutTree(
                                session.layoutRoot(), session.document(),
                                session.rootW(), session.rootH(),
                                session.env(), session.interactionState());
                        if (res.layoutRequired()) {
                            recomputeLayout(sessionId, session.rootW(), session.rootH());
                        } else if (res.styleChanged()) {
                            repaintFromLayout(session);
                        }
                    }
                }
            }
        }
    }

    private static void repaintFromLayout(SessionContext session) {
        if (session == null || session.layoutRoot() == null || session.document() == null) return;
        if (UiDocumentCompiler.clientBoxRenderer == null) return;
        List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer.render(
                session.layoutRoot(),
                session.rootW(),
                session.rootH(),
                session.document().sheet.keyframes,
                session.scrollState());
        session.setRenderCache(elements);
        if (applyOverridesCallback != null) applyOverridesCallback.accept(session.sessionId());
    }

    public static void recomputeLayout(String sessionId, int newRootW, int newRootH) {
        SessionContext session = getSession(sessionId);
        if (session == null) return;
        int oldRootW = session.rootW();
        int oldRootH = session.rootH();
        session.setRootW(newRootW);
        session.setRootH(newRootH);

        if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);

        UiDocumentCompiler.Document doc = session.document();
        if (doc != null && doc.root != null) {
            UiDocumentCompiler parser = new UiDocumentCompiler();

            // @media はシートを読み直さず、新しいビューポートで条件だけ評価し直す
            if (doc.sheet == null) {
                doc.sheet = parser.buildStyleSheet(doc.authorCss, newRootW, newRootH);
                session.interactionState().markAllDirty();
            } else if (oldRootW != newRootW || oldRootH != newRootH) {
                boolean mediaChanged = doc.sheet.updateMediaEnvironment(
                        doc.sheet.mediaEnvironment().withSize(newRootW, newRootH));
                if (mediaChanged) session.interactionState().markAllDirty();
            }

            com.nishiyu.enderframelib.core.layout.LayoutBox rootBox = parser.layoutDocument(
                    doc, newRootW, newRootH, session.env(), session.scrollState(), CLIENT_FONTS, session.interactionState());
            session.setLayoutRoot(rootBox);
            List<DisplayItem> elements = UiDocumentCompiler.clientBoxRenderer != null
                    ? UiDocumentCompiler.clientBoxRenderer.render(rootBox, newRootW, newRootH, doc.sheet.keyframes, session.scrollState())
                    : new ArrayList<>();
            session.setRenderCache(elements);
            if (applyOverridesCallback != null) applyOverridesCallback.accept(sessionId);
        }
    }

    public static List<DisplayItem> getElements(String sessionId) {
        SessionContext session = getSession(sessionId);
        if (session != null && session.document() != null && session.document().root != null) {
            HtmlNode root = session.document().root;
            ClientLocalEnvProvider.tickSessionLocalVariables(sessionId, root, root);
        }
        if (!DIRTY_SESSIONS.isEmpty()) {
            processRenderQueue();
        }
        return session != null ? session.renderCache() : Collections.emptyList();
    }

    public static boolean shouldRequestSync(String sessionId) {
        SessionContext session = getOrCreateSession(sessionId);
        long now = System.currentTimeMillis();
        long lastReq = session.lastSyncRequestTime();
        if (now - lastReq > 2000) {
            session.setLastSyncRequestTime(now);
            return true;
        }
        return false;
    }

    public static String getBaseNodeId(String renderId) {
        if (renderId == null || renderId.isEmpty()) return "";

        Matcher numbered = NUMBERED_RENDER_PART.matcher(renderId);
        if (numbered.find() && numbered.start() > 0) return renderId.substring(0, numbered.start());
        for (String suffix : RENDER_SUFFIXES) {
            if (renderId.length() > suffix.length() && renderId.endsWith(suffix)) {
                return renderId.substring(0, renderId.length() - suffix.length());
            }
        }
        return renderId;
    }

    public static DisplayItem getElementById(String sessionId, String id) {
        if (id == null) return null;
        String targetBase = getBaseNodeId(id);
        DisplayItem fallback = null;
        SessionContext session = getSession(sessionId);
        List<DisplayItem> elements = session != null ? session.renderCache() : Collections.emptyList();
        for (DisplayItem el : elements) {
            if (el.id().equals(id) && el.type() != ElementType.NODE) {
                return el;
            }
            if (targetBase.equals(getBaseNodeId(el.id()))) {
                if (el.type() == ElementType.NODE || el.id().endsWith("_ph") || el.id().contains("_shadow") || el.id().contains("_b_") || el.id().contains("_o_") || el.id().contains("_bgl")) {
                    if (fallback == null) fallback = el;
                    continue;
                }
                return el;
            }
        }
        return fallback;
    }

    public static void updateNodeAttributeLocally(String sessionId, String elementId, String attrName, String attrValue, boolean remove) {
        String baseId = getBaseNodeId(elementId);
        HtmlNode target = findNode(sessionId, baseId);
        if (target == null) return;

        DomUtils.updateAttribute(target, attrName, attrValue, remove);
        if ("value".equalsIgnoreCase(attrName)) {
            updateElementTextLocally(sessionId, baseId, remove || attrValue == null ? "" : attrValue);
        }
        SessionContext session = getSession(sessionId);
        if (session != null) {
            syncAttributeToInteractionState(session, target, attrName, !remove);
            session.interactionState().markDirty(target);
        }
        requestRender(sessionId);
    }

    public static void updateNodeInnerHtmlLocally(String sessionId, String elementId, String html) {
        HtmlNode target = findNode(sessionId, getBaseNodeId(elementId));
        if (target == null) return;

        target.setInnerHTML(html);
        DeclarativeBindingManager.initializeBindings(sessionId, target);
        SessionContext session = getSession(sessionId);
        if (session != null) session.interactionState().markDirty(target);
        requestRender(sessionId);
    }

    public static void updateNodeStyleLocally(String sessionId, String elementId, String prop, String val) {
        HtmlNode target = findNode(sessionId, getBaseNodeId(elementId));
        if (target == null) return;

        target.setStyleProperty(prop, val);
        SessionContext session = getSession(sessionId);
        if (session != null) session.interactionState().markDirty(target);
        requestRender(sessionId);
    }

    public static boolean handleScrollLocally(String sessionId, double pixelX, double pixelY, double scrollX, double scrollY) {
        List<DisplayItem> elements = getElements(sessionId);
        float sens = EnderFrameBuiltInConfig.scrollSensitivity;

        for (int i = elements.size() - 1; i >= 0; i--) {
            DisplayItem el = elements.get(i);
            if (el.events() != null && el.events().containsKey(RenderKeys.MC_SCROLLABLE)) {
                CssAnimator.AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, el);
                double[] localPt = inverseTransform(pixelX, pixelY, el, anim);
                double localX = localPt[0] - el.x();
                double localY = localPt[1] - el.y();

                if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                    String[] maxStrs = el.events().get(RenderKeys.MC_SCROLLABLE).split(",");
                    float maxW = Float.parseFloat(maxStrs[0]), maxH = Float.parseFloat(maxStrs[1]);

                    SessionContext session = getOrCreateSession(sessionId);
                    ScrollState state = session.scrollState();
                    // 何が壊れていたか: ここだけが描画 ID (foo_bg など) で読み書きしていた。
                    // 読み手 (LayoutBox / DisplayListBuilder) はベースノード ID を使うため、
                    // スクロール可能な部分要素が _bg だったコンテナはホイールが無反応だった。
                    HtmlNode scrollNode = findNode(sessionId, el.baseId());
                    if (scrollNode == null) continue;
                    float curX = state.getScrollX(scrollNode);
                    float curY = state.getScrollY(scrollNode);

                    float limitX = Math.max(0, maxW - el.width()), limitY = Math.max(0, maxH - el.height());
                    if (limitX > 0 || limitY > 0) {
                        float nextX = Mth.clamp(curX - (float) scrollX * sens, 0, limitX);
                        float nextY = Mth.clamp(curY - (float) scrollY * sens, 0, limitY);

                        if (Math.abs(nextX - curX) > 0.1f || Math.abs(nextY - curY) > 0.1f) {
                            state.setScroll(scrollNode, (int) nextX, (int) nextY);
                            requestRender(sessionId);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean handleScrollbarClick(String sessionId, DisplayItem clickedEl, double pixelX, double pixelY) {
        if (clickedEl == null || clickedEl.id() == null) return false;
        String id = clickedEl.id();
        boolean isVTrack = id.endsWith("_sb_vt");
        boolean isHTrack = id.endsWith("_sb_ht");
        boolean isVThumb = id.endsWith("_sb_v");
        boolean isHThumb = id.endsWith("_sb_h");
        if (!isVTrack && !isHTrack && !isVThumb && !isHThumb) return false;

        SessionContext session = getSession(sessionId);
        if (session == null || session.layoutRoot() == null) return false;

        HtmlNode scrollNode = findNode(sessionId, getBaseNodeId(id));
        com.nishiyu.enderframelib.core.layout.LayoutBox box = scrollNode != null ? session.layoutRoot().findByNode(scrollNode) : null;
        if (box == null) return false;

        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState scrollState = session.scrollState();

        if (isVTrack) {
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / (float) Math.max(1, box.scrollHeight)));
            float availH = Math.max(1, trackH - thumbH);
            float ratio = Math.clamp((float) (pixelY - trackY - thumbH / 2.0) / (float) availH, 0f, 1f);
            float maxScrollY = Math.max(0, box.scrollHeight - box.h);
            float curX = scrollState.getScrollX(scrollNode);
            scrollState.setScroll(scrollNode, (int) curX, (int) (ratio * maxScrollY));
            requestRender(sessionId);
            return true;
        } else if (isHTrack) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = scrollState.getScrollY(scrollNode);
            scrollState.setScroll(scrollNode, (int) (ratio * maxScrollX), (int) curY);
            requestRender(sessionId);
            return true;
        }
        return false;
    }

    public static boolean handleScrollbarDrag(String sessionId, String draggingId, double pixelX, double pixelY) {
        if (draggingId == null || draggingId.isEmpty()) return false;
        boolean isVThumb = draggingId.endsWith("_sb_v") || draggingId.endsWith("_sb_vt");
        boolean isHThumb = draggingId.endsWith("_sb_h") || draggingId.endsWith("_sb_ht");
        if (!isVThumb && !isHThumb) return false;

        SessionContext session = getSession(sessionId);
        if (session == null || session.layoutRoot() == null) return false;

        HtmlNode scrollNode = findNode(sessionId, getBaseNodeId(draggingId));
        com.nishiyu.enderframelib.core.layout.LayoutBox box = scrollNode != null ? session.layoutRoot().findByNode(scrollNode) : null;
        if (box == null) return false;

        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState scrollState = session.scrollState();

        if (isVThumb) {
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / (float) Math.max(1, box.scrollHeight)));
            float availH = Math.max(1, trackH - thumbH);
            float ratio = Math.clamp((float) (pixelY - trackY - thumbH / 2.0) / (float) availH, 0f, 1f);
            float maxScrollY = Math.max(0, box.scrollHeight - box.h);
            float curX = scrollState.getScrollX(scrollNode);
            scrollState.setScroll(scrollNode, (int) curX, (int) (ratio * maxScrollY));
            requestRender(sessionId);
            return true;
        } else if (isHThumb) {
            float trackX = box.x + box.bL + 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / (float) Math.max(1, box.scrollWidth)));
            float availW = Math.max(1, trackW - thumbW);
            float ratio = Math.clamp((float) (pixelX - trackX - thumbW / 2.0) / (float) availW, 0f, 1f);
            float maxScrollX = Math.max(0, box.scrollWidth - box.w);
            float curY = scrollState.getScrollY(scrollNode);
            scrollState.setScroll(scrollNode, (int) (ratio * maxScrollX), (int) curY);
            requestRender(sessionId);
            return true;
        }
        return false;
    }

    public static double[] inverseTransform(double px, double py, DisplayItem el, CssAnimator.AnimatedValues anim) {
        DisplayItem.RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;

        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        double dx = px - cx - anim.tx();
        double dy = py - cy - anim.ty();
        if (anim.rot() != 0.0f) {
            double rad = -Math.toRadians(anim.rot());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double nx = dx * cos - dy * sin;
            double ny = dx * sin + dy * cos;
            dx = nx;
            dy = ny;
        }
        if (anim.sx() != 0 && anim.sx() != 1.0f) dx /= anim.sx();
        if (anim.sy() != 0 && anim.sy() != 1.0f) dy /= anim.sy();

        return new double[]{dx + cx, dy + cy};
    }

    public static UIHitResult getHitElement(String sessionId, double pixelX, double pixelY) {
        SessionContext session = getSession(sessionId);
        if (session == null) return null;
        return session.hitTestIndex().hitTest(sessionId, pixelX, pixelY);
    }

    public static UIHitResult getLocalCoords(String sessionId, String elementId, double pixelX, double pixelY) {
        DisplayItem el = getElementById(sessionId, elementId);
        if (el == null) return null;
        SessionContext session = getSession(sessionId);
        if (session == null) return null;
        return session.hitTestIndex().getLocalCoords(sessionId, el, pixelX, pixelY);
    }

    public static String resolveSelectDisplayLabel(HtmlNode selectNode, String currentVal) {
        if (selectNode == null) return currentVal != null ? currentVal : "";
        String val = currentVal != null ? currentVal : selectNode.attrs.getOrDefault("value", "");
        String firstLabel = null;
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                String optVal = child.attrs.getOrDefault("value", child.getTextContent().trim());
                String optLabel = child.getTextContent().trim();
                if (firstLabel == null) firstLabel = optLabel.isEmpty() ? optVal : optLabel;
                if (val.isEmpty() || val.equals(optVal)) {
                    return optLabel.isEmpty() ? optVal : optLabel;
                }
            }
        }
        return firstLabel != null ? firstLabel : val;
    }

    public static void updateElementTextLocally(String sessionId, String elementId, String newText) {
        String safeText = newText != null ? newText : "";
        String baseId = getBaseNodeId(elementId);

        HtmlNode target = findNode(sessionId, baseId);
        boolean isRange = target != null && "range".equalsIgnoreCase(target.attrs.getOrDefault("type", ""));
        boolean isSelect = target != null && "select".equalsIgnoreCase(target.tag);

        String domValue = safeText;
        if (isRange && safeText.contains(":")) {
            domValue = safeText.split(":")[0];
        }

        if (target != null && !Objects.equals(target.attrs.get("value"), domValue)) {
            DomUtils.updateAttribute(target, "value", domValue, false);
        }
        SessionContext session = getSession(sessionId);
        List<DisplayItem> elements = session != null ? session.renderCache() : null;
        if (elements != null && !elements.isEmpty()) {
            String phId = baseId + "_ph";
            String inputRenderId = baseId + "_input";
            String textareaRenderId = baseId + "_textarea";
            String selectRenderId = baseId + "_select";
            String placeholderOrig = target != null ? target.attrs.getOrDefault("placeholder", "") : "";

            List<DisplayItem> updated = null;
            for (int i = 0; i < elements.size(); i++) {
                DisplayItem el = elements.get(i);
                if (el.id().equals(elementId) || el.id().equals(inputRenderId) || el.id().equals(textareaRenderId) || el.id().equals(selectRenderId)) {
                    String renderText = safeText;
                    if ((el.type() == ElementType.RANGE || el.type() == ElementType.PROGRESS) && !safeText.contains(":")) {
                        String min = target != null ? target.attrs.getOrDefault("min", "0") : "0";
                        String max = target != null ? target.attrs.getOrDefault("max", "100") : "100";
                        renderText = safeText + ":" + min + ":" + max;
                    } else if (el.type() == ElementType.SELECT || isSelect) {
                        renderText = resolveSelectDisplayLabel(target, domValue) + " \u25be";
                    }
                    if (!Objects.equals(el.text(), renderText)) {
                        if (updated == null) updated = new ArrayList<>(elements);
                        updated.set(i, el.withText(renderText));
                    }
                } else if (el.id().equals(phId)) {
                    String nextPh = domValue.isEmpty() ? placeholderOrig : "";
                    if (!Objects.equals(el.text(), nextPh)) {
                        if (updated == null) updated = new ArrayList<>(elements);
                        updated.set(i, el.withText(nextPh));
                    }
                }
            }
            if (updated != null) {
                session.setRenderCache(updated);
            }
        }
    }

    /** セッションの登録を外し、そのセッションが持つ全状態を片付ける。 */
    public static void clearSession(String sessionId) {
        if (sessionId == null) return;
        DIRTY_SESSIONS.remove(sessionId);
        SessionContext ctx = SESSIONS.remove(sessionId);
        if (ctx != null) {
            ctx.close();
        }
    }

    public static UiDocumentCompiler.Document getDocument(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.document() : null;
    }

    public static boolean hasDocument(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null && session.document() != null;
    }

    public static int getLastRootW(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.rootW() : 1920;
    }

    public static int getLastRootH(String sessionId) {
        SessionContext session = getSession(sessionId);
        return session != null ? session.rootH() : 1080;
    }

    public record UIHitResult(DisplayItem element, double localX, double localY) {}

    public static final class DeclarativeBindingManager {

        public interface IHudRegistrationCallback {
            void registerHudTemplate(String sessionId, String containerId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt);
            void registerTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt);
        }

        public static IHudRegistrationCallback hudCallback = null;

        private DeclarativeBindingManager() {}

        public static void setHudCallback(IHudRegistrationCallback callback) {
            hudCallback = callback;
        }

        public static void initializeBindings(String sessionId, HtmlNode rootNode) {
            if (rootNode == null) return;

            if (rootNode.attrs.containsKey("data-bind")) {
                String bindDefinition = rootNode.attrs.get("data-bind");
                String targetAttr = "text";
                String path = bindDefinition;

                if (bindDefinition.contains(":")) {
                    String[] parts = bindDefinition.split(":", 2);
                    targetAttr = parts[0].trim();
                    path = parts[1].trim();
                }
                String channel = resolveChannel(rootNode, path);
                ClientPubSubManager.registerDataBinding(sessionId, rootNode.getId(), channel, path, targetAttr);
                ClientPubSubManager.requestSubscribe(sessionId, channel);
            }

            if (rootNode.attrs.containsKey("data-list")) {
                String listPath = rootNode.attrs.get("data-list");
                HtmlNode templateNode = findTemplateChild(rootNode);

                if (templateNode != null) {
                    int itemW = 36;
                    int itemH = 36;
                    try { itemW = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-width", "36")); } catch (Exception ignored) {}
                    try { itemH = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-height", "36")); } catch (Exception ignored) {}
                    String channel = resolveChannel(rootNode, listPath);
                    ClientPubSubManager.registerVirtualList(sessionId, rootNode.getId(), channel, templateNode, itemW, itemH);
                    ClientPubSubManager.requestSubscribe(sessionId, channel);
                }
            }

            if (rootNode.attrs.containsKey("data-hud") && hudCallback != null) {
                String origDisplay = rootNode.attrs.getOrDefault("data-hud-display", "flex");
                java.util.List<String> requireNbt = new java.util.ArrayList<>();
                if (rootNode.attrs.containsKey("data-hud-require-nbt")) {
                    String[] nbtKeys = rootNode.attrs.get("data-hud-require-nbt").split(",");
                    for (String key : nbtKeys) requireNbt.add(key.trim());
                }
                hudCallback.registerHudTemplate(sessionId, rootNode.getId(), origDisplay, rootNode, requireNbt);
            }

            if (rootNode.attrs.containsKey("data-tracker") && hudCallback != null) {
                double radius = 24.0;
                double yOffset = 0.8;
                String targetType = "living";
                java.util.List<String> requireNbt = new java.util.ArrayList<>();

                if (rootNode.attrs.containsKey("data-tracker-radius")) radius = Double.parseDouble(rootNode.attrs.get("data-tracker-radius"));
                if (rootNode.attrs.containsKey("data-tracker-y-offset")) yOffset = Double.parseDouble(rootNode.attrs.get("data-tracker-y-offset"));
                if (rootNode.attrs.containsKey("data-tracker-type")) targetType = rootNode.attrs.get("data-tracker-type");
                if (rootNode.attrs.containsKey("data-tracker-require-nbt")) {
                    String[] nbtKeys = rootNode.attrs.get("data-tracker-require-nbt").split(",");
                    for (String key : nbtKeys) requireNbt.add(key.trim());
                }

                hudCallback.registerTrackerTemplate(sessionId, rootNode, radius, yOffset, targetType, requireNbt);
            }

            for (HtmlNode child : rootNode.children) {
                initializeBindings(sessionId, child);
            }
        }

        private static String resolveChannel(HtmlNode node, String bindKey) {
            String attrName = "data-channel-" + bindKey.toLowerCase(java.util.Locale.ROOT);
            HtmlNode current = node;
            while (current != null) {
                if (current.attrs.containsKey(attrName)) {
                    return current.attrs.get(attrName);
                }
                current = current.parent;
            }
            return Objects.requireNonNull(node).attrs.getOrDefault("data-channel", bindKey);
        }

        private static HtmlNode findTemplateChild(HtmlNode parent) {
            for (HtmlNode child : parent.children) {
                if (child.tag.equals("template")) return child;
            }
            return null;
        }
    }
}
