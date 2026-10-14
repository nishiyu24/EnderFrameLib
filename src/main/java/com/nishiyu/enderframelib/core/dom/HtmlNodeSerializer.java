package com.nishiyu.enderframelib.core.dom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

public class HtmlNodeSerializer {

    public static CompoundTag serialize(HtmlNode node) {
        if (node == null) return new CompoundTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("tag", node.tag != null ? node.tag : "");
        tag.putString("id", node.id != null ? node.id : "");
        tag.putString("text", node.text != null ? node.text : "");

        ListTag classesTag = new ListTag();
        for (String c : node.classes) classesTag.add(StringTag.valueOf(c));
        tag.put("classes", classesTag);

        CompoundTag attrsTag = new CompoundTag();
        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            attrsTag.putString(entry.getKey(), entry.getValue());
        }
        tag.put("attrs", attrsTag);

        ListTag childrenTag = new ListTag();
        for (HtmlNode child : node.children) {
            childrenTag.add(serialize(child));
        }
        tag.put("children", childrenTag);

        return tag;
    }

    public static HtmlNode deserialize(CompoundTag tag, HtmlNode parent) {
        HtmlNode node = new HtmlNode(tag.getString("tag"));
        node.id = tag.getString("id");
        node.text = tag.getString("text");
        node.parent = parent;

        ListTag classesTag = tag.getList("classes", Tag.TAG_STRING);
        for (int i = 0; i < classesTag.size(); i++) {
            node.classes.add(classesTag.getString(i));
        }

        CompoundTag attrsTag = tag.getCompound("attrs");
        for (String key : attrsTag.getAllKeys()) {
            node.attrs.put(key, attrsTag.getString(key));
        }

        ListTag childrenTag = tag.getList("children", Tag.TAG_COMPOUND);
        for (int i = 0; i < childrenTag.size(); i++) {
            node.children.add(deserialize(childrenTag.getCompound(i), node));
        }

        return node;
    }
}