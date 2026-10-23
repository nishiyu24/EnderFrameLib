package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Swing 用の EnderFrame レンダリングパネル（{@link JPanel}）。
 *
 * <p>通常の Swing ウィンドウ (JFrame) やダイアログにそのまま追加でき、
 * HTML/CSS のレイアウト、スクロール、マウスホバー、クリック判定を行います。</p>
 */
public class EnderPanel extends JPanel {

    private String htmlContent = "";
    private Document document;
    private LayoutBox rootLayoutBox;
    private List<DisplayItem> displayList = Collections.emptyList();

    private final ScrollState scrollState = new ScrollState();
    private final InteractionState interactionState = new InteractionState();
    private final FontResolver fontResolver = EnderFrameStandalone.defaultFontResolver();
    private final UiDocumentCompiler compiler = new UiDocumentCompiler();

    private final List<BiConsumer<String, MouseEvent>> clickListeners = new CopyOnWriteArrayList<>();

    public EnderPanel() {
        setOpaque(true);
        setBackground(Color.WHITE);

        // リサイズ時の再レイアウト
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayout();
                repaint();
            }
        });

        // マウス操作とヒットテスト
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHoverAndCursor(e.getX(), e.getY());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // スクロール処理
                if (document != null && document.root != null) {
                    int currentY = scrollState.getScrollY(document.root);
                    int currentX = scrollState.getScrollX(document.root);
                    int notches = e.getWheelRotation();
                    scrollState.setScroll(document.root, currentX, Math.max(0, currentY + notches * 24));
                    relayout();
                    repaint();
                }
            }
        };

        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
    }

    /**
     * HTML 文字列を読み込み、即座にレイアウトと再描画を行います。
     */
    public void loadHtml(String html) {
        this.htmlContent = html != null ? html : "";
        relayout();
        repaint();
    }

    /**
     * 要素のクリックリスナーを登録します（第1引数は要素 ID、第2引数は AWT マウスイベント）。
     */
    public void addClickListener(BiConsumer<String, MouseEvent> listener) {
        if (listener != null) {
            clickListeners.add(listener);
        }
    }

    public synchronized void relayout() {
        if (htmlContent == null || htmlContent.isEmpty()) {
            displayList = Collections.emptyList();
            return;
        }

        int w = Math.max(1, getWidth() > 0 ? getWidth() : getPreferredSize().width);
        int h = Math.max(1, getHeight() > 0 ? getHeight() : getPreferredSize().height);

        this.document = compiler.parseDocument(htmlContent, "", w, h);
        this.rootLayoutBox = compiler.layoutDocument(document, w, h, Map.of(), scrollState, fontResolver, interactionState);

        DisplayListBuilder builder = new DisplayListBuilder(w, h, document.sheet.keyframes, scrollState);
        this.displayList = builder.render(rootLayoutBox);
    }

    private DisplayItem findItemAt(int x, int y) {
        if (displayList == null || displayList.isEmpty()) return null;
        for (int i = displayList.size() - 1; i >= 0; i--) {
            DisplayItem item = displayList.get(i);
            if (x >= item.x() && x <= item.x() + item.width()
                    && y >= item.y() && y <= item.y() + item.height()) {
                return item;
            }
        }
        return null;
    }

    private void updateHoverAndCursor(int mx, int my) {
        DisplayItem hit = findItemAt(mx, my);
        if (hit != null) {
            ElementType type = hit.type();
            if (type == ElementType.BUTTON || type == ElementType.A || hit.hasHover()) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                return;
            }
        }
        setCursor(Cursor.getDefaultCursor());
    }

    private void handleClick(MouseEvent e) {
        DisplayItem hit = findItemAt(e.getX(), e.getY());
        if (hit != null) {
            String elemId = hit.baseId();
            for (BiConsumer<String, MouseEvent> listener : clickListeners) {
                try {
                    listener.accept(elemId, e);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (displayList == null || displayList.isEmpty()) return;

        Graphics2D g2d = (Graphics2D) g.create();
        try {
            AwtDisplayItemDrawer.render(g2d, displayList);
        } finally {
            g2d.dispose();
        }
    }
}
