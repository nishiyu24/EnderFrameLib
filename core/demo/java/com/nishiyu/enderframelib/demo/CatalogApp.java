package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.ui.Ref;
import com.nishiyu.enderframelib.api.ui.VNode;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.common.UiTimers;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.nishiyu.enderframelib.api.ui.React.*;

/**
 * Showcase とは別の、測定向けのテストサイト。アニメーション主体の Showcase に対し、こちらは
 * 「長いスクロール・多数のカード（flex-wrap）・文字の多い箱・毎秒の一部書き換え」というレイアウト／文字寄りの負荷。
 *
 * <p>React / Hyperscript に着想を得たモダンな宣言的 UI DSL ({@link com.nishiyu.enderframelib.api.ui.React})
 * で記述されています。</p>
 *
 * <pre>
 * ./gradlew :core:runCatalog                         窓で表示（-Dcatalog.items=N で件数、既定 3000）
 * ./gradlew :core:runCatalog --args="true"           可変ウィンドウで表示（最大化・マウスリサイズ可）
 * ./gradlew :core:runCatalog --args="1280 800 true"  幅・高さ・可変ウィンドウを指定
 * ./gradlew :core:runCatalog --args="--png out.png"  窓なしで PNG
 * </pre>
 */
public final class CatalogApp {
    private CatalogApp() {}

    public static void main(String[] args) throws Exception {
        Path html = Path.of("core/demo/resources/catalog.html");
        List<String> a = new ArrayList<>(List.of(html.toString()));

        // 画面サイズ（幅・高さ）と可変ウィンドウ（true指定: 最大化・マウスリサイズを許可。argsでfalse指定された場合は可変しない）
        boolean sized = args.length >= 2 && args[0].matches("[0-9]+") && args[1].matches("[0-9]+");
        boolean hasResizable = sized
                ? (args.length >= 3 && ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2])))
                : (args.length >= 1 && ("true".equalsIgnoreCase(args[0]) || "false".equalsIgnoreCase(args[0])));
        boolean resizable = hasResizable ? Boolean.parseBoolean(sized ? args[2] : args[0]) : true;

        int pos = 0;
        if (sized) {
            a.add(args[0]);
            a.add(args[1]);
            pos = 2;
        } else {
            a.addAll(List.of("1280", "800"));
        }

        a.add(String.valueOf(resizable));
        if (hasResizable) {
            pos++;
        }

        for (int i = pos; i < args.length; i++) {
            a.add(args[i]);
        }

        StandaloneViewer.launch(a.toArray(new String[0]), CatalogApp::wire);
    }

    /**
     * カタログカードの宣言的コンポーネント表現と、マウント後の DOM ハンドル。
     */
    record CardItem(
            VNode vnode,
            Ref<Element> cardRef,
            Ref<Element> priceRef,
            Ref<Element> badgeRef,
            String name
    ) implements VNode {
        @Override
        public Node mount(Document doc) {
            return vnode.mount(doc);
        }

        public Element card() { return cardRef.get(); }
        public Element price() { return priceRef.get(); }
        public Element badge() { return badgeRef.get(); }
    }

    /**
     * 単一のカタログカードを生成する関数型コンポーネント。
     */
    private static CardItem Card(int i, String kind, String hue, Random rnd) {
        Ref<Element> cardRef = useRef();
        Ref<Element> priceRef = useRef();
        Ref<Element> badgeRef = useRef();

        String name = kind + " #" + (1000 + i);
        String desc = "Item " + i + ": a sturdy " + kind.toLowerCase()
                + " crafted for long expeditions, with enough text here to wrap across several lines.";
        int initialPrice = 5 + rnd.nextInt(995);
        int initialStock = rnd.nextInt(50);

        var vnode = div(ref(cardRef), cls("card"),
                div(cls("thumb"), style("background:" + hue)),
                div(cls("body"),
                        div(cls("name"), name),
                        div(cls("desc"), desc),
                        div(cls("row"),
                                span(ref(priceRef), cls("price"), "$" + initialPrice),
                                span(ref(badgeRef), cls(initialStock < 8 ? "badge low" : "badge"), "stock " + initialStock)
                        )
                )
        );

        return new CardItem(vnode, cardRef, priceRef, badgeRef, name);
    }

    static void wire(StandaloneView view) {
        Node root = view.root();
        UiTimers timers = view.timers();
        Random rnd = new Random(7);
        int n = Integer.getInteger("catalog.items", 3000);
        String[] kinds = {"Pickaxe", "Lantern", "Compass", "Bread", "Obsidian", "Shield", "Elytra", "Barrel"};
        String[] hues = {"#fca5a5", "#fcd34d", "#86efac", "#93c5fd", "#c4b5fd", "#f9a8d4"};

        // 1. リアクティブな状態管理 (React useState)
        var count = useState(n);
        var clock = useState("--:--:--");
        var updates = useState(0);
        var ticker = useState("");

        // DOM 要素と状態をバインド (状態変化時に自動で DOM テキストを同期)
        bindText($("#count", root), count);
        bindText($("#clock", root), clock);
        bindText($("#updates", root), updates);
        bindText($("#ticker", root), ticker);

        // 2. カードの宣言的構築とバッチマウント
        List<CardItem> cards = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cards.add(Card(i, kinds[i % kinds.length], hues[i % hues.length], rnd));
        }
        mount($("#list", root), cards);

        // 3. タイマー更新ループ (毎秒クロック更新 + ランダム 10 枚の価格/在庫書き換え + フラッシュ)
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        Element[] flashed = new Element[6];

        timers.setInterval(() -> {
            clock.set(LocalTime.now().format(timeFmt));

            for (int i = 0; i < 10; i++) {
                int k = rnd.nextInt(n);
                var card = cards.get(k);
                // 既存の Text 節の文字だけを書き換える（箱の木を作り直さない局所パッチに乗る）
                card.price().updateTextContent("$" + (5 + rnd.nextInt(995)));
                int stock = rnd.nextInt(50);
                card.badge().updateTextContent("stock " + stock);
                classList(card.badge()).toggle("low", stock < 8);
                updates.update(u -> u + 1);
            }

            for (int i = 0; i < flashed.length; i++) {
                if (flashed[i] != null) classList(flashed[i]).remove("flash");
                flashed[i] = cards.get(rnd.nextInt(n)).card();
                classList(flashed[i]).add("flash");
            }
        }, 1000);

        // 4. 検索窓: 入力のたびに名前で絞り込み (一致しないカードは display:none)
        Element q = $("#q", root);
        q.addEventListener("input", e -> {
            String needle = q.getAttribute("value");
            String lower = needle != null ? needle.trim().toLowerCase() : "";

            int shown = 0;
            for (CardItem item : cards) {
                boolean hit = lower.isEmpty() || item.name().toLowerCase().contains(lower);
                item.card().setAttribute("style", hit ? "" : "display:none");
                if (hit) shown++;
            }
            ticker.set(shown + " / " + n + " match");
            count.set(shown);
        });
    }
}
