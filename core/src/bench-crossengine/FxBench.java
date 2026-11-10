import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.geometry.Insets;
import java.util.*;
import java.util.function.IntConsumer;

public class FxBench {
    static final int W = 800, H = 600;
    static double bench(IntConsumer fn, int n, int samples, int warm) {
        for (int i = 0; i < warm; i++) fn.accept(i);
        double[] t = new double[samples]; int k = 0;
        for (int s = 0; s < samples; s++) { long a = System.nanoTime(); for (int i = 0; i < n; i++) fn.accept(k++); t[s] = (System.nanoTime() - a) / 1e6 / n; }
        Arrays.sort(t); return t[samples / 2];
    }
    static void out(String n, double v) { System.out.printf("RESULT %s %.4f ms%n", n, v); }
    static VBox list; static Label[] rows; static ScrollPane sp; static Scene scene;
    static void build(int count) {
        list = new VBox(); rows = new Label[count];
        for (int i = 0; i < count; i++) {
            Label l = new Label("Row item #" + (i + 1) + " with description text");
            l.setPadding(new Insets(4)); l.setMaxWidth(Double.MAX_VALUE);
            l.setStyle("-fx-border-color: transparent transparent #cccccc transparent; -fx-border-width: 0 0 1 0;");
            rows[i] = l; list.getChildren().add(l);
        }
        sp = new ScrollPane(list); sp.setFitToWidth(true);
        scene = new Scene(sp, W, H);
    }
    static void pulse() { sp.applyCss(); sp.layout(); }
    public static void main(String[] a) throws Exception {
        System.out.println("javafx " + System.getProperty("javafx.runtime.version"));
        Platform.startup(() -> {});
        Object lock = new Object();
        Platform.runLater(() -> {
            try {
                double[] bt = new double[20];
                for (int r = 0; r < 40; r++) { long t0 = System.nanoTime(); build(500); pulse(); double ms = (System.nanoTime() - t0) / 1e6; if (r >= 20) bt[r - 20] = ms; }
                Arrays.sort(bt); out("javafx.open500(build+css+layout)", bt[10]);
                build(500); pulse();
                // フルレイアウト（幅変更）
                out("javafx.relayout(width)", bench(i -> { sp.resize((i & 1) == 0 ? W - 1 : W, H); pulse(); }, 20, 15, 100));
                out("javafx.text", bench(i -> { rows[250].setText((i & 1) == 0 ? "Row item A with text " + (i & 7) : "Row item B with text " + (i & 7)); pulse(); }, 50, 15, 300));
                // :hover 相当の擬似クラス切替 → CSS 再適用
                javafx.css.PseudoClass hov = javafx.css.PseudoClass.getPseudoClass("hover");
                out("javafx.hover(pseudoclass+css)", bench(i -> { rows[250 + (i & 1)].pseudoClassStateChanged(hov, true); rows[250 + ((i + 1) & 1)].pseudoClassStateChanged(hov, false); pulse(); }, 100, 15, 300));
                out("javafx.style(setTextFill)", bench(i -> { rows[250].setTextFill((i & 1) == 0 ? Color.web("#112233") : Color.web("#223344")); pulse(); }, 100, 15, 300));
                out("javafx.child(ins+rem)/2", bench(i -> {
                    if ((i & 1) == 0) { Label l = new Label("inserted " + i); l.setPadding(new Insets(4)); list.getChildren().add(250, l); } else list.getChildren().remove(250);
                    pulse(); }, 50, 15, 200));
                out("javafx.inherited(root style change)", bench(i -> { sp.setStyle((i & 1) == 0 ? "-fx-text-fill:#112233;" : "-fx-text-fill:#223344;"); pulse(); }, 20, 15, 100));
                // スナップショット（描画 + 読み戻しを含む）
                WritableImage img = new WritableImage(W, H);
                out("javafx.snapshot(paint+readback)", bench(i -> { sp.snapshot(null, img); }, 10, 15, 30));
                out("javafx.frame(text+css+layout+snapshot)", bench(i -> { rows[250].setText("Row item A with text " + (i & 7)); pulse(); sp.snapshot(null, img); }, 10, 15, 30));
            } catch (Throwable t) { t.printStackTrace(); }
            synchronized (lock) { lock.notifyAll(); }
        });
        synchronized (lock) { lock.wait(); }
        Platform.exit(); System.exit(0);
    }
}
