package com.nishiyu.enderframelib.network;

import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import java.util.Map;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * WHATWG MutationRecord に相当する、サーバーからクライアントへ送信される
 * 単一の DOM 変異レコード。
 *
 * <p>仮想 DOM や全ツリー差分突き合わせを介さず、実 DOM ノードへ直接適用される。</p>
 */
public final class DomMutationRecord {

    public enum Type {
        TEXT,
        ATTRIBUTE,
        STYLE,
        CLASS,
        INSERT_CHILD,
        REMOVE_CHILD,
        INNER_HTML
    }

    private final Type type;
    private final String targetId;
    private final String name;
    private final String value;
    private final String mode;
    private final CompoundTag childTag;
    private final String referenceId;

    public DomMutationRecord(Type type, String targetId, @Nullable String name, @Nullable String value,
                             @Nullable String mode, @Nullable CompoundTag childTag, @Nullable String referenceId) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.targetId = targetId != null ? targetId : "";
        this.name = name != null ? name : "";
        this.value = value != null ? value : "";
        this.mode = mode != null ? mode : "";
        this.childTag = childTag;
        this.referenceId = referenceId != null ? referenceId : "";
    }

    public Type getType() {
        return type;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getMode() {
        return mode;
    }

    @Nullable
    public CompoundTag getChildTag() {
        return childTag;
    }

    public String getReferenceId() {
        return referenceId;
    }

    // --- 便利ファクトリ ---

    public static DomMutationRecord text(String targetId, String text) {
        return new DomMutationRecord(Type.TEXT, targetId, null, text, null, null, null);
    }

    public static DomMutationRecord attribute(String targetId, String name, @Nullable String value, boolean remove) {
        return new DomMutationRecord(Type.ATTRIBUTE, targetId, name, value, remove ? "remove" : "set", null, null);
    }

    public static DomMutationRecord style(String targetId, String property, String value) {
        return new DomMutationRecord(Type.STYLE, targetId, property, value, null, null, null);
    }

    public static DomMutationRecord className(String targetId, String className, String mode) {
        return new DomMutationRecord(Type.CLASS, targetId, null, className, mode, null, null);
    }

    public static DomMutationRecord insertChild(String parentId, @Nullable String referenceId, CompoundTag childTag) {
        return new DomMutationRecord(Type.INSERT_CHILD, parentId, null, null, null, childTag, referenceId);
    }

    public static DomMutationRecord removeChild(String parentId, String childId) {
        return new DomMutationRecord(Type.REMOVE_CHILD, parentId, null, childId, null, null, null);
    }

    public static DomMutationRecord innerHtml(String targetId, String htmlFragment) {
        return new DomMutationRecord(Type.INNER_HTML, targetId, null, htmlFragment, null, null, null);
    }

    // --- NBT 相互変換 ---

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        tag.putString("TargetId", targetId);
        if (!name.isEmpty()) tag.putString("Name", name);
        if (!value.isEmpty()) tag.putString("Value", value);
        if (!mode.isEmpty()) tag.putString("Mode", mode);
        if (childTag != null) tag.put("ChildTag", childTag);
        if (!referenceId.isEmpty()) tag.putString("RefId", referenceId);
        return tag;
    }

    public static DomMutationRecord fromTag(CompoundTag tag) {
        String typeStr = tag.getString("Type");
        Type type;
        try {
            type = Type.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            // 互換性フォールバック: 旧パッチ形式の Op 名
            String op = tag.getString("Op");
            type = switch (op) {
                case "text" -> Type.TEXT;
                case "attr" -> Type.ATTRIBUTE;
                case "style" -> Type.STYLE;
                case "add_class", "remove_class", "toggle_class" -> Type.CLASS;
                case "inner_html" -> Type.INNER_HTML;
                case "remove_node" -> Type.REMOVE_CHILD;
                default -> Type.TEXT;
            };
        }

        String targetId = tag.contains("TargetId") ? tag.getString("TargetId") : "";
        String name = tag.contains("Name") ? tag.getString("Name") : tag.getString("Key");
        String value = tag.getString("Value");
        String mode = tag.getString("Mode");
        if (mode.isEmpty() && tag.contains("Remove") && tag.getBoolean("Remove")) {
            mode = "remove";
        }
        if (mode.isEmpty() && tag.contains("Op")) {
            String op = tag.getString("Op");
            if (op.endsWith("_class")) {
                mode = op.substring(0, op.indexOf('_'));
            }
        }
        CompoundTag childTag = tag.contains("ChildTag") ? tag.getCompound("ChildTag") : null;
        String refId = tag.getString("RefId");

        return new DomMutationRecord(type, targetId, name, value, mode, childTag, refId);
    }

    /**
     * この変異レコードを実 DOM ツリーに直接適用する。
     *
     * @param root ドキュメントのルートノード（{@link HtmlDocument} 等）
     * @param dirtyCallback 変更のあったノードを通知するコールバック
     * @return 実際に変更があった場合 true
     */
    public boolean applyTo(HtmlNode root, @Nullable Consumer<HtmlNode> dirtyCallback) {
        if (root == null) return false;

        HtmlNode target = root.getNodeById(targetId);
        if (target == null && type != Type.REMOVE_CHILD) {
            return false;
        }

        boolean changed = false;
        switch (type) {
            case TEXT -> {
                if (target != null && !Objects.equals(target.getTextContent(), value)) {
                    target.setTextContent(value);
                    changed = true;
                }
            }
            case ATTRIBUTE -> {
                if (target != null) {
                    boolean remove = "remove".equalsIgnoreCase(mode);
                    String prevVal = target.attrs.get(name);
                    if (remove) {
                        if (prevVal != null) {
                            DomUtils.updateAttribute(target, name, null, true);
                            changed = true;
                        }
                    } else {
                        if (!Objects.equals(prevVal, value)) {
                            DomUtils.updateAttribute(target, name, value, false);
                            changed = true;
                        }
                    }
                }
            }
            case STYLE -> {
                if (target != null) {
                    target.setStyleProperty(name, value);
                    changed = true;
                }
            }
            case CLASS -> {
                if (target != null) {
                    switch (mode.toLowerCase()) {
                        case "remove" -> {
                            if (target.hasClass(value)) {
                                target.removeClass(value);
                                changed = true;
                            }
                        }
                        case "toggle" -> {
                            target.toggleClass(value);
                            changed = true;
                        }
                        default -> {
                            if (!target.hasClass(value)) {
                                target.addClass(value);
                                changed = true;
                            }
                        }
                    }
                }
            }
            case INSERT_CHILD -> {
                if (target != null && childTag != null) {
                    HtmlNode newChild = HtmlNodeSerializer.deserialize(childTag, null);
                    if (newChild != null) {
                        HtmlNode refNode = !referenceId.isEmpty() ? target.getNodeById(referenceId) : null;
                        if (refNode != null && refNode.parent == target) {
                            target.insertBefore(newChild, refNode);
                        } else {
                            target.appendChild(newChild);
                        }
                        changed = true;
                    }
                }
            }
            case REMOVE_CHILD -> {
                HtmlNode childToRemove = !value.isEmpty() ? root.getNodeById(value) : target;
                if (childToRemove != null) {
                    HtmlNode parent = childToRemove.parent != null ? childToRemove.parent : target;
                    if (parent != null) {
                        parent.removeChild(childToRemove);
                        changed = true;
                    } else {
                        childToRemove.remove();
                        changed = true;
                    }
                }
            }
            case INNER_HTML -> {
                if (target != null) {
                    target.setInnerHTML(value);
                    changed = true;
                }
            }
        }

        if (changed && dirtyCallback != null && target != null) {
            dirtyCallback.accept(target);
        }
        return changed;
    }

    public static class HtmlNodeSerializer {

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
}
