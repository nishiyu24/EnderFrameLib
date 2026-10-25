package com.nishiyu.enderframelib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * core/ が Minecraft / NeoForge / 上位層に依存しない純 Java のリーフであることを守る番人。
 * これが無いと、次に追加されたコードで MC への import がすぐ戻ってくる。
 *
 * <p>あわせて STRUCTURE_PLAN.md の T0 で決めた管理指標（M1〜M9）を上限値として持つ。
 * ここに書いた数は「今の作業ツリーの実測値」で、フェーズを進めて数を減らしたら
 * 同じコミットで上限も下げる。増えた場合は CI で落ちる。</p>
 */
public class ArchitectureTest {

    private static final Path MAIN = Paths.get("src/main/java/com/nishiyu/enderframelib");
    private static final Path CORE = MAIN.resolve("core");
    private static final Path TEST = Paths.get("src/test/java/com/nishiyu/enderframelib");

    /** M1: セッション ID をキーにした static な状態を持つクラス（2026-10-20 時点: 1。ClientWebManager の登録簿のみ）。 */
    private static final int M1_MAX_SESSION_STATE_OWNERS = 1;
    /** M2: フォーカス状態の持ち場（2026-10-20 時点: 1。InteractionState のみ）。 */
    private static final int M2_MAX_FOCUS_STATE_OWNERS = 1;
    /** M3: ClientWebManager の public static メソッド（2026-10-20 時点: 8）。 */
    private static final int M3_MAX_CLIENT_WEB_MANAGER_PUBLIC_STATIC = 8;
    /** M4: テンプレート・バインディングを解釈するクラス（2026-10-21 時点: 1。Bindings のみ）。 */
    private static final int M4_MAX_BINDING_INTERPRETERS = 1;
    /** M5: スタイル→ボックス→レイアウト→ディスプレイリストを組む場所（2026-10-20 時点: 2）。 */
    private static final int M5_MAX_PIPELINE_BUILDERS = 1;
    /** M6: 入力をイベントに変える経路（2026-10-21 時点: 1。SessionInput のみ）。 */
    private static final int M6_MAX_INPUT_ENTRY_POINTS = 1;
    /** M7: 2 つ以上のパッケージから参照される入れ子型（2026-10-20 時点: 26。api は公開面のまとまりとして除外）。 */
    private static final int M7_MAX_CROSS_PACKAGE_NESTED_TYPES = 0;
    /** M8: CSS 値を文字列で分割する自前の関数（2026-10-20 時点: 8）。 */
    private static final int M8_MAX_CSS_SPLIT_HELPERS = 8;
    /**
     * M9: トップレベルのファイル数（参考値。2026-10-21 時点: 127。SessionInput・EnderScreenHost を追加）。
     * 2026-10-22: システムの書体で文字を測り描く仕組みとして +5（core/text/font の SystemFonts・LoadedFace・
     * OutlineFontFace・KerningTable と client/renderer の OutlineTextRenderer）。一覧・照合・総称ファミリ・解決は
     * SystemFonts に、FreeType の扱いは LoadedFace にまとめ、パッケージをまたいで使う型だけをトップレベルにした。
     */
    // 2026-10-23: Headless State フェーズ H1（Headless Server State）の導入に伴い +7。
    // api/state/UIState, server/state/UIStateImpl, server/state/UIStateManager, StateNbtConverter（2026-10-24 に network/ へ移動）,
    // network/OpenUiS2CPacket, network/StateDeltaS2CPacket, network/UiActionC2SPacket を追加。
    // 2026-10-24: Headless State フェーズ H2（Headless State Client Template）の導入に伴い +1。
    // client/template/ClientTemplateManager を追加。
    // 2026-10-24: Headless State フェーズ H3（機械向け高水準 API とコンテナ連携）の導入に伴い +1。
    // client/template/ParsedTemplate を追加（M7 準拠のためトップレベルへ切り出し）。
    // 2026-10-24: Headless State フェーズ H4（後方互換性アダプタと古い二重経路の整理）の導入に伴い +2。
    // server/state/UIStateSessionAdapter, server/state/WebSessionUIStateAdapter を追加。
    // 2026-10-24: 表面描画の整理（SurfaceGeometry / SurfaceHitResolver）と、外部 MOD 向けひな形 API
    // （api/template の基底クラス、ホログラム、アイテム面の設定）の追加に伴い +12。
    // 2026-10-25: API の整理（Ui / ClientSession / Surfaces / SurfaceStyle / SurfaceRenderer / Hologram の追加、
    // EnderScreenClient / EnderScreenItemClient / 互換アダプタ 2 つの削除）で差し引き +1。
    // 2026-10-25: テンプレートで動く世界内の面（server/SurfaceViewers）の追加で +1。
    // 2026-10-25: ホログラムの入力対応で SurfaceHit を client/ のトップレベルに出した（M7 準拠）ので +1。
    // 2026-10-25: Phase 1 / T8 でコマンドクラスから server/HologramRegistry（ホログラム状態）を切り出したので +1。
    private static final int M9_MAX_TOP_LEVEL_FILES = 209;

