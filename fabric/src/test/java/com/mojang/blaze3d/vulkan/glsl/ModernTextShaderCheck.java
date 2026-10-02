package com.mojang.blaze3d.vulkan.glsl;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;

/** CPU-only SPIR-V regression check; does not create a window or Vulkan device. */
public final class ModernTextShaderCheck {
    public static void main(String[] args) throws Exception {
        try (var compiler = new GlslCompiler()) {
            for (boolean gui : new boolean[]{false, true}) {
                String vertexSource = preprocess("rendertype_modern_text.vsh", gui);
                for (String variant : List.of("normal", "sdf_fill", "sdf_stroke")) {
                    try (var vertex = compiler.createIntermediary("modernui/text.vsh", vertexSource, ShaderType.VERTEX);
                         var fragment = compiler.createIntermediary("modernui/" + variant + ".fsh",
                                 preprocess("rendertype_modern_text_" + variant + ".fsh", gui), ShaderType.FRAGMENT)) {
                        if (!compatible(vertex, fragment)) {
                            throw new AssertionError("Vulkan varying mismatch: " + variant + ", GUI=" + gui);
                        }
                        // Resource-pack vertices can insert outputs before ModernUI's varyings.
                        // Mojang's rebinder then compacts fragment locations and corrupts text.
                        String extra = vertexSource.replace("out vec4 vertexColor;",
                                "out float packExtra;\nout vec4 vertexColor;")
                                .replace("void main() {", "void main() {\npackExtra = 1.0;");
                        try (var packVertex = compiler.createIntermediary("pack/text.vsh", extra, ShaderType.VERTEX)) {
                            if (compatible(packVertex, fragment)) {
                                throw new AssertionError("Check failed to detect resource-pack varying mismatch");
                            }
                        }
                        System.out.println("Verified " + variant + ", GUI=" + gui);
                    }
                }
            }
        }
    }

    private static boolean compatible(IntermediaryShaderModule vertex, IntermediaryShaderModule fragment) {
        Set<String> inputs = new HashSet<>();
        fragment.inputs().forEach(input -> inputs.add(input.name()));
        int fragmentLocation = 0;
        for (int vertexLocation = 0; vertexLocation < vertex.outputs().size(); vertexLocation++) {
            if (inputs.remove(vertex.outputs().get(vertexLocation).name())) {
                if (vertexLocation != fragmentLocation++) return false;
            }
        }
        return inputs.isEmpty();
    }

    private static String preprocess(String name, boolean gui) throws IOException {
        String source = read("assets/modernui/shaders/core/" + name);
        Set<String> imported = new HashSet<>();
        var processor = new GlslPreprocessor() {
            @Override
            public String applyImport(boolean relative, String path) {
                if (!imported.add(path)) return null;
                String[] location = path.split(":", 2);
                String namespace = location.length == 2 ? location[0] : "minecraft";
                String file = location.length == 2 ? location[1] : location[0];
                try {
                    return read("assets/" + namespace + "/shaders/include/" + file);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        if (gui) source = GlslPreprocessor.injectDefines(source, ShaderDefines.builder().define("IS_GUI").build());
        return String.join("", processor.process(source));
    }

    private static String read(String path) throws IOException {
        try (var stream = ModernTextShaderCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
