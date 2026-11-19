package com.nishiyu.enderframelib.core.paint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isAtomicInline;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isFlexOrGridItem;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;
import com.nishiyu.enderframelib.core.paint.PaintOrder.PaintNode;

/** スタッキングコンテキスト（または疑似コンテキスト）の中の層。番号は付録 E の手順。 */
final class PaintLayers {
    private static final Comparator<PaintNode> Z_CMP = Comparator.comparingInt(PaintNode::z);
    // 空の層が大半なので、最初の追加まで一覧を作らない（読むときは空の共有一覧）
    private List<PaintNode> negative;   // 2: 負の z-index のコンテキスト
    private List<PaintNode> blocks;     // 4: 通常フローのブロックレベル
    private List<PaintNode> floats;     // 5: float
    private List<PaintNode> inlines;    // 7: インラインレベル
    private List<PaintNode> positioned; // 8: z-index: auto / 0 の位置指定要素とコンテキスト
    private List<PaintNode> positive;   // 9: 正の z-index のコンテキスト
    private List<RenderTask> after;     // スクロールバー（中身より上）

    void reset() {
        if (negative != null) negative.clear();
        if (blocks != null) blocks.clear();
        if (floats != null) floats.clear();
        if (inlines != null) inlines.clear();
        if (positioned != null) positioned.clear();
        if (positive != null) positive.clear();
        if (after != null) after.clear();
    }

    List<PaintNode> negative() { return negative == null ? List.of() : negative; }
    List<PaintNode> blocks() { return blocks == null ? List.of() : blocks; }
    List<PaintNode> floats() { return floats == null ? List.of() : floats; }
    List<PaintNode> inlines() { return inlines == null ? List.of() : inlines; }
    List<PaintNode> positioned() { return positioned == null ? List.of() : positioned; }
    List<PaintNode> positive() { return positive == null ? List.of() : positive; }
    List<RenderTask> after() { return after == null ? List.of() : after; }

    void addNegative(PaintNode n) { (negative == null ? negative = new ArrayList<>(4) : negative).add(n); }
    void addBlock(PaintNode n) { (blocks == null ? blocks = new ArrayList<>(4) : blocks).add(n); }
    void addFloat(PaintNode n) { (floats == null ? floats = new ArrayList<>(4) : floats).add(n); }
    void addInline(PaintNode n) { (inlines == null ? inlines = new ArrayList<>(4) : inlines).add(n); }
    void addPositioned(PaintNode n) { (positioned == null ? positioned = new ArrayList<>(4) : positioned).add(n); }
    void addPositive(PaintNode n) { (positive == null ? positive = new ArrayList<>(4) : positive).add(n); }
    void addAfter(RenderTask t) { (after == null ? after = new ArrayList<>(2) : after).add(t); }

    void sortNegative() { if (negative != null && negative.size() > 1) negative.sort(Z_CMP); }
    void sortPositive() { if (positive != null && positive.size() > 1) positive.sort(Z_CMP); }
}
