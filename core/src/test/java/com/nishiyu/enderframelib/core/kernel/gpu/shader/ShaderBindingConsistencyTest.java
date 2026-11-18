package com.nishiyu.enderframelib.core.kernel.gpu.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 全シェーダーで、GLSL に書いた binding 番号（{@code EF_UBO}・{@code EF_TEX2D}・{@code EF_TBO_*}）と、Java 側の宣言
 * （{@link ShaderProgram#firstTextureUnit()}・{@link RecordView#unit()}）が一致し、互いに重ならないこと（T2b）。
 *
 * <p>Vulkan は Java 側の宣言から記述子セットのレイアウトを作り、SPIR-V は GLSL の番号で引くので、食い違うと記述子の更新が不正になる。
 * GL は {@code glUniform1i} で結び直すので食い違っても動き、実機の Vulkan でも絵が違うだけで落ちないことがあり、気付きにくい。
 * 以前は FX（ぼかし・backdrop-filter）と TEX のサンプラーが Java 側だけ 0 始まりで、定数ブロックと同じ binding 0 になっていた
 * （検証レイヤーと WSL の lavapipe が落ちた）。</p>
 */
class ShaderBindingConsistencyTest {
    private static final Pattern DECL = Pattern.compile("EF_(UBO|TEX2D|TBO_F|TBO_I)\\(\\s*(\\d+)\\s*,\\s*(\\w+)\\s*\\)");

    /** GLSL の宣言の「名前 → binding」（マクロの定義行の efBind は数字でないので拾わない）。 */
    private static Map<String, Integer> bindings(ShaderProgram p, List<String> problems) {
        Map<String, Integer> out = new HashMap<>();
        for (String src : new String[]{p.vertexBody(), p.fragmentBody()}) {
            Matcher m = DECL.matcher(src);
            while (m.find()) {
                String name = m.group(3);
                int binding = Integer.parseInt(m.group(2));
                Integer prev = out.put(name, binding);
                if (prev != null && prev != binding) problems.add(p.id() + ": " + name + " の binding が頂点とフラグメントで違う（" + prev + " / " + binding + "）");
            }
        }
        return out;
    }

    @Test
    void glslBindingsMatchTheJavaDeclarations() {
        List<String> problems = new ArrayList<>();
        int checkedSamplers = 0;
        for (ShaderProgram p : ShaderPrograms.all()) {
            Map<String, Integer> b = bindings(p, problems);
            for (int i = 0; i < p.samplers().size(); i++) {
                String name = p.samplers().get(i);
                Integer glsl = b.get(name);
                if (glsl == null) { problems.add(p.id() + ": サンプラー " + name + " が GLSL に無い"); continue; }
                checkedSamplers++;
                if (glsl != p.firstTextureUnit() + i) {
                    problems.add(p.id() + ": サンプラー " + name + " は GLSL で binding " + glsl + "、Java 側は " + (p.firstTextureUnit() + i));
                }
            }
            for (RecordView rv : p.records()) {
                Integer glsl = b.get(rv.sampler());
                if (glsl == null) problems.add(p.id() + ": 記録列 " + rv.sampler() + " が GLSL に無い");
                else if (glsl != rv.unit()) problems.add(p.id() + ": 記録列 " + rv.sampler() + " は GLSL で binding " + glsl + "、Java 側は " + rv.unit());
            }
            // 重なり: 同じ binding を別の名前が使わない（定数ブロックの 0 を含む）
            Map<Integer, String> byBinding = new HashMap<>();
            for (var e : b.entrySet()) {
                String other = byBinding.put(e.getValue(), e.getKey());
                if (other != null) problems.add(p.id() + ": binding " + e.getValue() + " を " + other + " と " + e.getKey() + " が使う");
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(checkedSamplers >= 8, "サンプラーを実際に照合した（空の検証ではない）: " + checkedSamplers);
    }
}
