package com.nishiyu.enderframelib.core.paint.model;

import java.util.Map;
import java.util.Objects;

/**
 * ホストが自分の描画系で描く中身の 1 つ（{@code HostTextures}。M3b-4c-2b-3b-2b-2）。描画器の準備が描くフレームを組みながら、ホストが
 * 欲しいと答えた（{@link Filter#wants}）中身だけを値で集め、準備した値（{@code PreparedFrame#hostContents}）に持たせる。ホストは
 * 表示項目の列を読まずにこれだけを見てテクスチャを用意し、描くときは項目がどの中身か（{@link #matches}）で引く。
 *
 * @param key         項目の鍵（節の番号と部品）
 * @param baseId      持ち主の要素の ID
 * @param type        項目の種類
 * @param text        中身の指定（画像の {@code src}・文字）
 * @param width       項目の幅（CSS px。描く項目と同じ整数）
 * @param height      項目の高さ
 * @param color       文字の色
 * @param fontFamily  書体の指定（無ければ null）
 * @param events      項目の属性の写し（太さ・文字の影・飾りの線・3D の場面の指定）
 * @param meta        文字の大きさとベースライン（無ければ null）
 * @param placeholder 入力欄の placeholder の文字か（縦の中央に置く）
 * @param owner       持ち主の節の番号（描かない節〔{@code RenderFrame#exclude}〕の判定。分からなければ 0）
 */
public record HostContent(long key, String baseId, ElementType type, String text, int width, int height, int color,
                          String fontFamily, Map<String, String> events, RenderMeta meta, boolean placeholder, int owner) {

    /** ホストがどの中身を自分で描くか（{@code HostTextures} が実装する。描画器の準備が項目ごとに聞く）。 */
    public interface Filter {
        /** 何も描かない。 */
        Filter NONE = (type, text, fontFamily, events) -> false;

        /**
         * @param type       項目の種類
         * @param text       中身の指定（画像の {@code src}・文字）
         * @param fontFamily 書体の指定（無ければ null）
         * @param events     項目の属性の写し（場面の種類など。空でもよい）
         */
        boolean wants(ElementType type, String text, String fontFamily, Map<String, String> events);
    }

    /** 項目 {@code it} の中身。 */
    public static HostContent of(DisplayItem it) {
        return new HostContent(it.key(), it.baseId(), it.type(), it.text(), it.width(), it.height(), it.color(), fontFamilyOf(it),
                it.events() != null ? it.events() : Map.of(), it.meta(), it.isPart("_ph"), ownerOf(it));
    }

    /** 描く項目 {@code it} がこの中身か（鍵・種類・中身の指定・色・大きさが同じ）。 */
    public boolean matches(DisplayItem it) {
        return it.key() == key && it.type() == type && it.color() == color && it.width() == width && it.height() == height
                && Objects.equals(it.text(), text);
    }

    /** 項目の書体の指定（付帯情報か属性の写し。無ければ null）。 */
    public static String fontFamilyOf(DisplayItem it) {
        String family = it.hints().fontFamily();
        if (family == null && it.events() != null) family = it.events().get("font-family");
        return family;
    }

    /** ホストに聞く種類の項目か（画像・場面・文字。空の文字は聞かない）。描画器がホストのテクスチャを引く項目と同じ。 */
    public static boolean asksHost(DisplayItem it) {
        return switch (it.type().rasterRoute()) {
            case IMAGE, SCENE, TEXT_ALWAYS -> true;
            case TEXT_IF_ANY -> it.text() != null && !it.text().isEmpty();
            default -> false;
        };
    }

    /** 項目の持ち主の節の番号（節の鍵から。文字列の鍵なら親の節の鍵から。分からなければ 0）。 */
    public static int ownerOf(DisplayItem it) {
        long k = it.key();
        if (k == 0L || (k & DisplayItem.STRING_KEY_BIT) != 0L) k = it.hints().parentKey();
        return k == 0L || (k & DisplayItem.STRING_KEY_BIT) != 0L ? 0 : DisplayItem.nodeHandleOf(k);
    }
}
