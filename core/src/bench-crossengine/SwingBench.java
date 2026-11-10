import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.IntConsumer;

public class SwingBench {
    static final int W = 800, H = 600;
    static double bench(IntConsumer fn, int n, int samples, int warm) {
        for (int i = 0; i < warm; i++) fn.accept(i);
        double[] t = new double[samples]; int k = 0;
        for (int s = 0; s < samples; s++) { long a = System.nanoTime(); for (int i = 0; i < n; i++) fn.accept(k++); t[s] = (System.nanoTime() - a) / 1e6 / n; }
        Arrays.sort(t); return t[samples / 2];
    }
    static void out(String n, double v) { System.out.printf("RESULT %s %.4f ms%n", n, v); }
    static JPanel list;
    static JLabel[] rows;
    static JFrame frame;
    static JScrollPane sp;

    static void build(int count) {
        list = new JPanel(); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setBackground(Color.WHITE);
        rows = new JLabel[count];
        for (int i = 0; i < count; i++) {
            JLabel l = new JLabel("Row item #" + (i + 1) + " with description text");
            l.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, new Color(0xcc, 0xcc, 0xcc)), new EmptyBorder(4, 4, 4, 4)));
            l.setOpaque(true); l.setBackground(Color.WHITE); l.setAlignmentX(0f);
            rows[i] = l; list.add(l);
        }
        sp = new JScrollPane(list);
    }

    public static void main(String[] a) throws Exception {
        System.out.println("java " + System.getProperty("java.version"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                // build (≒ parse + open): コンポーネント木構築 + 初回レイアウト
                double[] bt = new double[20];
                for (int r = 0; r < 40; r++) {
                    long t0 = System.nanoTime();
                    JFrame f = new JFrame(); build(500); f.add(sp); f.setSize(W, H); f.addNotify(); f.validate(); sp.getViewport().getView().getHeight();
                    double ms = (System.nanoTime() - t0) / 1e6; if (r >= 20) bt[r - 20] = ms; f.dispose();
                }
                Arrays.sort(bt); out("swing.open500", bt[10]);
                frame = new JFrame(); build(500); frame.add(sp); frame.setSize(W, H); frame.addNotify(); frame.validate();
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                // フルペイント (ビューポート 800x600 を BufferedImage へ)
                out("swing.paintViewport", bench(i -> { sp.paint(g); }, 20, 15, 100));
                // 幅変更 → validate
                out("swing.relayout(width)", bench(i -> { frame.setSize((i & 1) == 0 ? W - 1 : W, H); frame.validate(); }, 20, 15, 100));
                // テキスト 1 行変更 → validate
                out("swing.text", bench(i -> { rows[250].setText((i & 1) == 0 ? "Row item A with text " + (i & 7) : "Row item B with text " + (i & 7)); list.revalidate(); frame.validate(); }, 100, 15, 300));
                // 1 要素のスタイル（背景色）変更 → paint 無効化のみ
                out("swing.style(setBackground)", bench(i -> { rows[250].setForeground((i & 1) == 0 ? new Color(0x112233) : new Color(0x223344)); }, 1000, 15, 1000));
                // 子の挿入と削除 → validate
                out("swing.child(ins+rem)/2", bench(i -> {
                    if ((i & 1) == 0) { JLabel l = new JLabel("inserted " + i); l.setBorder(rows[0].getBorder()); list.add(l, 250); }
                    else list.remove(250);
                    list.revalidate(); frame.validate(); }, 100, 15, 300));
                // 継承相当: 全ラベルの前景色を変更 + paint
                out("swing.inherited(500 setForeground)", bench(i -> { Color c = (i & 1) == 0 ? new Color(0x112233) : new Color(0x223344); for (JLabel l : rows) l.setForeground(c); }, 50, 15, 100));
                // 1 フレーム (text 変更 + validate + paint)
                out("swing.frame(text+validate+paint)", bench(i -> { rows[250].setText("Row item A with text " + (i & 7)); list.revalidate(); frame.validate(); sp.paint(g); }, 20, 15, 100));
                out("swing.frame(idle paint)", bench(i -> { sp.paint(g); }, 20, 15, 100));

                // JEditorPane (HTML 3.2 + 限定 CSS) に同じ 500 行 HTML
                StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px} li{padding:4px}</style></head><body><ul>");
                for (int i = 1; i <= 500; i++) sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
                String html = sb.append("</ul></body></html>").toString();
                double[] ep = new double[20];
                for (int r = 0; r < 40; r++) {
                    long t0 = System.nanoTime();
                    JEditorPane e = new JEditorPane("text/html", ""); e.setSize(W, H); e.setText(html); e.getPreferredSize();
                    double ms = (System.nanoTime() - t0) / 1e6; if (r >= 20) ep[r - 20] = ms;
                }
                Arrays.sort(ep); out("jeditorpane.parse+layout500", ep[10]);
                JEditorPane e = new JEditorPane("text/html", html); e.setSize(W, H); e.getPreferredSize();
                BufferedImage img2 = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB); Graphics2D g2 = img2.createGraphics();
                out("jeditorpane.paintViewport", bench(i -> e.paint(g2), 20, 15, 100));
                out("jeditorpane.relayout(width)", bench(i -> { e.setSize((i & 1) == 0 ? W - 1 : W, H); e.getPreferredSize(); }, 20, 15, 100));
            } catch (Throwable t) { t.printStackTrace(); }
        });
        System.exit(0);
    }
}
