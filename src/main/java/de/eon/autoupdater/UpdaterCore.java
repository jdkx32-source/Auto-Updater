package de.eon.autoupdater;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixins;

import java.io.InputStreamReader;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

public class UpdaterCore {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoUpdater");
    private static final List<Path> loadedJars = new ArrayList<>();

    // Constants
    private static final String DOWNLOAD_URL = "DOWNLOAD_LINK";
    private static final String CACHE_DIR = "cache";
    private static final String JAR_FILE_NAME = "EonCCMod.jar";
    private static final String DEPS_DIR = "deps";
    private static final String FABRIC_JSON = "fabric.mod.json";
    private static final String JARS_KEY = "jars";
    private static final String FILE_KEY = "file";
    private static final String DELEGATE_FIELD = "delegate";
    private static final String ADD_CODE_SOURCE_METHOD = "addCodeSource";
    private static final String LAUNCHER_BASE_CLASS = "net.fabricmc.loader.impl.launch.FabricLauncherBase";
    private static final String GET_LAUNCHER_METHOD = "getLauncher";
    private static final String ADD_TO_CP_METHOD = "addToClassPath";
    private static final String ADD_URL_FWD_METHOD = "addUrlFwd";
    private static final String MIXINS_KEY = "mixins";
    private static final String CONFIG_KEY = "config";
    private static final String ENTRYPOINTS_KEY = "entrypoints";
    private static final String MAIN_KEY = "main";
    private static final String CLIENT_KEY = "client";
    private static final String VALUE_KEY = "value";
    private static final String ON_INIT_METHOD = "onInitialize";
    private static final String ON_INIT_CLIENT_METHOD = "onInitializeClient";

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoupdater.properties");

    public static List<Path> getLoadedJars() {
        return loadedJars;
    }

    public static void preLaunch() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;

        Properties props = loadConfig();
        if ("false".equals(props.getProperty("enabled", "true"))) {
            LOGGER.info("Auto-Updater is disabled via config.");
            return;
        }

        showTransparencyPopup(props);

        Path cachePath = FabricLoader.getInstance().getGameDir().resolve(CACHE_DIR);
        Path jarPath = cachePath.resolve(JAR_FILE_NAME);

        LOGGER.info("Auto-Updater is checking for the newest update...");
        LOGGER.info("Source: " + DOWNLOAD_URL);
        LOGGER.info("Target: " + jarPath.toAbsolutePath());

