package me.cortex.voxy.common.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.ModList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Serialization {
    public static final Set<Class<?>> CONFIG_TYPES = new HashSet<>();
    public static Gson GSON;

    private static final class GsonConfigSerialization <T> implements TypeAdapterFactory {
        private final String typeField = "TYPE";
        private final Class<T> clz;

        private final Map<String, Class<? extends T>> name2type = new HashMap<>();
        private final Map<Class<? extends T>, String> type2name = new HashMap<>();

        private GsonConfigSerialization(Class<T> clz) {
            this.clz = clz;
        }

        public GsonConfigSerialization<T> register(String typeName, Class<? extends T> cls) {
            if (this.name2type.put(typeName, cls) != null) {
                throw new IllegalStateException("Type name already registered: " + typeName);
            }
            if (this.type2name.put(cls, typeName) != null) {
                throw new IllegalStateException("Class already registered with type name: " + typeName + ", " + cls);
            }
            return this;
        }


        private T deserialize(Gson gson, JsonElement json) {
            var retype = this.name2type.get(json.getAsJsonObject().remove(this.typeField).getAsString());
            return gson.getDelegateAdapter(this, TypeToken.get(retype)).fromJsonTree(json);
        }

        private JsonElement serialize(Gson gson, T value) {
            String name = this.type2name.get(value.getClass());
            if (name == null) {
                name = "UNKNOWN_TYPE_{" + value.getClass().getName() + "}";
            }

            var vjson = gson
                    .getDelegateAdapter(this, TypeToken.get((Class<T>) value.getClass()))
                    .toJsonTree(value);
            var json = new JsonObject();
            json.addProperty(this.typeField, name);
            vjson.getAsJsonObject().asMap().forEach(json::add);
            return json;
        }


        @Override
        public <X> TypeAdapter<X> create(Gson gson, TypeToken<X> type) {
            if (this.clz.isAssignableFrom(type.getRawType())) {
                var jsonObjectAdapter = gson.getAdapter(JsonElement.class);

                return (TypeAdapter<X>) new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        jsonObjectAdapter.write(out, GsonConfigSerialization.this.serialize(gson, value));
                    }

                    @Override
                    public T read(JsonReader in) throws IOException {
                        var obj = jsonObjectAdapter.read(in);
                        return GsonConfigSerialization.this.deserialize(gson, obj);
                    }
                };
            }
            return null;
        }
    }

    public static void init() {
        String BASE_SEARCH_PACKAGE = "me.cortex.voxy";

        Map<Class<?>, GsonConfigSerialization<?>> serializers = new HashMap<>();

        Set<String> clazzs = new LinkedHashSet<>();

        List<Class<?>> knownConfigClasses = List.of(
            me.cortex.voxy.common.config.compressors.LZ4Compressor.Config.class,
            me.cortex.voxy.common.config.compressors.ZSTDCompressor.Config.class,
            me.cortex.voxy.common.config.section.SectionSerializationStorage.Config.class,
            me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend.Config.class,
            me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend.Config.class,
            me.cortex.voxy.common.config.storage.other.ConditionalStorageBackendConfig.class,
            me.cortex.voxy.common.config.storage.other.BasicPathInsertionConfig.class,
            me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor.Config.class,
            me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor.Config.class,
            me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor.Config2.class,
            me.cortex.voxy.common.config.storage.other.ReadonlyCachingLayer.Config.class,
            me.cortex.voxy.common.config.storage.redis.RedisStorageBackend.Config.class,
            me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend.Config.class
        );
        for (var kc : knownConfigClasses) {
            clazzs.add(kc.getName());
        }

        var mod = ModList.get() != null ? ModList.get().getModContainerById("voxy").orElse(null) : null;
        if (mod != null) {
            try {
                var path = mod.getModInfo().getOwningFile().getFile().getFilePath();
                clazzs.addAll(collectAllClasses(path, BASE_SEARCH_PACKAGE));
            } catch (Exception ignored) {}
        }
        clazzs.addAll(collectAllClasses(BASE_SEARCH_PACKAGE));
        int count = 0;
        outer:
        for (var clzName : clazzs) {
            if (VoxyCommon.IS_DEDICATED_SERVER&&clzName.startsWith("me.cortex.voxy.client")) {
                continue;
            }
            if (!clzName.toLowerCase(Locale.ROOT).contains("config")) {
                continue;
            }
            if (clzName.contains("mixin")) {
                continue;
            }
            if (clzName.contains("ModMenuIntegration")) {
                continue;
            }
            if (clzName.contains("VoxyConfigScreenPages")) {
                continue;
            }
            if (clzName.endsWith("VoxyConfig")) {
                continue;
            }

            if (clzName.equals(Serialization.class.getName())) {
                continue;
            }

            try {
                var clz = Class.forName(clzName);
                if (Modifier.isAbstract(clz.getModifiers())) {
                    continue;
                }
                var original = clz;
                while ((clz = clz.getSuperclass()) != null) {
                    if (CONFIG_TYPES.contains(clz)) {
                        Method nameMethod = null;
                        try {
                            nameMethod = original.getMethod("getConfigTypeName");
                            nameMethod.setAccessible(true);
                        } catch (NoSuchMethodException e) {}
                        if (nameMethod == null) {
                            Logger.error("WARNING: Config class " + clzName + " doesnt contain a getConfigTypeName and thus wont be serializable");
                            continue outer;
                        }
                        count++;
                        String name = (String) nameMethod.invoke(null);
                        serializers.computeIfAbsent(clz, GsonConfigSerialization::new)
                                .register(name, (Class) original);
                        Logger.info("Registered " + original.getSimpleName() + " as " + name + " for config type " + clz.getSimpleName());
                        break;
                    }
                }
            } catch (Throwable e) {
                Logger.error("Error while setting up config serialization", e);
            }
        }

        var builder = new GsonBuilder()
                .setPrettyPrinting();
        for (var entry : serializers.entrySet()) {
            builder.registerTypeAdapterFactory(entry.getValue());
        }

        GSON = builder.create();
        Logger.info("Registered " + count + " config types");
    }

    private static List<String> collectAllClasses(String pack) {
        try {
            InputStream stream = Serialization.class.getClassLoader()
                    .getResourceAsStream(pack.replaceAll("[.]", "/"));
            if (stream == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            return reader.lines().flatMap(inner -> {
                if (inner.endsWith(".class")) {
                    return Stream.of(pack + "." + inner.replace(".class", ""));
                } else if (!inner.contains(".")) {
                    return collectAllClasses(pack + "." + inner).stream();
                } else {
                    return Stream.of();
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            Logger.error("Failed to collect classes in package: " + pack, e);
            return List.of();
        }
    }

    private static List<String> collectAllClasses(Path base, String pack) {
        if (base == null || !Files.exists(base)) {
            return List.of();
        }
        if (Files.isRegularFile(base)) {
            List<String> list = new ArrayList<>();
            try (var jar = new JarFile(base.toFile())) {
                String prefix = pack.replace('.', '/') + "/";
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith(prefix) && name.endsWith(".class")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        list.add(className);
                    }
                }
            } catch (Exception e) {
                Logger.error("Failed to read classes from jar: " + base, e);
            }
            return list;
        }
        if (!Files.exists(base.resolve(pack.replaceAll("[.]", "/")))) {
            return List.of();
        }
        try {
            return Files.list(base.resolve(pack.replaceAll("[.]", "/"))).flatMap(inner -> {
                if (inner.getFileName().toString().endsWith(".class")) {
                    return Stream.of(pack + "." + inner.getFileName().toString().replace(".class", ""));
                } else if (Files.isDirectory(inner)) {
                    return collectAllClasses(base, pack + "." + inner.getFileName()).stream();
                } else {
                    return Stream.of();
                }
            }).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