    /** M1 拡張: シングルトンの static フィールドに持つ画面・セッション単位のコールバック（Phase 1 / T8 で 0 にした。2026-10-25）。 */
    private static final int M1X_MAX_STATIC_CALLBACK_FIELDS = 0;
    /** M1 拡張: コマンド定義クラス内のサーバー状態コレクション（Phase 1 / T8 で server/HologramRegistry へ移し 0 にした。2026-10-25）。 */
    private static final int M1X_MAX_COMMAND_STATE_COLLECTIONS = 0;
    /** DOM 属性の直接書き換え（node/・パーサ・直列化の復元・CustomTagExpander の新規ノード構築を除く。Phase 1 / T8 で 0 にした。2026-10-25）。 */
    private static final int DOM_MAX_DIRECT_ATTR_WRITES = 0;
    /** R6: RenderKeys.java 以外からの RenderKeys 参照行数（2026-10-25 時点: 48。Phase 3 で 0 にする）。 */
    private static final int R6_MAX_RENDER_KEYS_REFERENCES = 48;
    /** R6: DisplayListBuilder 内の Pattern.compile の数（2026-10-25 時点: 8。Phase 3 で 0 にする）。 */
    private static final int R6_MAX_DISPLAY_LIST_BUILDER_REGEX = 8;

    /** core/ から参照してはいけないパッケージ（import でも完全修飾名でも禁止） */
    private static final String[] FORBIDDEN = {
            "net.minecraft.",
            "net.neoforged.",
            "com.mojang.",
            "com.nishiyu.enderframelib.client.",
            "com.nishiyu.enderframelib.server.",
            "com.nishiyu.enderframelib.common.",
            "com.nishiyu.enderframelib.network.",
    };

    /**
     * 消した型の名前。文書に残ると、利用者が存在しないクラスを探すことになる。
     * STRUCTURE_PLAN.md は「これから消すもの」を書く計画なので対象外。
     */
    private static final List<String> REMOVED_TYPE_NAMES = List.of(
            "UIRenderer",
            "UIElement",
            "WebStateStore",
            "DomDiffEngine",
            "InteractionHandler",
            "DeclarativeBindingManager",
            "WebViewport",
            "WebActionRouter",
            "DragDropController",
            "SelectDropdownWidget",
            "WebRenderCore",
            "WebAnimator",
            "WebCanvasContext",
            "WebTextareaEditor",
            "IWebActionReceiver",
            "IWebScreenBlockEntity",
            "IWebSessionProvider",
            "TemplateEngine",
            "core.model",
            "DomEvent",
            "UiEvents"
    );

    private static final List<Path> DOCUMENTS = List.of(
            Paths.get("README.md"),
            Paths.get("CONTRIBUTING.md"),
            Paths.get("FIDELITY.md"),
            Paths.get("REFACTORING_PLAN.md"),
            Paths.get("ARCHITECTURE.md"),
            Paths.get("BENCHMARK.md")
    );

    /** S4 の入力経路。ここが増えたら入力の直し漏れがまた起きる。 */
    private static final List<String> INPUT_ENTRY_POINTS = List.of(
            "client/SessionInput.java"
    );

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLEAR_SESSION = Pattern.compile("\\bstatic\\s+void\\s+clearSession(?:State)?\\s*\\(");
    private static final Pattern FOCUS_STATE_FIELD =
            Pattern.compile("\\b(?:String|Element|HtmlNode)\\s+(?:focusedElementBaseId|activeElement|focusedNode)\\b");
    private static final Pattern NESTED_TYPE = Pattern.compile(
            "(?m)^\\s+(?:@[\\w.]+\\s+)*(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?"
                    + "(?:abstract\\s+)?(?:sealed\\s+)?(?:non-sealed\\s+)?(?:class|interface|record|enum)\\s+"
                    + "([A-Za-z_$][\\w$]*)");
    private static final Pattern SPLIT_METHOD = Pattern.compile(
            "\\bstatic\\s+(?:final\\s+)?[\\w.$<>\\[\\],? ]+?\\s+(split\\w*)\\s*\\(\\s*(?:final\\s+)?String\\b");
    private static final Pattern NEW_LAYOUT_BOX = Pattern.compile("\\bnew\\s+LayoutBox\\s*\\(");
    private static final Pattern MEASURE_CALL = Pattern.compile("\\.measure\\s*\\(");

