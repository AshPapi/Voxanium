package me.cortex.voxy.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipFile;

public class VoxyNativeLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyNativeLoader.class);
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        try {
            Path nativesDir = FMLPaths.GAMEDIR.get().resolve(".voxy_natives");
            Files.createDirectories(nativesDir);

            boolean zstd = extractNative(nativesDir, "zstd", "lwjgl_zstd");
            boolean lmdb = extractNative(nativesDir, "lmdb", "lwjgl_lmdb");

            String absPath = nativesDir.toAbsolutePath().toString();
            String existing = Configuration.LIBRARY_PATH.get();
            if (existing != null && !existing.isBlank()) {
                Configuration.LIBRARY_PATH.set(absPath + File.pathSeparator + existing);
            } else {
                Configuration.LIBRARY_PATH.set(absPath);
            }
            System.setProperty("org.lwjgl.librarypath", Configuration.LIBRARY_PATH.get());
            LOGGER.info("[Voxy] Configured LWJGL native library path: {}", Configuration.LIBRARY_PATH.get());

            if (zstd) {
                try {
                    int zstdVersion = org.lwjgl.util.zstd.Zstd.ZSTD_versionNumber();
                    LOGGER.info("[Voxy] LWJGL ZSTD native library loaded (version: {})", zstdVersion);
                } catch (Throwable t) {
                    LOGGER.error("[Voxy] ZSTD native library was extracted but failed to load", t);
                }
            }
            if (lmdb) {
                try {
                    org.lwjgl.util.lmdb.LMDB.mdb_version((int[]) null, (int[]) null, (int[]) null);
                    LOGGER.info("[Voxy] LWJGL LMDB native library loaded");
                } catch (Throwable t) {
                    LOGGER.error("[Voxy] LMDB native library was extracted but failed to load", t);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[Voxy] Failed to prepare native libraries", t);
        }
    }

    private static boolean extractNative(Path nativesDir, String module, String libName) {
        String mappedName = System.mapLibraryName(Platform.mapLibraryNameBundled(libName));
        String resPath = Platform.get().getName().toLowerCase(Locale.ROOT) + "/"
                + Platform.getArchitecture().name().toLowerCase(Locale.ROOT) + "/org/lwjgl/" + module + "/" + mappedName;
        Path target = nativesDir.resolve(mappedName);

        byte[] bytes = readResource(resPath);
        if (bytes == null) {
            if (Files.isRegularFile(target)) {
                LOGGER.warn("[Voxy] Native {} not found in the voxy jar, using previously extracted {}", resPath, target);
                return true;
            }
            LOGGER.error("[Voxy] Native {} not found in the voxy jar, {} will be unavailable (was the jar built with the natives configuration?)", resPath, module);
            return false;
        }

        try {
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
                LOGGER.info("[Voxy] Native {} already up to date at {}", mappedName, target);
                return true;
            }
            Path tmp = Files.createTempFile(nativesDir, mappedName, ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[Voxy] Extracted native {} to {}", mappedName, target);
            return true;
        } catch (Exception e) {
            if (Files.isRegularFile(target)) {
                LOGGER.warn("[Voxy] Could not update native {} ({}), using existing file", target, e.toString());
                return true;
            }
            LOGGER.error("[Voxy] Could not extract native {} to {}", resPath, target, e);
            return false;
        }
    }

    private static byte[] readResource(String resPath) {
        try {
            var modFile = ModList.get() == null ? null : ModList.get().getModFileById("voxy");
            if (modFile != null) {
                try (InputStream in = modFile.getFile().getContents().openFile(resPath)) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[Voxy] Mod file lookup of {} failed: {}", resPath, t.toString());
        }

        try (InputStream in = VoxyNativeLoader.class.getResourceAsStream("/" + resPath)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (Throwable t) {
            LOGGER.debug("[Voxy] Classloader lookup of {} failed: {}", resPath, t.toString());
        }

        try {
            var source = VoxyNativeLoader.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null && "file".equals(source.getLocation().getProtocol())) {
                Path jar = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(jar)) {
                    try (ZipFile zip = new ZipFile(jar.toFile())) {
                        var entry = zip.getEntry(resPath);
                        if (entry != null) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                return in.readAllBytes();
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[Voxy] Code source lookup of {} failed: {}", resPath, t.toString());
        }
        return null;
    }
}
