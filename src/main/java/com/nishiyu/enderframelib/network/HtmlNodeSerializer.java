package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

@org.jetbrains.annotations.ApiStatus.Internal
public class HtmlNodeSerializer {

    private static final int MAX_DEPTH = 256;

    public static CompoundTag serialize(HtmlNode node) {
        if (node == null) return new CompoundTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("tag", node.tag != null ? node.tag : "");
        tag.putString("id", node.getId() != null ? node.getId() : "");
        tag.putString("text", node.text != null ? node.text : "");

        ListTag classesTag = new ListTag();
        for (String c : node.getClasses()) classesTag.add(StringTag.valueOf(c));
        tag.put("classes", classesTag);

        CompoundTag attrsTag = new CompoundTag();
        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            attrsTag.putString(entry.getKey(), entry.getValue());
        }
        tag.put("attrs", attrsTag);

        ListTag childrenTag = new ListTag();
        HtmlNode childHost = childHost(node);
        for (HtmlNode child : childHost.children) {
            childrenTag.add(serialize(child));
        }
        tag.put("children", childrenTag);

        return tag;
    }

    /** {@code template} の中身は要素の子ではなく content に直列化する。 */
    private static HtmlNode childHost(HtmlNode node) {
        return node instanceof HtmlElement el && el.isTemplateElement() ? el.getContent() : node;
    }

    public static HtmlNode deserialize(CompoundTag tag, HtmlNode parent) {
        return deserialize(tag, parent, 0);
    }

    /**
     * 親の無い最上位の {@code root} は文書そのもの。生きた {@link HtmlDocument}（id の索引・イベントの送り先・所有者の既定を持つ）に
     * 戻さないと、サーバー駆動の文書がクライアントのコードから {@code Document} として触れない。
     */
    private static boolean isDocumentRoot(String tagName, HtmlNode parent, int depth) {
        return depth == 0 && parent == null && "root".equals(tagName);
    }

    private static HtmlNode deserialize(CompoundTag tag, HtmlNode parent, int depth) {
        if (depth > MAX_DEPTH) {
            // 深すぎるネストはスタックオーバーフロー攻撃とみなし、ダミーを返して再帰を打ち切る
            return new HtmlNode("div");
        }

        String tagName = tag.getString("tag");
        HtmlNode node = "#text".equals(tagName)
                ? new HtmlText(tag.getString("text"))
                : isDocumentRoot(tagName, parent, depth) ? new HtmlDocument() : new HtmlElement(tagName);
        node.setId(tag.getString("id"));
        node.text = tag.getString("text");
        node.parent = parent;

        ListTag classesTag = tag.getList("classes", Tag.TAG_STRING);
        for (int i = 0; i < classesTag.size(); i++) {
            node.addClass(classesTag.getString(i));
        }

        CompoundTag attrsTag = tag.getCompound("attrs");
        for (String key : attrsTag.getAllKeys()) {
            node.attrs.put(key, attrsTag.getString(key));
        }

        ListTag childrenTag = tag.getList("children", Tag.TAG_COMPOUND);
        HtmlNode childHost = childHost(node);
        for (int i = 0; i < childrenTag.size(); i++) {
            childHost.children.add(deserialize(childrenTag.getCompound(i), childHost, depth + 1));
        }

        return node;
    }
}
