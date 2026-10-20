package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

import java.util.Objects;

/**
 * テキスト選択範囲モデル（DOM Selection 仕様準拠）。
 *
 * アンカー（ドラッグ開始点）とフォーカス（ドラッグ終了点/現在点）を持ち、
 * 選択範囲内のテキストおよび状態を純 Java で管理する。
 */
public class TextSelection {

    private String anchorNodeId = "";
    private int anchorOffset = 0;
    private String focusNodeId = "";
    private int focusOffset = 0;
    private String selectedText = "";

    public TextSelection() {}

    public TextSelection(String anchorNodeId, int anchorOffset, String focusNodeId, int focusOffset, String selectedText) {
        this.anchorNodeId = anchorNodeId != null ? anchorNodeId : "";
        this.anchorOffset = Math.max(0, anchorOffset);
        this.focusNodeId = focusNodeId != null ? focusNodeId : "";
        this.focusOffset = Math.max(0, focusOffset);
        this.selectedText = selectedText != null ? selectedText : "";
    }

    public boolean isEmpty() {
        if (anchorNodeId.isEmpty() || focusNodeId.isEmpty()) return true;
        if (anchorNodeId.equals(focusNodeId) && anchorOffset == focusOffset) return true;
        return selectedText.isEmpty();
    }

    public void clear() {
        this.anchorNodeId = "";
        this.anchorOffset = 0;
        this.focusNodeId = "";
        this.focusOffset = 0;
        this.selectedText = "";
    }

    public void startSelection(String nodeId, int offset) {
        this.anchorNodeId = nodeId != null ? nodeId : "";
        this.anchorOffset = Math.max(0, offset);
        this.focusNodeId = this.anchorNodeId;
        this.focusOffset = this.anchorOffset;
        this.selectedText = "";
    }

    /**
     * 単一ノード内での選択範囲更新。
     */
    public void updateWithinNode(String nodeId, int currentOffset, String fullText) {
        if (nodeId == null || !nodeId.equals(this.anchorNodeId)) return;
        this.focusNodeId = nodeId;
        this.focusOffset = Math.max(0, currentOffset);

        if (fullText == null || fullText.isEmpty()) {
            this.selectedText = "";
            return;
        }

        int textLen = fullText.length();
        int start = Math.clamp(Math.min(anchorOffset, focusOffset), 0, textLen);
        int end = Math.clamp(Math.max(anchorOffset, focusOffset), 0, textLen);

        if (start < end) {
            this.selectedText = fullText.substring(start, end);
        } else {
            this.selectedText = "";
        }
    }

    /**
     * ノード全体を選択する。
     */
    public void selectAll(String nodeId, String fullText) {
        if (nodeId == null || fullText == null || fullText.isEmpty()) {
            clear();
            return;
        }
        this.anchorNodeId = nodeId;
        this.anchorOffset = 0;
        this.focusNodeId = nodeId;
        this.focusOffset = fullText.length();
        this.selectedText = fullText;
    }

    public String anchorNodeId() {
        return anchorNodeId;
    }

    public int anchorOffset() {
        return anchorOffset;
    }

    public String focusNodeId() {
        return focusNodeId;
    }

    public int focusOffset() {
        return focusOffset;
    }

    public String selectedText() {
        return selectedText;
    }

    public int startOffset() {
        return Math.min(anchorOffset, focusOffset);
    }

    public int endOffset() {
        return Math.max(anchorOffset, focusOffset);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextSelection that = (TextSelection) o;
        return anchorOffset == that.anchorOffset && focusOffset == that.focusOffset
                && Objects.equals(anchorNodeId, that.anchorNodeId)
                && Objects.equals(focusNodeId, that.focusNodeId)
                && Objects.equals(selectedText, that.selectedText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchorNodeId, anchorOffset, focusNodeId, focusOffset, selectedText);
    }

    @Override
    public String toString() {
        return "TextSelection{" +
                "anchorNodeId='" + anchorNodeId + '\'' +
                ", anchorOffset=" + anchorOffset +
                ", focusNodeId='" + focusNodeId + '\'' +
                ", focusOffset=" + focusOffset +
                ", selectedText='" + selectedText + '\'' +
                '}';
    }
}