    @Test
    public void testCorePackageIsPureJava() throws IOException {
        // パスが見つからない＝実行ディレクトリが想定外。黙ってスキップすると番人が無力化するので失敗させる
        assertTrue(Files.isDirectory(CORE),
                "core パッケージが見つかりません: " + CORE.toAbsolutePath()
                        + " (テストはプロジェクトルートを作業ディレクトリとして実行してください)");

        List<String> violations = new ArrayList<>();

        for (Path path : javaFiles(CORE)) {
            String content = Files.readString(path);
            for (String forbidden : FORBIDDEN) {
                if (content.contains(forbidden)) {
                    violations.add(CORE.relativize(path) + " → " + forbidden);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("【アーキテクチャ違反】 core/ が外部(Minecraft/NeoForge/上位層)に依存しています:\n  "
                    + String.join("\n  ", violations)
                    + "\n依存が必要な場合は core/ 側にインターフェース（フック）を定義し、上位層から注入してください。");
        }
    }

    /**
     * client/ は server/ を参照しない（import でも完全修飾名でも）。両側で使う型は network/ か common/ に置く。
     *
     * 何が壊れていたか: Headless State の NBT 変換（StateNbtConverter）が server/state にあり、
     * クライアントのパケット処理が完全修飾名で server を参照していた。core の番人だけでは見えなかった。
     */
    @Test
    public void testClientDoesNotDependOnServer() throws IOException {
        Path client = MAIN.resolve("client");
        assertTrue(Files.isDirectory(client), "client パッケージが見つかりません: " + client.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        for (Path path : javaFiles(client)) {
            if (Files.readString(path).contains("com.nishiyu.enderframelib.server.")) {
                violations.add(mainRelative(path));
            }
        }
        assertTrue(violations.isEmpty(),
                "【アーキテクチャ違反】 client/ が server/ に依存しています。両側で使う型は network/ か common/ へ移してください:\n  "
                        + String.join("\n  ", violations));
    }

    // ------------------------------------------------------------------
    // T0 のラチェット（M1〜M9）
    // ------------------------------------------------------------------

    @Test
    public void testM1SessionStateOwnersDoNotGrow() throws IOException {
        List<String> owners = new ArrayList<>();
        for (Path path : javaFiles(MAIN.resolve("client"))) {
            if (CLEAR_SESSION.matcher(Files.readString(path)).find()) {
                owners.add(relative(path));
            }
        }
        assertAtMost("M1 セッション状態の static な持ち主", M1_MAX_SESSION_STATE_OWNERS, owners,
                "セッションごとの状態は SessionContext のフィールドにしてください（STRUCTURE_PLAN S1）。");
    }

    @Test
    public void testM1xNoStaticCallbackFieldsInSingletons() throws IOException {
        Pattern field = Pattern.compile("(?m)^\\s*(?:private|public|protected)?\\s*static\\s+(?:volatile\\s+)?"
                + "(?!final\\b)\\w*Callback\\w*\\s+\\w+\\s*(?:=|;)");
        List<String> found = new ArrayList<>();
        for (Path path : javaFiles(MAIN.resolve("client"))) {
            Matcher matcher = field.matcher(Files.readString(path));
            while (matcher.find()) found.add(relative(path) + " → " + matcher.group().trim());
        }
        assertAtMost("M1拡張 static なコールバックフィールド", M1X_MAX_STATIC_CALLBACK_FIELDS, found,
                "画面・セッションごとのコールバックは SessionContext（または sessionId ごとの登録簿）に持たせてください（REFACTORING_PLAN Phase 1 / T8）。");
    }

    @Test
    public void testM1xCommandClassesHoldNoServerState() throws IOException {
        Pattern field = Pattern.compile("(?m)^\\s*(?:private|public|protected)?\\s*static\\s+(?:final\\s+)?"
                + "[\\w.]*(?:Map|Set|List|Queue|Deque)<[^;=]*>\\s+\\w+\\s*=\\s*new\\b");
        List<String> found = new ArrayList<>();
        for (Path path : javaFiles(MAIN.resolve("server"))) {
            if (!path.getFileName().toString().endsWith("Commands.java")) continue;
            Matcher matcher = field.matcher(Files.readString(path));
            while (matcher.find()) found.add(relative(path) + " → " + matcher.group().trim());
        }
        assertAtMost("M1拡張 コマンドクラス内のサーバー状態", M1X_MAX_COMMAND_STATE_COLLECTIONS, found,
                "サーバー状態は server/ の状態管理クラスに置き、コマンドクラスは呼び出すだけにしてください（REFACTORING_PLAN Phase 1 / T8）。");
    }

    @Test
    public void testDomAttributesAreNotWrittenDirectly() throws IOException {
        Pattern write = Pattern.compile("\\.attrs\\.(?:put|remove)\\(");
        List<String> found = new ArrayList<>();
        for (Path path : javaFiles(MAIN)) {
            String rel = mainRelative(path);
            if (rel.startsWith("core/dom/node/") || rel.startsWith("core/dom/parser/")
                    || rel.equals("network/DomMutationRecord.java")
                    || rel.equals("network/HtmlNodeSerializer.java")
                    || rel.equals("common/CustomTagExpander.java")) continue;
            String[] lines = Files.readString(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (write.matcher(lines[i]).find()) found.add(rel + ":" + (i + 1));
            }
        }
        assertAtMost("DOM 属性の直接書き換え", DOM_MAX_DIRECT_ATTR_WRITES, found,
                "setAttribute / removeAttribute（MutationObserver と無効化フックを通る経路）を使ってください（REFACTORING_PLAN Phase 1 / T8）。");
    }

    @Test
    public void testR6RenderKeysReferencesDoNotGrow() throws IOException {
        List<String> found = new ArrayList<>();
        Pattern ref = Pattern.compile("\\bRenderKeys\\b");
        for (Path path : javaFiles(MAIN)) {
            if (mainRelative(path).equals("core/paint/model/RenderKeys.java")) continue;
            String[] lines = Files.readString(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (ref.matcher(lines[i]).find()) found.add(mainRelative(path) + ":" + (i + 1));
            }
        }
        assertAtMost("R6 RenderKeys の参照", R6_MAX_RENDER_KEYS_REFERENCES, found,
                "RenderKeys は Phase 3（R6）で撤去します。新しい参照を足さず、型付きディスプレイリストの属性を使ってください。");
    }

    @Test
    public void testR6DisplayListBuilderRegexDoesNotGrow() throws IOException {
        Path file = MAIN.resolve("client/renderer/DisplayListBuilder.java");
        int count = countMatches(Files.readString(file), "Pattern\\.compile\\(");
        assertMetricAtMost("R6 DisplayListBuilder の Pattern.compile", R6_MAX_DISPLAY_LIST_BUILDER_REGEX, count,
                "描画時に CSS 文字列を正規表現で解釈せず、計算済みスタイルの型付き値を使ってください（Phase 3 / R6）。");
    }

    @Test
    public void testM2FocusStateOwnersDoNotGrow() throws IOException {
        List<String> owners = new ArrayList<>();
        for (Path path : javaFiles(MAIN)) {
            if (FOCUS_STATE_FIELD.matcher(Files.readString(path)).find()) {
                owners.add(relative(path));
            }
        }
        assertAtMost("M2 フォーカス状態の持ち場", M2_MAX_FOCUS_STATE_OWNERS, owners,
                "ノードをキーにした対話状態は InteractionState に集めてください（STRUCTURE_PLAN S2）。");
    }

    @Test
    public void testM3ClientWebManagerPublicStaticDoesNotGrow() throws IOException {
        Path file = MAIN.resolve("client/ClientWebManager.java");
        int count = countMatches(Files.readString(file), "\\bpublic\\s+static\\b");
        assertMetricAtMost("M3 ClientWebManager の public static", M3_MAX_CLIENT_WEB_MANAGER_PUBLIC_STATIC,
                count, "セッションを 1 回引いたら、そのオブジェクトのメソッドにしてください（STRUCTURE_PLAN S3）。");
    }

    @Test
    public void testM4BindingInterpretersDoNotGrow() throws IOException {
        List<String> interpreters = new ArrayList<>();
        for (Path path : mainFilesUnderClientOrCoreDom()) {
            String rel = mainRelative(path);
            String content = Files.readString(path);
            boolean hasTemplate = content.contains("\"{{\"");
            boolean hasI18n = content.contains("data-i18n");
            boolean hasVirtualList = content.contains("data-scroll-y")
                    || content.contains("data-container-width")
                    || content.contains("data-visible-rows");
            if (hasTemplate || hasI18n || hasVirtualList || bindingAttributeCount(content) >= 2) {
                interpreters.add(rel);
            }
        }
        assertAtMost("M4 テンプレート・バインディングの解釈", M4_MAX_BINDING_INTERPRETERS, interpreters,
                "解釈は core/dom の 1 クラスに寄せてください（STRUCTURE_PLAN S6）。");
    }

    @Test
    public void testM5PipelineBuildersDoNotGrow() throws IOException {
        List<String> builders = new ArrayList<>();
        List<Path> roots = List.of(MAIN.resolve("client"), MAIN.resolve("common"));
        for (Path root : roots) {
            for (Path path : javaFiles(root)) {
                String content = Files.readString(path);
                if (NEW_LAYOUT_BOX.matcher(content).find() && MEASURE_CALL.matcher(content).find()) {
                    builders.add(relative(path));
                }
            }
        }
        assertAtMost("M5 スタイル→レイアウト→ディスプレイリストの組み立て", M5_MAX_PIPELINE_BUILDERS, builders,
                "入口は UiDocumentCompiler の 1 つにしてください（STRUCTURE_PLAN S7）。");
    }

    @Test
    public void testM6InputEntryPointsDoNotGrow() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (String entry : INPUT_ENTRY_POINTS) {
            Path path = MAIN.resolve(entry);
            if (Files.isRegularFile(path)) {
                found.add(entry);
            } else {
                missing.add(entry);
            }
        }
        assertTrue(missing.isEmpty(), "入力経路の番人が見ているファイルがありません: " + missing);
        assertAtMost("M6 入力をイベントに変える経路", M6_MAX_INPUT_ENTRY_POINTS, found,
                "ホストごとの座標変換だけを残し、入力処理は 1 本にしてください（STRUCTURE_PLAN S4）。");
    }

    @Test
    public void testInputSynthesisIsCentralized() throws IOException {
        Path input = MAIN.resolve("client/SessionInput.java");
        assertTrue(Files.isRegularFile(input), "入力経路の本体がありません: " + input);
        String inputContent = Files.readString(input);
        for (String marker : List.of("CoreEvent.pointer", "CoreEvent.mouse", "CoreEvent.click", "core.event.StandardDefaultActions")) {
            assertTrue(inputContent.contains(marker),
                    "SessionInput が " + marker + " を使っていません。入力合成の置き場所が変わった可能性があります");
        }

        List<String> violations = new ArrayList<>();
        for (String legacy : List.of(
                "client/ClientScreenInteractionManager.java",
                "client/gui/EnderScreenDelegate.java",
                "client/ClientDomEventDispatcher.java")) {
            String content = Files.readString(MAIN.resolve(legacy));
            for (String marker : List.of("CoreEvent.pointer", "CoreEvent.mouse", "CoreEvent.click", "core.event.StandardDefaultActions")) {
                if (content.contains(marker)) {
                    violations.add(legacy + " → " + marker);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "入力イベントの合成が SessionInput の外に残っています:\n  " + String.join("\n  ", violations));
    }

    @Test
    public void testM7CrossPackageNestedTypesDoNotGrow() throws IOException {
        Map<String, NestedType> nested = collectNestedTypes();
        Map<String, Set<String>> references = new LinkedHashMap<>();
        nested.keySet().forEach(key -> references.put(key, new LinkedHashSet<>()));

        for (Path path : javaFiles(MAIN)) {
            String content = Files.readString(path);
            String pkg = packageOf(content, path);
            for (Map.Entry<String, NestedType> entry : nested.entrySet()) {
                NestedType type = entry.getValue();
                if (pkg.equals(type.ownerPackage)) continue;
                Pattern qualified = Pattern.compile("\\b" + Pattern.quote(type.owner) + "\\."
                        + Pattern.quote(type.name) + "\\b");
                if (qualified.matcher(content).find()) {
                    references.get(entry.getKey()).add(pkg);
                }
            }
        }

        List<String> leaked = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : references.entrySet()) {
            if (entry.getValue().size() >= 2) {
                NestedType type = nested.get(entry.getKey());
                leaked.add(entry.getKey() + " [" + type.ownerPackage + "] ← "
                        + String.join(", ", entry.getValue()));
            }
        }
        assertAtMost("M7 2 つ以上のパッケージから参照される入れ子型", M7_MAX_CROSS_PACKAGE_NESTED_TYPES, leaked,
                "複数の層から使う型はトップレベルへ出してください（STRUCTURE_PLAN S8・CONTRIBUTING）。");
    }

    @Test
    public void testM8CssSplitHelpersDoNotGrow() throws IOException {
        List<String> helpers = new ArrayList<>();
        for (Path path : javaFiles(MAIN)) {
            String rel = mainRelative(path);
            String content = Files.readString(path);
            if (rel.equals("core/css/CssParser.java")) {
                // CssTokenizer は互換のための薄い窓口。中の split* は数えず、窓口自体を 1 つと数える。
                if (content.contains("class CssTokenizer")) helpers.add(rel + "#CssTokenizer");
                continue;
            }
            boolean cssScope = rel.startsWith("core/css/")
                    || rel.equals("core/layout/BoxTreeBuilder.java")
                    || rel.equals("core/layout/GridLayoutEngine.java")
                    || rel.equals("core/paint/BoxDecorations.java");
            if (!cssScope) continue;
            Matcher matcher = SPLIT_METHOD.matcher(content);
            while (matcher.find()) {
                helpers.add(rel + "#" + matcher.group(1));
            }
        }
        assertAtMost("M8 CSS 値を文字列で分割する関数", M8_MAX_CSS_SPLIT_HELPERS, helpers,
                "CssSyntax のコンポーネント値に寄せてください（STRUCTURE_PLAN S10）。");
    }

    @Test
    public void testM9TopLevelFileCountDoesNotGrow() throws IOException {
        int count = javaFiles(MAIN).size();
        assertMetricAtMost("M9 トップレベルのファイル数", M9_MAX_TOP_LEVEL_FILES, count,
                "ファイルを増やす前に、責務を統合できないか検討してください（STRUCTURE_PLAN S8）。");
    }

    // ------------------------------------------------------------------
    // 文書・テストの置き場所（S8）
    // ------------------------------------------------------------------

    @Test
    public void testTestPackagesFollowMainPackages() throws IOException {
        Set<String> mainPackages = new HashSet<>();
        for (Path path : javaFiles(MAIN)) {
            mainPackages.add(packageOf(Files.readString(path), path));
        }

        List<String> violations = new ArrayList<>();
        for (Path path : javaFiles(TEST)) {
            String pkg = packageOf(Files.readString(path), path);
            if (pkg.equals("com.nishiyu.enderframelib.wpt") || pkg.startsWith("com.nishiyu.enderframelib.wpt.")) {
                continue; // WPT の移植ハーネスはテスト専用のパッケージ
            }
            boolean covered = mainPackages.contains(pkg)
                    || mainPackages.stream().anyMatch(main -> main.startsWith(pkg + "."));
            if (!covered) {
                violations.add(relative(TEST.getParent().getParent().getParent()) + "/"
                        + relative(path) + " → " + pkg);
            }
        }
        assertTrue(violations.isEmpty(),
                "本体に対応するパッケージが無いテストがあります。テストのパッケージを本体に合わせてください:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    public void testDocumentsDoNotMentionRemovedTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path document : DOCUMENTS) {
            if (!Files.isRegularFile(document)) continue;
            String content = Files.readString(document);
            for (String removed : REMOVED_TYPE_NAMES) {
                Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(removed) + "\\b");
                if (wordPattern.matcher(content).find()) {
                    violations.add(document + " → " + removed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "消した型の名前が文書に残っています。現在の名前へ直すか、名前を出さずに説明してください:\n  "
                        + String.join("\n  ", violations));
    }

    // ------------------------------------------------------------------
    // サーバー・ネットワーク（T7 / S11）
    // ------------------------------------------------------------------

    @Test
    public void testServerSessionAttributesAreNotParallelMaps() throws IOException {
        Path sessionManagerPath = MAIN.resolve("server/WebSessionManager.java");
        assertTrue(Files.isRegularFile(sessionManagerPath));
        String content = Files.readString(sessionManagerPath);
        assertFalse(content.contains("SESSION_VIEWERS"),
                "SESSION_VIEWERS は WebSession のフィールドへ集約してください（STRUCTURE_PLAN S11）。");
        assertFalse(content.contains("SESSION_OWNERS"),
                "SESSION_OWNERS は WebSession のフィールドへ集約してください（STRUCTURE_PLAN S11）。");
    }

    @Test
    public void testScriptingBridgeHoldsNoGlobalProviderMap() throws IOException {
        Path bridgePath = MAIN.resolve("api/EnderFrameScriptingBridge.java");
        assertTrue(Files.isRegularFile(bridgePath));
        String content = Files.readString(bridgePath);
        assertFalse(content.contains("GLOBAL_PROVIDERS"),
                "GLOBAL_PROVIDERS は WebSessionManager へ集約し、窓口クラスはステートレスにしてください（STRUCTURE_PLAN S11）。");
    }

    @Test
    public void testSingleMutationPipelineWithoutDomPatch() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path path : javaFiles(MAIN)) {
            String content = Files.readString(path);
            if (content.contains("\"dom_patch\"")) {
                violations.add(mainRelative(path) + " が \"dom_patch\" を参照しています");
            }
        }
        assertTrue(violations.isEmpty(),
                "差分の送り方は dom_mutations 1 系統にしてください（STRUCTURE_PLAN S11）:\n  "
                        + String.join("\n  ", violations));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<Path> javaFiles(Path root) throws IOException {
        assertTrue(Files.isDirectory(root),
                "ソースディレクトリが見つかりません: " + root.toAbsolutePath()
                        + " (テストはプロジェクトルートを作業ディレクトリとして実行してください)");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> mainFilesUnderClientOrCoreDom() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path path : javaFiles(MAIN)) {
            String rel = mainRelative(path);
            boolean clientRoot = rel.startsWith("client/") && !rel.startsWith("client/gui/");
            boolean coreDom = rel.startsWith("core/dom/");
            if (clientRoot || coreDom) files.add(path);
        }
        return files;
    }

    private static int bindingAttributeCount(String content) {
        int count = 0;
        for (String attribute : List.of(
                "\"data-model\"", "\"data-bind\"", "\"data-if\"", "\"data-show\"",
                "\"data-for\"", "\"data-list\"", "\"data-class\"", "\"data-channel\"",
                "\"data-hud-\"", "\"data-tracker-\"")) {
            if (content.contains(attribute)) count++;
        }
        return count;
    }

    private static Map<String, NestedType> collectNestedTypes() throws IOException {
        Map<String, NestedType> nested = new LinkedHashMap<>();
        for (Path path : javaFiles(MAIN)) {
            String content = Files.readString(path);
            String pkg = packageOf(content, path);
            if (pkg.startsWith("com.nishiyu.enderframelib.api")) continue;
            String owner = path.getFileName().toString().replace(".java", "");
            Matcher matcher = NESTED_TYPE.matcher(content);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name.equals(owner)) continue;
                nested.putIfAbsent(owner + "." + name, new NestedType(owner, name, pkg));
            }
        }
        return nested;
    }

    private static String packageOf(String content, Path path) {
        Matcher matcher = PACKAGE.matcher(content);
        if (!matcher.find()) {
            fail("package 宣言がありません: " + path);
        }
        return matcher.group(1);
    }

    private static int countMatches(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String relative(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** MAIN からの相対パス（`client/...` のような層判定に使う）。 */
    private static String mainRelative(Path path) {
        return relative(MAIN.relativize(path));
    }

    private static void assertAtMost(String metric, int limit, Collection<String> found, String advice) {
        if (found.size() > limit) {
            fail("【ラチェット違反】 " + metric + " が上限 " + limit + " を超えました（実測 " + found.size() + "）:\n  "
                    + String.join("\n  ", found)
                    + "\n" + advice);
        }
    }

    private static void assertMetricAtMost(String metric, int limit, int actual, String advice) {
        assertTrue(actual <= limit,
                "【ラチェット違反】 " + metric + " が上限 " + limit + " を超えました（実測 " + actual + "）。\n" + advice);
    }

    private record NestedType(String owner, String name, String ownerPackage) {
    }
}