        try {
            if (!Files.exists(cachePath)) Files.createDirectories(cachePath);

            if (!downloadJar(DOWNLOAD_URL, jarPath) && !Files.exists(jarPath)) {
                LOGGER.warn("Failed to download update and no local cache found.");
                return;
            }

            LOGGER.info("Update verified. Loading " + JAR_FILE_NAME + "...");
            loadedJars.clear();
            loadModAndDependencies(jarPath);
        } catch (Exception ignored) {}
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                props.load(in);
            } catch (Exception ignored) {}
        }
        return props;
    }

    private static void showTransparencyPopup(Properties props) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;

        if ("true".equals(props.getProperty("skipPopup"))) {
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        Object[] options = {"OK", "Don't show again", "Disable auto-updates"};
        int n = JOptionPane.showOptionDialog(null,
                "Auto-Updater is checking for the newest update...\n" +
                "Source: " + DOWNLOAD_URL + "\n\n" +
                "This mod automatically downloads and installs updates to keep everything functional.\n" +
                "Note: Disabling auto-updates may lead to limited functionality or bugs not being fixed.",
                "Auto-Updater Transparency Notice",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]);

        if (n == 1) { // "Don't show again"
            props.setProperty("skipPopup", "true");
            saveConfig(props);
        } else if (n == 2) { // "Disable auto-updates"
            props.setProperty("enabled", "false");
            saveConfig(props);
            JOptionPane.showMessageDialog(null, 
                "Auto-updates have been disabled. You can re-enable them in the config file.",
                "Auto-Updater Disabled", 
                JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void saveConfig(Properties props) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (java.io.OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "AutoUpdater Configuration");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save AutoUpdater configuration", e);
        }
    }

    public static void init() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        for (Path jar : loadedJars) {
            if (Files.exists(jar)) runEntrypoints(jar, MAIN_KEY);
        }
    }

    public static void initClient() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        for (Path jar : loadedJars) {
            if (Files.exists(jar)) runEntrypoints(jar, CLIENT_KEY);
        }
    }

    private static void loadModAndDependencies(Path jarPath) {
        if (loadedJars.contains(jarPath)) return;

        injectIntoClasspath(jarPath);
        injectIntoModContainer(jarPath);
        registerMixins(jarPath);
        loadedJars.add(jarPath);

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(FABRIC_JSON);
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            if (json.has(JARS_KEY)) {
                JsonArray nestedJars = json.getAsJsonArray(JARS_KEY);
                Path depsDir = jarPath.getParent().resolve(DEPS_DIR);
                if (!Files.exists(depsDir)) Files.createDirectories(depsDir);

                for (JsonElement e : nestedJars) {
                    String nestedPath = e.getAsJsonObject().get(FILE_KEY).getAsString();
                    JarEntry nestedEntry = jarFile.getJarEntry(nestedPath);
                    if (nestedEntry != null) {
                        String fileName = Path.of(nestedPath).getFileName().toString();
                        Path extractedPath = depsDir.resolve(fileName);
                        try (InputStream in = jarFile.getInputStream(nestedEntry)) {
                            Files.copy(in, extractedPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        loadModAndDependencies(extractedPath);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void injectIntoModContainer(Path jarPath) {
        try {
            net.fabricmc.loader.api.ModContainer mc = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("autoupdater").orElse(null);
            if (mc != null) {
                try {
                    mc.getRootPaths();
                } catch (Throwable ignored) {}

                Method obtainRootPathMethod = findMethod(mc.getClass(), "obtainRootPath", Path.class);
                Path newRoot = jarPath;
                if (obtainRootPathMethod != null) {
                    obtainRootPathMethod.setAccessible(true);
                    newRoot = (Path) obtainRootPathMethod.invoke(null, jarPath);
                }

                Field rootsField = findField(mc.getClass(), "roots");
                if (rootsField != null) {
                    rootsField.setAccessible(true);
                    List<Path> roots = (List<Path>) rootsField.get(mc);
                    List<Path> newRoots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
                    if (!newRoots.contains(newRoot)) {
                        newRoots.add(newRoot);
                        rootsField.set(mc, newRoots);
                    }
                }

                Field codeSourcePathsField = findField(mc.getClass(), "codeSourcePaths");
                if (codeSourcePathsField != null) {
                    codeSourcePathsField.setAccessible(true);
                    List<Path> codeSourcePaths = (List<Path>) codeSourcePathsField.get(mc);
                    List<Path> newCodeSourcePaths = codeSourcePaths == null ? new ArrayList<>() : new ArrayList<>(codeSourcePaths);
                    if (!newCodeSourcePaths.contains(jarPath)) {
                        newCodeSourcePaths.add(jarPath);
                        codeSourcePathsField.set(mc, newCodeSourcePaths);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void injectIntoClasspath(Path jarPath) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            
            try {
                Field delegateField = findField(cl.getClass(), DELEGATE_FIELD);
                if (delegateField != null) {
                    delegateField.setAccessible(true);
                    Object delegate = delegateField.get(cl);
                    Method addCodeSourceMethod = findMethod(delegate.getClass(), ADD_CODE_SOURCE_METHOD, Path.class);
                    if (addCodeSourceMethod != null) {
                        addCodeSourceMethod.setAccessible(true);
                        addCodeSourceMethod.invoke(delegate, jarPath);
                    }
                }
            } catch (Exception ignored) {}

            try {
                Class<?> launcherBaseClass = Class.forName(LAUNCHER_BASE_CLASS);
                Object launcher = launcherBaseClass.getMethod(GET_LAUNCHER_METHOD).invoke(null);
                for (Method m : launcher.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(ADD_TO_CP_METHOD)) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == Path[].class) {
                            m.invoke(launcher, (Object) new Path[]{jarPath});
                        }
                    }
                }
            } catch (Exception ignored) {}

            Method m = findMethod(cl.getClass(), ADD_URL_FWD_METHOD, URL.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(cl, jarPath.toUri().toURL());
            }
        } catch (Exception ignored) {}
    }

    private static void registerMixins(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(FABRIC_JSON);
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            if (json.has(MIXINS_KEY)) {
                JsonArray mixins = json.getAsJsonArray(MIXINS_KEY);
                for (JsonElement m : mixins) {
                    String config = m.isJsonPrimitive() ? m.getAsString() : m.getAsJsonObject().get(CONFIG_KEY).getAsString();
                    Mixins.addConfiguration(config);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void runEntrypoints(Path jarPath, String key) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(FABRIC_JSON);
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            if (json.has(ENTRYPOINTS_KEY)) {
                JsonObject entrypoints = json.getAsJsonObject(ENTRYPOINTS_KEY);
                executeEntrypoints(entrypoints, key);
            }
        } catch (Exception ignored) {}
    }

    private static void executeEntrypoints(JsonObject entrypoints, String key) {
        if (!entrypoints.has(key)) return;
        JsonArray list = entrypoints.getAsJsonArray(key);
        for (JsonElement e : list) {
            String className = e.isJsonPrimitive() ? e.getAsString() : e.getAsJsonObject().get(VALUE_KEY).getAsString();
            try {
                Class<?> clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                
                if (key.equals(MAIN_KEY)) {
                    Method m = findMethod(clazz, ON_INIT_METHOD);
                    if (m != null) m.invoke(instance);
                } else if (key.equals(CLIENT_KEY)) {
                    Method m = findMethod(clazz, ON_INIT_CLIENT_METHOD);
                    if (m != null) m.invoke(instance);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean downloadJar(String urlStr, Path target) {
        try {
            URL url = URI.create(urlStr).toURL();
            try (InputStream in = url.openStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try { return current.getDeclaredMethod(name, parameterTypes); } 
            catch (NoSuchMethodException e) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try { return current.getDeclaredField(name); } 
            catch (NoSuchFieldException e) { current = current.getSuperclass(); }
        }
        return null;
    }
}
