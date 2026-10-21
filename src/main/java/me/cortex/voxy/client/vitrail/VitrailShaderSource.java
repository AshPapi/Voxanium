package me.cortex.voxy.client.vitrail;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VitrailShaderSource {
    public static final int PACK_UNIFORM_BINDING = 9;
    public static final int FIRST_SAMPLER_BINDING = 10;
    public static final String SCENE_DEPTH_SCALE = "voxy_sceneDepthScale";

    private static final Pattern LOOSE_UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;\\s*(?://.*)?$");
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\s+(\\w+)\\s*:\\s*(\\w+).*$");
    private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
    private static final Pattern SIMPLE_VALUE = Pattern.compile("^[\\w.+\\-]*$");
    private static final Pattern RESERVED = Pattern.compile("\\b(sampler|subpassInput)\\b(?!\\s*\\()");

    public record Member(String name, String type, int offset) {
    }

    public record Sampler(String name, String type, int binding) {
    }

    private final List<Member> members;
    private final int blockSize;
    private final List<Sampler> samplers;
    private final String opaqueText;
    private final String translucentText;
    private final Set<String> extensions;
    private final List<String> notes;
    private final int sceneDepthBinding;

    private VitrailShaderSource(List<Member> members, int blockSize, List<Sampler> samplers, String opaqueText,
                                String translucentText, Set<String> extensions, List<String> notes) {
        this.members = Collections.unmodifiableList(members);
        this.blockSize = blockSize;
        this.samplers = Collections.unmodifiableList(samplers);
        this.opaqueText = opaqueText;
        this.translucentText = translucentText;
        this.extensions = Collections.unmodifiableSet(extensions);
        this.notes = Collections.unmodifiableList(notes);
        this.sceneDepthBinding = FIRST_SAMPLER_BINDING + samplers.size();
    }

    public int sceneDepthBinding() {
        return this.sceneDepthBinding;
    }

    public int samplerBindings() {
        return this.samplers.size() + 1;
    }

    public Member member(String name) {
        for (Member member : this.members) {
            if (member.name().equals(name)) {
                return member;
            }
        }
        return null;
    }

    public List<Member> members() {
        return this.members;
    }

    public int blockSize() {
        return this.blockSize;
    }

    public List<Sampler> samplers() {
        return this.samplers;
    }

    public List<String> notes() {
        return this.notes;
    }

    public boolean hasTranslucent() {
        return this.translucentText != null;
    }

    public static VitrailShaderSource build(VitrailPackPatch patch, Function<String, String> uniformTypes, boolean reverseZ) {
        List<String> notes = new ArrayList<>();
        Map<String, String> extraUniforms = new LinkedHashMap<>();
        Map<String, String> extraSamplers = new LinkedHashMap<>();
        Set<String> extensions = new LinkedHashSet<>();

        String opaque = transform(patch.opaqueSource, patch.opaqueLive, patch, uniformTypes, extraUniforms, extraSamplers, extensions, reverseZ);
        String translucent = patch.translucentSource == null ? null
                : transform(patch.translucentSource, patch.translucentLive, patch, uniformTypes, extraUniforms, extraSamplers, extensions, reverseZ);

        Map<String, String> declared = new LinkedHashMap<>();
        declared.put(SCENE_DEPTH_SCALE, "vec2");
        List<String> missing = new ArrayList<>();
        for (String name : patch.uniforms) {
            String type = uniformTypes.apply(name);
            if (type == null) {
                missing.add(name);
                continue;
            }
            declared.put(name, type);
        }
        extraUniforms.forEach(declared::putIfAbsent);
        if (!missing.isEmpty()) {
            notes.add("the pack asks for uniforms nothing serves: " + String.join(", ", missing));
        }

        List<Member> members = new ArrayList<>();
        int offset = 0;
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            int align = alignment(entry.getValue());
            int size = size(entry.getValue());
            if (align <= 0 || size <= 0) {
                notes.add("uniform " + entry.getKey() + " has type " + entry.getValue() + " which cannot be served");
                continue;
            }
            offset = (offset + align - 1) / align * align;
            members.add(new Member(entry.getKey(), entry.getValue(), offset));
            offset += size;
        }
        int blockSize = (offset + 15) / 16 * 16;

        List<Sampler> samplers = new ArrayList<>();
        Map<String, String> allSamplers = new LinkedHashMap<>(patch.samplers);
        extraSamplers.forEach(allSamplers::putIfAbsent);
        int binding = FIRST_SAMPLER_BINDING;
        for (Map.Entry<String, String> entry : allSamplers.entrySet()) {
            samplers.add(new Sampler(entry.getKey(), entry.getValue(), binding++));
        }

        return new VitrailShaderSource(members, blockSize, samplers, opaque, translucent, extensions, notes);
    }

    private static String transform(String source, BitSet live, VitrailPackPatch patch, Function<String, String> uniformTypes,
                                    Map<String, String> extraUniforms, Map<String, String> extraSamplers,
                                    Set<String> extensions, boolean reverseZ) {
        StringBuilder out = new StringBuilder(source.length() + 256);
        String[] lines = source.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = RESERVED.matcher(lines[index]).replaceAll("voxy_$1");
            if (live != null && !live.get(index)) {
                out.append(line).append('\n');
                continue;
            }
            Matcher extension = EXTENSION.matcher(line);
            if (extension.matches()) {
                extensions.add(extension.group(1));
                out.append('\n');
                continue;
            }
            Matcher loose = LOOSE_UNIFORM.matcher(line);
            if (loose.matches()) {
                String type = loose.group(1);
                String[] names = loose.group(2).split("\\s*,\\s*");
                StringBuilder replaced = new StringBuilder();
                for (String name : names) {
                    if (opaqueType(type)) {
                        if (!patch.samplers.containsKey(name)) {
                            extraSamplers.putIfAbsent(name, type);
                        }
                    } else if (!patch.uniforms.contains(name)) {
                        if (uniformTypes.apply(name) != null && size(type) > 0) {
                            extraUniforms.putIfAbsent(name, type);
                        } else if (size(type) > 0) {
                            replaced.append(type).append(' ').append(name).append(" = ").append(type).append("(0); ");
                        } else {
                            replaced.append(type).append(' ').append(name).append("; ");
                        }
                    }
                }
                out.append(replaced).append('\n');
                continue;
            }
            out.append(reverseZ ? FRAG_COORD.matcher(line).replaceAll("voxy_fragCoord()") : line).append('\n');
        }
        return out.toString();
    }

    public static boolean opaqueType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler")
                || type.startsWith("image") || type.startsWith("iimage") || type.startsWith("uimage")
                || type.startsWith("texture");
    }

    public static int alignment(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
            case "vec3", "ivec3", "uvec3", "bvec3", "vec4", "ivec4", "uvec4", "bvec4", "mat3", "mat4" -> 16;
            default -> -1;
        };
    }

    public static int size(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
            case "vec3", "ivec3", "uvec3", "bvec3" -> 12;
            case "vec4", "ivec4", "uvec4", "bvec4" -> 16;
            case "mat3" -> 48;
            case "mat4" -> 64;
            default -> -1;
        };
    }

    public static int components(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 1;
            case "vec2", "ivec2", "uvec2", "bvec2" -> 2;
            case "vec3", "ivec3", "uvec3", "bvec3" -> 3;
            case "vec4", "ivec4", "uvec4", "bvec4" -> 4;
            case "mat3" -> 9;
            case "mat4" -> 16;
            default -> 0;
        };
    }

    public static boolean integral(String type) {
        return type.startsWith("i") || type.startsWith("u") || type.startsWith("b");
    }

    private String declarations(boolean fragment) {
        StringBuilder out = new StringBuilder();
        if (!this.members.isEmpty()) {
            out.append("layout(binding = ").append(PACK_UNIFORM_BINDING).append(", std140) uniform VoxyPackUniforms {\n");
            for (Member member : this.members) {
                out.append("    layout(offset = ").append(member.offset()).append(") ").append(member.type()).append(' ').append(member.name()).append(";\n");
            }
            out.append("};\n");
        }
        if (fragment) {
            for (Sampler sampler : this.samplers) {
                out.append("layout(binding = ").append(sampler.binding()).append(") uniform ").append(sampler.type()).append(' ').append(sampler.name()).append(";\n");
            }
        }
        return out.toString();
    }

    private static String defines(Map<String, String> defines) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : defines.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("GL_") || name.startsWith("__")) {
                continue;
            }
            if (entry.getValue() != null && !SIMPLE_VALUE.matcher(entry.getValue().trim()).matches()) {
                continue;
            }
            out.append("#ifndef ").append(name).append('\n');
            out.append("#define ").append(name);
            String value = entry.getValue();
            if (value != null && !value.isBlank()) {
                out.append(' ').append(value);
            }
            out.append("\n#endif\n");
        }
        return out.toString();
    }

    private String withExtensions(String base) {
        if (this.extensions.isEmpty()) {
            return base;
        }
        int end = base.indexOf('\n');
        if (end < 0 || !base.startsWith("#version")) {
            return base;
        }
        StringBuilder out = new StringBuilder(base.length() + 64 * this.extensions.size());
        out.append(base, 0, end + 1);
        for (String extension : this.extensions) {
            out.append("#extension ").append(extension).append(" : enable\n");
        }
        out.append(base, end + 1, base.length());
        return out.toString();
    }

    public String fragment(String base, boolean translucent, Map<String, String> engineDefines, boolean reverseZ) {
        String text = translucent ? this.translucentText : this.opaqueText;
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(base.length() + text.length() + 4096);
        out.append(withExtensions(base)).append('\n');
        out.append(this.declarations(true));
        out.append(defines(engineDefines));
        out.append("layout(binding = ").append(this.sceneDepthBinding).append(") uniform sampler2D voxySceneDepth;\n");
        out.append("#ifdef PATCHED_SCENE_DEPTH_TEST\n");
        out.append("bool voxy_sceneDepthHidden() {\n");
        out.append("    return texelFetch(voxySceneDepth, ivec2(gl_FragCoord.xy * ").append(SCENE_DEPTH_SCALE).append("), 0).r != PATCHED_SCENE_DEPTH_CLEAR;\n");
        out.append("}\n#endif\n");
        out.append("#ifndef VOXY\n#define VOXY ").append(VitrailVoxy.DEFINE_VERSION).append("\n#endif\n");
        if (reverseZ) {
            out.append("vec4 voxy_fragCoord() {\n    return vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w);\n}\n");
        }
        out.append(text);
        return out.toString();
    }

    public String vertex(String base, String taa, Map<String, String> taaDefines) {
        if (taa == null) {
            return base;
        }
        return base + "\n" + this.declarations(false) + defines(taaDefines) + "vec2 taaShift()\n" + taa + "\n";
    }
}
