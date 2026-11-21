import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.GpuApi;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.WindowHost;
import com.nishiyu.enderframelib.core.paint.gpu.GpuBackends;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

/** core/README.md クイックスタートの 4 例を、公開物だけで動かす（A10）。窓は開かず、窓の提供者が見つかることを確かめる。 */
public class ConsumerCheck {
    static final String HTML = "<body style=\"margin:0;background:#0f172a;color:#fff\"><h1>Hello</h1></body>";

    public static void main(String[] args) throws Exception {
        // 1. ヘッドレスで PNG
        Path png = Files.createTempFile("consumer", ".png");
        try (WebViewSurface s = EnderEngine.html(HTML).size(600, 300).build()) {
            s.frame(0, true);
            s.writePng(png);
        }
        check(Files.size(png) > 100, "1: PNG が書けていない");

        // 2. カスタムホスト（画素の受け取り）
        int[] got = new int[2];
        try (WebViewSurface s = EnderEngine.html(HTML).size(80, 40).sink((px, w, h) -> { got[0] = w; got[1] = h; }).build()) {
            s.frame(0, true);
        }
        check(got[0] == 80 && got[1] == 40, "2: sink が呼ばれていない");

        // 3. GPU（実装が両方見つかること。GPU が無い環境では描画だけ省く）
        var ids = GpuBackends.providers().stream().map(p -> p.id()).toList();
        check(ids.equals(java.util.List.of("gl", "vulkan")), "3: GPU 実装の発見 " + ids);
        try (WebViewSurface s = EnderEngine.html(HTML).size(64, 48).target(EnderEngine.Target.GPU).gpu(GpuApi.AUTO).build()) {
            s.frame(0, true);
            check(s.texture().isPresent(), "3: texture が無い");
            System.out.println("3: GPU texture " + s.texture().get().kind());
        } catch (IllegalStateException e) {
            System.out.println("3: SKIP GPU 描画（この環境では GPU を作れない）: " + e.getMessage());
        }

        // 4. 窓（開かない。提供者と StandaloneViewer が公開物から読めること）
        check(ServiceLoader.load(WindowHost.class).findFirst().isPresent(), "4: WindowHost の提供者が無い");
        Class.forName("com.nishiyu.enderframelib.common.StandaloneViewer");

        System.out.println("CONSUMER-CHECK OK");
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
