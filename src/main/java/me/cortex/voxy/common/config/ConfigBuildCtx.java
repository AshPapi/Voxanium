package me.cortex.voxy.common.config;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class ConfigBuildCtx {
    public static final String BASE_SAVE_PATH = "{base_save_path}";
    public static final String WORLD_IDENTIFIER = "{world_identifier}";
    public static final String PLAYER_UUID = "{player_uuid}";
    public static final String DEFAULT_STORAGE_PATH = BASE_SAVE_PATH+"/"+WORLD_IDENTIFIER+"/storage/";


    private final Map<String, String> properties = new HashMap<>();
    private final Stack<String> pathStack = new Stack<>();

    public ConfigBuildCtx setProperty(String property, String value) {
        if (!(property.startsWith("{") && property.endsWith("}"))) {
            throw new IllegalArgumentException("Property name doesnt start with { and end with }");
        }
        this.properties.put(property, value);
        return this;
    }

    public ConfigBuildCtx pushPath(String path) {
        this.pathStack.push(path);
        return this;
    }

    public ConfigBuildCtx popPath() {
        this.pathStack.pop();
        return this;
    }

    private static String concatPath(String a, String b) {
        if (b.contains("..")) {
            throw new IllegalStateException("Relative resolving not supported");
        }

        if ((!a.isBlank()) && !a.endsWith("/")) {
            a += "/";
        }

        if (b.startsWith("/")) {
            return b;
        }

        if (b.startsWith("./")) {
            b = b.substring(2);
        }

        if (b.startsWith(":", 1)) {
            return b;
        }

        return a+b;
    }

    public String resolvePath() {
        String prev = "";
        String path = "";
        do {
            prev = path;
            path = "";
            for (var part : this.pathStack) {
                path = concatPath(path, part);
            }
        } while (!prev.equals(path));
        return path;
    }

    public String substituteString(String string) {
        for (var entry : this.properties.entrySet()) {
            string = string.replace(entry.getKey(), entry.getValue());
        }
        return string;
    }

    public String ensurePathExists(String path) {
        try {
            Files.createDirectories(new File(path).toPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return path;
    }
}
