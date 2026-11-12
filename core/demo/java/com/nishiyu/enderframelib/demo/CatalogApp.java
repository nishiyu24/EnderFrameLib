package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.common.UiTimers;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Showcase とは別の、測定向けのテストサイト。アニメーション主体の Showcase に対し、こちらは
 * 「長いスクロール・多数のカード（flex-wrap）・文字の多い箱・毎秒の一部書き換え」というレイアウト／文字寄りの負荷。
 *
 * <pre>
 * ./gradlew :core:runCatalog                         窓で表示（-Dcatalog.items=N で件数、既定 3000）
 * ./gradlew :core:runCatalog --args="--png out.png"  窓なしで PNG
 * </pre>
 */
public final class CatalogApp {
    private CatalogApp() {}

    public static void main(String[] args) throws Exception {
        Path html = Path.of("core/demo/resources/catalog.html");
        List<String> a = new ArrayList<>(List.of(html.toString()));
        boolean sized = args.length >= 2 && args[0].matches("[0-9]+") && args[1].matches("[0-9]+");
        if (!sized) a.addAll(List.of("1280", "800"));
        a.addAll(List.of(args));
        StandaloneViewer.launch(a.toArray(new String[0]), CatalogApp::wire);
    }

    static void wire(StandaloneView view) {
        HtmlNode root = view.root();
        HtmlDocument doc = root.ownerDocument;
        UiTimers timers = view.timers();
        Random rnd = new Random(7);
        int n = Integer.getInteger("catalog.items", 3000);
        String[] kinds = {"Pickaxe", "Lantern", "Compass", "Bread", "Obsidian", "Shield", "Elytra", "Barrel"};
        String[] hues = {"#fca5a5", "#fcd34d", "#86efac", "#93c5fd", "#c4b5fd", "#f9a8d4"};

        HtmlNode list = byId(root, "list");
        List<HtmlElement> prices = new ArrayList<>(), badges = new ArrayList<>(), cards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            HtmlElement card = el(doc, "div", "card");
            HtmlElement thumb = el(doc, "div", "thumb");
            thumb.setAttribute("style", "background:" + hues[i % hues.length]);
            HtmlElement body = el(doc, "div", "body");
            HtmlElement name = el(doc, "div", "name");
            name.setTextContent(kinds[i % kinds.length] + " #" + (1000 + i));
            HtmlElement desc = el(doc, "div", "desc");
            desc.setTextContent("Item " + i + ": a sturdy " + kinds[(i * 3) % kinds.length].toLowerCase()
                    + " crafted for long expeditions, with enough text here to wrap across several lines.");
            HtmlElement row = el(doc, "div", "row");
            HtmlElement price = el(doc, "span", "price");
            price.setTextContent("$" + (5 + rnd.nextInt(995)));
            HtmlElement badge = el(doc, "span", "badge");
            badge.setTextContent("stock " + rnd.nextInt(50));
            row.appendChild(price);
            row.appendChild(badge);
            body.appendChild(name);
            body.appendChild(desc);
            body.appendChild(row);
            card.appendChild(thumb);
            card.appendChild(body);
            list.appendChild(card);
            prices.add(price);
            badges.add(badge);
            cards.add(card);
        }
        byId(root, "count").setTextContent(Integer.toString(n));

        HtmlNode clock = byId(root, "clock"), updates = byId(root, "updates"), ticker = byId(root, "ticker");
        int[] seq = {0};
        HtmlElement[] flashed = new HtmlElement[6];
        timers.setInterval(() -> {
            clock.setTextContent(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            for (int i = 0; i < 10; i++) {
                int k = rnd.nextInt(n);
                prices.get(k).setTextContent("$" + (5 + rnd.nextInt(995)));
                int stock = rnd.nextInt(50);
                badges.get(k).setTextContent("stock " + stock);
                badges.get(k).setAttribute("class", stock < 8 ? "badge low" : "badge");
                seq[0]++;
            }
            for (int i = 0; i < flashed.length; i++) {
                if (flashed[i] != null) flashed[i].setAttribute("class", "card");
                flashed[i] = cards.get(rnd.nextInt(n));
                flashed[i].setAttribute("class", "card flash");
            }
            updates.setTextContent(Integer.toString(seq[0]));
        }, 1000);

        // 検索窓: 入力のたびに名前で絞り込む（一致しないカードは display:none）
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add((kinds[i % kinds.length] + " #" + (1000 + i)).toLowerCase());
        HtmlNode q = byId(root, "q");
        q.addEventListener("input", e -> {
            String needle = q.attrs.getOrDefault("value", "").trim().toLowerCase();
            int shown = 0;
            for (int i = 0; i < n; i++) {
                boolean hit = needle.isEmpty() || names.get(i).contains(needle);
                cards.get(i).setAttribute("style", hit ? "" : "display:none");
                if (hit) shown++;
            }
            ticker.setTextContent(shown + " / " + n + " match");
            byId(root, "count").setTextContent(Integer.toString(shown));
        });
    }

    private static HtmlNode byId(HtmlNode root, String id) {
        HtmlNode n = root.getNodeById(id);
        if (n == null) throw new IllegalStateException("#" + id + " not found");
        return n;
    }

    private static int idSeq;

    private static HtmlElement el(HtmlDocument doc, String tag, String cls) {
        HtmlElement e = (HtmlElement) doc.createElement(tag);
        e.setAttribute("id", "c" + (++idSeq));
        if (cls != null) e.setAttribute("class", cls);
        return e;
    }
}
