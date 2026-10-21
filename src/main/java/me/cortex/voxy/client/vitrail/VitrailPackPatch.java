package me.cortex.voxy.client.vitrail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VitrailPackPatch {
    public static final int VERSION = 1;

    public record Blend(boolean enabled, int srcColour, int dstColour, int srcAlpha, int dstAlpha) {
        public static final Blend OFF = new Blend(false, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ZERO, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ZERO);
        public static final Blend DEFAULT_TRANSLUCENT = new Blend(true, VK10.VK_BLEND_FACTOR_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

    public final String place;
    public final int[] opaqueDrawBuffers;
    public final int[] translucentDrawBuffers;
    public final List<String> uniforms;
    public final Map<String, String> samplers;
    public final String opaqueSource;
    public final BitSet opaqueLive;
    public final String translucentSource;
    public final BitSet translucentLive;
    public final String taa;
    public final Map<String, String> jsonDefines;
    public final Blend allBlend;
    public final Map<Integer, Blend> blends;
    public final List<String> notes;

    private VitrailPackPatch(String place, int[] opaqueDrawBuffers, int[] translucentDrawBuffers, List<String> uniforms,
                             Map<String, String> samplers, Source opaque, Source translucent, String taa,
                             Map<String, String> jsonDefines, Blend allBlend, Map<Integer, Blend> blends, List<String> notes) {
        this.place = place;
        this.opaqueDrawBuffers = opaqueDrawBuffers;
        this.translucentDrawBuffers = translucentDrawBuffers;
        this.uniforms = Collections.unmodifiableList(uniforms);
        this.samplers = Collections.unmodifiableMap(samplers);
        this.opaqueSource = opaque.text();
        this.opaqueLive = opaque.live();
        this.translucentSource = translucent == null ? null : translucent.text();
        this.translucentLive = translucent == null ? null : translucent.live();
        this.taa = taa;
        this.jsonDefines = Collections.unmodifiableMap(jsonDefines);
        this.allBlend = allBlend;
        this.blends = Collections.unmodifiableMap(blends);
        this.notes = Collections.unmodifiableList(notes);
    }

    private record Source(String text, BitSet live) {
        static Source of(String text) {
            BitSet live = new BitSet();
            live.set(0, text.split("\n", -1).length);
            return new Source(text, live);
        }
    }

    public Blend translucentBlend(int buffer) {
        Blend blend = this.blends.get(buffer);
        if (blend != null) {
            return blend;
        }
        return this.allBlend != null ? this.allBlend : Blend.DEFAULT_TRANSLUCENT;
    }

    public static VitrailPackPatch load(Path packPath, Map<String, OptionValue> chosen, String profile, String place) throws IOException {
        try (OpenedPack pack = OpenedPack.open(packPath, chosen == null ? Map.of() : chosen, profile == null ? "" : profile)) {
            IncludeExpander expander = new IncludeExpander(pack.source(), pack.settings());
            String prefix = place == null || place.isEmpty() ? "" : place + "/";
            Optional<Path> jsonFile = pack.source().file(prefix + "voxy.json");
            if (jsonFile.isEmpty()) {
                return null;
            }
            IncludeExpander.ExpandedUnit jsonUnit = expander.expand(jsonFile.get());
            String json = jsonText(jsonUnit);
            if (json.isBlank()) {
                return null;
            }
            List<String> notes = new ArrayList<>();
            JsonObject root = parse(json);

            int version = root.has("version") ? root.get("version").getAsInt() : -1;
            if (version != VERSION) {
                throw new IllegalStateException("voxy.json patch version is " + version + ", expected " + VERSION);
            }

            Source opaque = glsl(expander, pack, prefix + "voxy_opaque.glsl");
            if (opaque == null && string(root, "opaquePatchData") != null) {
                opaque = Source.of(string(root, "opaquePatchData"));
            }
            if (opaque == null) {
                throw new IllegalStateException("voxy.json has no opaque patch");
            }
            Source translucent = glsl(expander, pack, prefix + "voxy_translucent.glsl");
            if (translucent == null && string(root, "translucentPatchData") != null) {
                translucent = Source.of(string(root, "translucentPatchData"));
            }
            Source taaSource = glsl(expander, pack, prefix + "voxy_taa.glsl");
            String taa = taaSource == null ? null : taaSource.text();
            if (taa == null) {
                taa = string(root, "taaOffset");
            }

            int[] opaqueBuffers = ints(root, "opaqueDrawBuffers");
            int[] translucentBuffers = ints(root, "translucentDrawBuffers");
            if (opaqueBuffers == null) {
                throw new IllegalStateException("voxy.json has no opaqueDrawBuffers");
            }
            if (translucentBuffers == null) {
                translucentBuffers = new int[0];
            }

            List<String> uniforms = new ArrayList<>();
            if (root.has("uniforms") && root.get("uniforms").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("uniforms")) {
                    if (element != null && element.isJsonPrimitive() && !uniforms.contains(element.getAsString())) {
                        uniforms.add(element.getAsString());
                    }
                }
            }

            Map<String, String> samplers = samplers(root.get("samplers"));

            Map<Integer, Blend> blends = new LinkedHashMap<>();
            Blend allBlend = blending(root.get("blending"), blends, notes);

            return new VitrailPackPatch(place == null ? "" : place, opaqueBuffers, translucentBuffers, uniforms, samplers,
                    opaque, translucent, taa, new LinkedHashMap<>(jsonUnit.defines()), allBlend, blends, notes);
        }
    }

    private static Source glsl(IncludeExpander expander, OpenedPack pack, String relative) throws IOException {
        Optional<Path> file = pack.source().file(relative);
        if (file.isEmpty()) {
            return null;
        }
        IncludeExpander.ExpandedUnit unit = expander.expand(file.get());
        StringBuilder out = new StringBuilder();
        List<String> lines = unit.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("#version")) {
                line = "";
            }
            out.append(line);
            if (i + 1 < lines.size()) {
                out.append('\n');
            }
        }
        return new Source(out.toString(), unit.live());
    }

    private static String jsonText(IncludeExpander.ExpandedUnit unit) {
        StringBuilder out = new StringBuilder();
        List<String> lines = unit.lines();
        boolean[] inString = {false};
        for (int i = 0; i < lines.size(); i++) {
            if (!unit.isLive(i)) {
                continue;
            }
            String line = lines.get(i);
            if (line.trim().startsWith("#")) {
                continue;
            }
            out.append(substitute(line, unit.defines(), inString)).append('\n');
        }
        String text = out.toString().replace("\\", "\\\\");
        StringBuilder fixed = new StringBuilder(text.length());
        for (String line : text.split("\n")) {
            int comment = line.indexOf("//");
            if (comment != -1) {
                fixed.append(line, 0, comment);
                fixed.append(line.substring(comment).replace("\"", "\\\""));
            } else {
                fixed.append(line);
            }
            fixed.append('\n');
        }
        return fixed.toString().replaceAll("void _cfi_ignoreMarker\\(\\) \\{\\}", "");
    }

    private static String substitute(String line, Map<String, String> defines, boolean[] inString) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString[0] = !inString[0];
                out.append(c);
                i++;
                continue;
            }
            if (!inString[0] && (Character.isLetter(c) || c == '_') && (i == 0 || !identifierChar(line.charAt(i - 1)))) {
                Matcher matcher = IDENTIFIER.matcher(line);
                if (matcher.find(i) && matcher.start() == i) {
                    String name = matcher.group();
                    String value = defines.get(name);
                    for (int depth = 0; value != null && depth < 8 && defines.containsKey(value.trim()); depth++) {
                        value = defines.get(value.trim());
                    }
                    out.append(value != null && !value.isBlank() ? value.trim() : name);
                    i = matcher.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean identifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static JsonObject parse(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.LENIENT);
        JsonElement element = JsonParser.parseReader(reader);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("voxy.json is not a json object");
        }
        return element.getAsJsonObject();
    }

    private static String string(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static int[] ints(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        List<Integer> values = new ArrayList<>();
        for (JsonElement one : array) {
            if (one != null && one.isJsonPrimitive()) {
                values.add(one.getAsInt());
            }
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<String, String> samplers(JsonElement element) {
        Map<String, String> samplers = new LinkedHashMap<>();
        if (element == null) {
            return samplers;
        }
        if (element.isJsonArray()) {
            for (JsonElement one : element.getAsJsonArray()) {
                if (one != null && one.isJsonPrimitive()) {
                    String name = one.getAsString();
                    samplers.put(name, name.equals("shadowtex") ? "sampler2DShadow" : "sampler2D");
                }
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String type = "sampler2D";
                if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                    if (entry.getKey().equals("shadowtex")) {
                        type = "sampler2DShadow";
                    }
                } else {
                    type = entry.getValue().getAsString();
                }
                samplers.put(entry.getKey(), type);
            }
        }
        return samplers;
    }

    private static Blend blending(JsonElement element, Map<Integer, Blend> into, List<String> notes) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString().equalsIgnoreCase("off") ? Blend.OFF : null;
        }
        if (!element.isJsonObject()) {
            notes.add("blending is neither off nor an object, it is ignored");
            return null;
        }
        Blend all = null;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            int buffer;
            try {
                buffer = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException e) {
                notes.add("blending key " + entry.getKey() + " is not a number");
                continue;
            }
            Blend blend = blend(entry.getValue(), notes);
            if (blend == null) {
                continue;
            }
            if (buffer == -1) {
                all = blend;
            } else {
                into.put(buffer, blend);
            }
        }
        return all;
    }

    private static Blend blend(JsonElement value, List<String> notes) {
        List<String> parts = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement one : value.getAsJsonArray()) {
                parts.add(one.getAsString());
            }
        } else if (value.isJsonPrimitive()) {
            String text = value.getAsString().trim();
            if (text.equalsIgnoreCase("off")) {
                return Blend.OFF;
            }
            for (String part : text.split("\\s+")) {
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        }
        if (parts.size() < 4) {
            notes.add("blend state " + value + " does not name four factors");
            return null;
        }
        int[] factors = new int[4];
        for (int i = 0; i < 4; i++) {
            factors[i] = factor(parts.get(i));
            if (factors[i] < 0) {
                notes.add("unknown blend factor " + parts.get(i));
                return null;
            }
        }
        return new Blend(true, factors[0], factors[1], factors[2], factors[3]);
    }

    private static int factor(String name) {
        String key = name.toUpperCase();
        if (key.startsWith("GL_")) {
            key = key.substring(3);
        }
        return switch (key) {
            case "ZERO" -> VK10.VK_BLEND_FACTOR_ZERO;
            case "ONE" -> VK10.VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK10.VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "SRC_ALPHA" -> VK10.VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK10.VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case "DST_COLOR" -> VK10.VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA_SATURATE" -> VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case "SRC1_COLOR" -> VK10.VK_BLEND_FACTOR_SRC1_COLOR;
            case "ONE_MINUS_SRC1_COLOR" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR;
            case "SRC1_ALPHA" -> VK10.VK_BLEND_FACTOR_SRC1_ALPHA;
            case "ONE_MINUS_SRC1_ALPHA" -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA;
            default -> -1;
        };
    }
}
