package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.common.*;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.nio.file.*;

public final class Repro {
    static long now = 1000;
    static final int FW = Boolean.getBoolean("nores") ? Integer.getInteger("rw", 1280) : 1500, FH = Boolean.getBoolean("nores") ? Integer.getInteger("rh", 800) : 900;
    static String html;
    static long diff(Pixmap a, Pixmap b, int w, int h) {
        long n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int p = a.pixels()[y * a.width() + x], q = b.pixels()[y * b.width() + x];
            int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255)); if (d > 150) n++; }
        return n;
    }
    static void save(Pixmap p, int w, int h, String f) throws Exception {
        var img = new java.awt.image.BufferedImage(w, h, 1);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, p.pixels()[y * p.width() + x]);
        javax.imageio.ImageIO.write(img, "png", new java.io.File(f));
    }
    public static void main(String[] a) throws Exception {
        html = Files.readString(Path.of("core/demo/resources/catalog.html"));
        int W = Integer.getInteger("rw", 1280), H = Integer.getInteger("rh", 800);
        String out = a.length > 0 ? a[0] : ".";
        try (LwjglGlRenderBackend gl = new LwjglGlRenderBackend(W, H, 1f, false, "r")) {
            StandaloneView view = new StandaloneView(html, W, H);
            CatalogApp.wire(view);
            view.frame(now, gl, true);
            String sid = gl.sessionId();
            view.mouseMove(W / 2.0, H / 2.0, sid);
            for (int i = 0; i < 40; i++) {
                view.scroll(0, -1, sid);
                view.mouseMove(W / 2.0 + (i % 5) * 37, H / 2.0 + (i % 3) * 91, sid);
                for (int k = 0; k < 6; k++) { now += 16; view.frame(now, gl, false); }
                if (true) {
                    Pixmap g = copy(gl.snapshotTarget(), W, H);
                    try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
                        view.frame(now, cpu, true);
                        Pixmap c = cpu.pixmap();
                        System.out.println("scroll " + i + " gl-vs-cpu diff px=" + diff(g, c, W, H));
                        if (true) { save(g, W, H, out + "/gl_scroll" + i + ".png"); save(c, W, H, out + "/cpu_scroll" + i + ".png"); }
                    }
                }
            }
            // resize
            int[][] sizes = Boolean.getBoolean("nores") ? new int[0][] : new int[][]{{1000, 700}, {1500, 900}, {900, 600}, {1280, 800}};
            for (int[] s : sizes) {
                gl.resize(s[0], s[1]);
                view.resize(s[0], s[1]);
                now += 16; view.frame(now, gl, true);
                for (int k = 0; k < 3; k++) { now += 16; view.frame(now, gl, true); }
                Pixmap g = copy(gl.snapshotTarget(), s[0], s[1]);
                StandaloneView fresh = new StandaloneView(html, s[0], s[1]);
                CatalogApp.wire(fresh);
                try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(s[0], s[1], 1f)) {
                    view.frame(now, cpu, true);
                    Pixmap c = cpu.pixmap();
                    System.out.println("resize " + s[0] + "x" + s[1] + " gl-vs-cpu(same view) diff=" + diff(g, c, s[0], s[1]));
                    save(g, s[0], s[1], out + "/gl_" + s[0] + ".png"); save(c, s[0], s[1], out + "/cpu_" + s[0] + ".png");
                }
                try (SoftwareRenderBackend cpu2 = new SoftwareRenderBackend(s[0], s[1], 1f)) {
                    fresh.frame(now, cpu2, true);
                    System.out.println("   view-vs-freshlayout diff(top only unscrolled?)=" + diff(copy(cpu2.pixmap(), s[0], s[1]), copy(gl.snapshotTarget(), s[0], s[1]), s[0], s[1]));
                    save(cpu2.pixmap(), s[0], s[1], out + "/fresh_" + s[0] + ".png");
                }
            }
            if (!Boolean.getBoolean("nores")) { gl.resize(1500, 900); view.resize(1500, 900); } view.mouseMove(700, 400, sid);
            for (int i = 0; i < 60; i++) {
                view.scroll(0, (i % 7 == 0) ? 1 : -1, sid); System.out.println("scroll dir " + ((i % 7 == 0) ? "UP" : "DOWN"));
                for (int k = 0; k < 4; k++) { now += 16; view.frame(now, gl, false); }
                Pixmap g = copy(gl.snapshotTarget(), FW, FH);
                try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(FW, FH, 1f)) {
                    view.frame(now, cpu, true);
                    LwjglGlRenderBackend.retainedScroll = false;
                    view.frame(now, gl, true);
                    Pixmap gB = copy(gl.snapshotTarget(), FW, FH);
                    LwjglGlRenderBackend.retainedScroll = true;
                    long dRet = diff(g, gB, FW, FH);
                    if (dRet > 20000) { try { var f = LwjglGlRenderBackend.class.getDeclaredField("rects"); f.setAccessible(true); Object rs = f.get(gl);
                        var rj = rs.getClass().getDeclaredField("rejects"); rj.setAccessible(true); var fc = rs.getClass().getDeclaredMethod("frameCount"); fc.setAccessible(true);
                        var cap = rs.getClass().getDeclaredMethod("capacity"); cap.setAccessible(true);
                        System.out.println("  rects rejects=" + java.util.Arrays.toString((long[]) rj.get(rs)) + " frameCount=" + fc.invoke(rs) + " cap=" + cap.invoke(rs)); } catch (Exception ex) { System.out.println(ex); } }
                    if (dRet > 500) { System.out.println("  RETAIN-vs-DIRECT i=" + i + " px=" + dRet); save(g, FW, FH, out + "/ret_" + i + ".png"); save(gB, FW, FH, out + "/dir_" + i + ".png"); }
                    view.frame(now, gl, true);
                    Pixmap g2 = copy(gl.snapshotTarget(), FW, FH);
                    long d = diff(g2, cpu.pixmap(), FW, FH);
                    long d12 = diff(g, g2, FW, FH);
                    if (d12 > 0) System.out.println("  nonforced-vs-forced GL diff at " + i + " = " + d12);
                    System.out.println("  i=" + i + " d=" + d + " skipped=" + gl.scrollLayersSkipped() + " rec=" + gl.scrollLayersRecorded());
                    if (d > 3000) { System.out.println("post-resize scroll " + i + " BAD px=" + d); save(g, FW, FH, out + "/bad_gl" + i + ".png"); save(cpu.pixmap(), FW, FH, out + "/bad_cpu" + i + ".png"); }
                }
            }
            System.out.println("post-resize scroll done");
        }
        System.exit(0);
    }
    static Pixmap copy(Pixmap p, int w, int h) {
        Pixmap q = new Pixmap(w, h);
        for (int y = 0; y < h; y++) System.arraycopy(p.pixels(), y * p.width(), q.pixels(), y * w, w);
        return q;
    }
}
