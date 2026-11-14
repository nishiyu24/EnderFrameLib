package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.VNode;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.nishiyu.enderframelib.api.ui.UI.*;

/**
 * Showcase とは別の、測定向けのテストサイト。アニメーション主体の Showcase に対し、こちらは
 * 「長いスクロール・多数のカード（flex-wrap）・文字の多い箱・毎秒の一部書き換え」というレイアウト／文字寄りの負荷。
 *
 * <p>軽量な pure-Java DOM ビルダーと {@link State} バインディング ({@link com.nishiyu.enderframelib.api.ui.UI})
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

    private record CardProps(
            String name,
            String kind,
            String hue,
            State<Integer> price,
            State<Integer> stock,
            State<Boolean> flashed,
            State<Boolean> visible
    ) {}

    /**
     * 単一のカタログカードを構築するビルダー。
     */
    private static VNode Card(int index, CardProps props) {
        var desc = "Item " + index + ": a sturdy " + props.kind().toLowerCase()
                + " crafted for long expeditions, with enough text here to wrap across several lines.";
        var isLow = props.stock().map(s -> s < 8);

        return div(
                className("card", when("flash", props.flashed())),
                show(props.visible()),
                div(className("thumb"), style("background", props.hue())),
                div(className("body"),
                        div(className("name"), props.name()),
                        div(className("desc"), desc),
                        div(className("row"),
                                span(className("price"), props.price().map(p -> "$" + p)),
                                span(className("badge", when("low", isLow)), props.stock().map(s -> "stock " + s))
                        )
                )
        );
    }

    static void wire(StandaloneView view) {
        var rnd = new Random(7);
        int n = Integer.getInteger("catalog.items", 3000);
        String[] kinds = {"Pickaxe", "Lantern", "Compass", "Bread", "Obsidian", "Shield", "Elytra", "Barrel"};
        String[] hues = {"#fca5a5", "#fcd34d", "#86efac", "#93c5fd", "#c4b5fd", "#f9a8d4"};

        Document doc = view.document();

        // 1. リアクティブな状態と DOM バインド
        var count = State.of(n);
        var clock = State.of("--:--:--");
        var updates = State.of(0);
        var ticker = State.of("");

        bindText(byId(doc, "count"), count);
        bindText(byId(doc, "clock"), clock);
        bindText(byId(doc, "updates"), updates);
        bindText(byId(doc, "ticker"), ticker);

        // 2. カード状態モデルの生成と #list への描画
        var cards = map(n, i -> {
            String kind = kinds[i % kinds.length];
            return new CardProps(
                    kind + " #" + (1000 + i),
                    kind,
                    hues[i % hues.length],
                    State.of(5 + rnd.nextInt(995)),
                    State.of(rnd.nextInt(50)),
                    State.of(false),
                    State.of(true)
            );
        });

        render(byId(doc, "list"),
                map(n, i -> Card(i, cards.get(i)))
        );

        // 3. タイマー更新ループ (毎秒クロック更新 + ランダム 10 枚の価格/在庫更新 + 6 枚フラッシュ)
        var timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        var flashed = new int[6];
        Arrays.fill(flashed, -1);

        view.timers().setInterval(() -> {
            clock.set(LocalTime.now().format(timeFmt));

            for (int i = 0; i < 10; i++) {
                var card = cards.get(rnd.nextInt(n));
                card.price().set(5 + rnd.nextInt(995));
                card.stock().set(rnd.nextInt(50));
                updates.update(u -> u + 1);
            }

            for (int i = 0; i < flashed.length; i++) {
                if (flashed[i] >= 0) cards.get(flashed[i]).flashed().set(false);
                flashed[i] = rnd.nextInt(n);
                cards.get(flashed[i]).flashed().set(true);
            }
        }, 1000);

        // 4. 検索窓: 入力のたびに名前で絞り込み
        onInputValue(byId(doc, "q"), input -> {
            String lower = input.trim().toLowerCase();
            int shown = 0;
            for (var card : cards) {
                boolean hit = lower.isEmpty() || card.name().toLowerCase().contains(lower);
                card.visible().set(hit);
                if (hit) shown++;
            }
            ticker.set(shown + " / " + n + " match");
            count.set(shown);
        });
    }
}
