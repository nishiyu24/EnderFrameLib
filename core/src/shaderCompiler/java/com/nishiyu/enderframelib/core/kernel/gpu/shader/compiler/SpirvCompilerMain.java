package com.nishiyu.enderframelib.core.kernel.gpu.shader.compiler;

import com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * ビルド時専用：全 {@link ShaderPrograms} の {@link Glsl#vk450(String)} ソースを Vulkan 1.1 向け SPIR-V へコンパイル・検証し、
 * リソースディレクトリへ出力する。{@code lwjgl-shaderc} はビルド時のみ使用し、{@code :core} の実行時依存には含めない。
 */
public final class SpirvCompilerMain {
    private static final String RESOURCE_SUBDIR = "com/nishiyu/enderframelib/core/kernel/gpu/shader/spirv";
    private static final int SPIRV_MAGIC = 0x07230203;

    private SpirvCompilerMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SpirvCompilerMain <output-resource-root>");
        }
        Path outRoot = Path.of(args[0]).resolve(RESOURCE_SUBDIR);
        Files.createDirectories(outRoot);

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            if (options != 0L) Shaderc.shaderc_compile_options_release(options);
            if (compiler != 0L) Shaderc.shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc の初期化に失敗しました");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_optimization_level(
                    options, Shaderc.shaderc_optimization_level_performance);
            Shaderc.shaderc_compile_options_set_warnings_as_errors(options);

            List<String> manifest = new ArrayList<>();
            for (ShaderProgram program : ShaderPrograms.all()) {
                compileStage(compiler, options, outRoot, manifest,
                        program.id() + ".vert", Shaderc.shaderc_vertex_shader, Glsl.vk450(program.vertexBody()));
                compileStage(compiler, options, outRoot, manifest,
                        program.id() + ".frag", Shaderc.shaderc_fragment_shader, Glsl.vk450(program.fragmentBody()));
            }
            Files.writeString(outRoot.resolve("manifest.txt"), String.join("\n", manifest) + "\n");
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void compileStage(
            long compiler, long options, Path outDir, List<String> manifest,
            String baseName, int kind, String source) throws IOException {
        String fileName = baseName + ".glsl";
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, fileName, "main", options);
        if (result == 0L) {
            throw new IllegalStateException("shaderc が null 結果を返しました: " + fileName);
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                String msg = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException("SPIR-V コンパイル失敗 [" + fileName + "] (status=" + status + "):\n"
                        + msg + "\n--- source ---\n" + numberLines(source));
            }
            ByteBuffer bytesBuf = Shaderc.shaderc_result_get_bytes(result);
            if (bytesBuf == null || bytesBuf.remaining() < 20 || (bytesBuf.remaining() % 4) != 0) {
                throw new IllegalStateException("不正な SPIR-V 出力サイズ: " + fileName);
            }
            byte[] bytes = new byte[bytesBuf.remaining()];
            bytesBuf.get(bytes);
            int magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (magic != SPIRV_MAGIC) {
                throw new IllegalStateException(
                        String.format("不正な SPIR-V マジック番号 [%s]: 0x%08x", fileName, magic));
            }
            String spvName = baseName + ".spv";
            Files.write(outDir.resolve(spvName), bytes);
            manifest.add(spvName + " " + bytes.length);
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    private static String numberLines(String src) {
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder(src.length() + lines.length * 6);
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%3d: %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }
}
