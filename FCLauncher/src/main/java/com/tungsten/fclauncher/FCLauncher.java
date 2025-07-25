package com.tungsten.fclauncher;

import static com.tungsten.fclauncher.utils.Architecture.ARCH_X86;
import static com.tungsten.fclauncher.utils.Architecture.is64BitsDevice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;

import com.mio.data.Renderer;
import com.oracle.dalvik.VMLauncher;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.plugins.DriverPlugin;
import com.tungsten.fclauncher.plugins.FFmpegPlugin;
import com.tungsten.fclauncher.plugins.RendererPlugin;
import com.tungsten.fclauncher.utils.Architecture;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FCLauncher {

    private static int FCL_VERSION_CODE = -1;

    private static void log(FCLBridge bridge, String log) {
        bridge.getCallback().onLog(log + "\n");
    }

    private static void printTaskTitle(FCLBridge bridge, String task) {
        log(bridge, "==================== " + task + " ====================");
    }

    private static void logStartInfo(FCLConfig config, FCLBridge bridge, String task) {
        printTaskTitle(bridge, "Start " + task);
        log(bridge, "Device: " + getDeviceName());
        log(bridge, "Architecture: " + Architecture.archAsString(Architecture.getDeviceArchitecture()));
        log(bridge, "CPU: " + getSocName());
        log(bridge, "Android SDK: " + Build.VERSION.SDK_INT);
        log(bridge, "Language: " + Locale.getDefault());

        PackageManager pm = config.getContext().getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(config.getContext().getPackageName(), 0);
            FCL_VERSION_CODE = packageInfo.versionCode;
            log(bridge, "FCL Version Code: " + FCL_VERSION_CODE);
        } catch (PackageManager.NameNotFoundException e) {
            log(bridge, "FCL Version Code: Can't get current version code, exception = " + e.getMessage());
        }
    }

    private static void logModList(FCLBridge bridge) {
        printTaskTitle(bridge, "Mods");
        log(bridge, bridge.getModSummary());
        bridge.setModSummary(null);
    }

    private static Map<String, String> readJREReleaseProperties(String javaPath) throws IOException {
        Map<String, String> jreReleaseMap = new ArrayMap<>();
        BufferedReader jreReleaseReader = new BufferedReader(new FileReader(javaPath + "/release"));
        String currLine;
        while ((currLine = jreReleaseReader.readLine()) != null) {
            if (currLine.contains("=")) {
                String[] keyValue = currLine.split("=");
                jreReleaseMap.put(keyValue[0], keyValue[1].replace("\"", ""));
            }
        }
        jreReleaseReader.close();
        return jreReleaseMap;
    }

    public static String getJreLibDir(String javaPath) throws IOException {
        String jreArchitecture = readJREReleaseProperties(javaPath).get("OS_ARCH");
        if (Architecture.archAsInt(jreArchitecture) == ARCH_X86) {
            jreArchitecture = "i386/i486/i586";
        }
        String jreLibDir = "/lib";
        if (jreArchitecture == null) {
            throw new IOException("Unsupported architecture!");
        }
        for (String arch : jreArchitecture.split("/")) {
            File file = new File(javaPath, "lib/" + arch);
            if (file.exists() && file.isDirectory()) {
                jreLibDir = "/lib/" + arch;
            }
        }
        return jreLibDir;
    }

    private static String getJvmLibDir(String javaPath) throws IOException {
        String jvmLibDir;
        File jvmFile = new File(javaPath + getJreLibDir(javaPath) + "/server/libjvm.so");
        jvmLibDir = jvmFile.exists() ? "/server" : "/client";
        return jvmLibDir;
    }

    private static String getLibraryPath(Context context, String javaPath, String pluginLibPath) throws IOException {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        String libDirName = is64BitsDevice() ? "lib64" : "lib";
        String jreLibDir = getJreLibDir(javaPath);
        String jvmLibDir = getJvmLibDir(javaPath);
        String jliLibDir = "/jli";
        String split = ":";
        return javaPath +
                jreLibDir +
                split +

                javaPath +
                jreLibDir +
                jliLibDir +
                split +

                javaPath +
                jreLibDir +
                jvmLibDir +
                split +

                "/system/" +
                libDirName +
                split +

                "/vendor/" +
                libDirName +
                split +

                "/vendor/" +
                libDirName +
                "/hw" +
                split +

                context.getDir("runtime", 0).getAbsolutePath() + "/jna" +
                split +

                ((pluginLibPath != null) ? pluginLibPath + split : "") +

                nativeDir;
    }

    private static String getLibraryPath(Context context, String pluginLibPath) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        String libDirName = is64BitsDevice() ? "lib64" : "lib";
        String split = ":";
        return "/system/" +
                libDirName +
                split +

                "/vendor/" +
                libDirName +
                split +

                "/vendor/" +
                libDirName +
                "/hw" +
                split +

                ((pluginLibPath != null && !pluginLibPath.isEmpty()) ? pluginLibPath + split : "") +

                nativeDir;
    }

    private static String[] rebaseArgs(FCLConfig config) throws IOException {
        ArrayList<String> argList = new ArrayList<>(Arrays.asList(config.getArgs()));
        argList.add(0, config.getJavaPath() + "/bin/java");
        String[] args = new String[argList.size()];
        for (int i = 0; i < argList.size(); i++) {
            String a = argList.get(i).replace("${natives_directory}", getLibraryPath(config.getContext(), config.getJavaPath(), config.getRenderer().getPath()));
            args[i] = config.getRenderer() == null ? a : a.replace("${gl_lib_name}", config.getRenderer().getGLPath());
        }
        return args;
    }

    private static void addCommonEnv(FCLConfig config, HashMap<String, String> envMap) {
        if (FCL_VERSION_CODE != -1) {
            envMap.put("FCL_VERSION_CODE", FCL_VERSION_CODE + "");
        }
        envMap.put("HOME", config.getLogDir());
        envMap.put("JAVA_HOME", config.getJavaPath());
        envMap.put("FCL_NATIVEDIR", config.getContext().getApplicationInfo().nativeLibraryDir);
        envMap.put("POJAV_NATIVEDIR", config.getContext().getApplicationInfo().nativeLibraryDir);
        envMap.put("DRIVER_PATH", DriverPlugin.getSelected().getPath());
        envMap.put("TMPDIR", config.getContext().getCacheDir().getAbsolutePath());
        envMap.put("PATH", config.getJavaPath() + "/bin:" + Os.getenv("PATH"));
        envMap.put("LD_LIBRARY_PATH", getLibraryPath(config.getContext(), config.getRenderer().getPath()));
        envMap.put("FORCE_VSYNC", "false");
        FFmpegPlugin.discover(config.getContext());
        if (FFmpegPlugin.isAvailable) {
            envMap.put("PATH", FFmpegPlugin.libraryPath + ":" + envMap.get("PATH"));
            envMap.put("LD_LIBRARY_PATH", FFmpegPlugin.libraryPath + ":" + envMap.get("LD_LIBRARY_PATH"));
        }
        if (config.isUseVKDriverSystem()) {
            envMap.put("VULKAN_DRIVER_SYSTEM", "1");
        }
        if (config.isPojavBigCore()) {
            envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        }
    }

    private static void addModLoaderEnv(FCLConfig config, HashMap<String, String> envMap) {
        if (config.getInstalledModLoaders() == null)
            return;

        if (config.getInstalledModLoaders().isInstallForge()) {
            envMap.put("INST_FORGE", "1");
        }
        if (config.getInstalledModLoaders().isInstallNeoForge()) {
            envMap.put("INST_NEOFORGE", "1");
        }
        if (config.getInstalledModLoaders().isInstallLiteLoader()) {
            envMap.put("INST_LITELOADER", "1");
        }
        if (config.getInstalledModLoaders().isInstallFabric()) {
            envMap.put("INST_FABRIC", "1");
        }
        if (config.getInstalledModLoaders().isInstallOptiFine()) {
            envMap.put("INST_OPTIFINE", "1");
        }
        if (config.getInstalledModLoaders().isInstallQuilt()) {
            envMap.put("INST_QUILT", "1");
        }
    }

    private static void addRendererEnv(FCLConfig config, HashMap<String, String> envMap) {
        Renderer renderer = config.getRenderer();
        if (!renderer.getPath().isEmpty()) {
            String eglName = renderer.getEglName();
            if (eglName.startsWith("/")) {
                eglName = renderer.getPath() + "/" + eglName;
            }
            List<String> envList;
            if (FCLBridge.BACKEND_IS_BOAT) {
                envMap.put("LIBGL_STRING", renderer.getName());
                envMap.put("LIBGL_NAME", renderer.getGlName());
                envMap.put("LIBEGL_NAME", eglName);
                envList = renderer.getBoatEnv();
            } else {
                envMap.put("POJAVEXEC_EGL", eglName);
                envList = renderer.getPojavEnv();
            }
            if (envList != null) {
                envList.forEach(env -> {
                    String[] split = env.split("=");
                    if (split[0].equals("DLOPEN") || split.length < 2) {
                        return;
                    }
                    if (split[0].equals("LIB_MESA_NAME")) {
                        envMap.put(split[0], renderer.getPath() + "/" + split[1]);
                    } else if (split[0].equals("MESA_LIBRARY")) {
                        envMap.put(split[0], renderer.getPath() + "/" + split[1]);
                    } else {
                        envMap.put(split[0], split[1]);
                    }
                });
            }
            return;
        }
        boolean useAngle = false;
        if (FCLBridge.BACKEND_IS_BOAT) {
            envMap.put("LIBGL_STRING", renderer.toString());
            envMap.put("LIBGL_NAME", renderer.getGlName());
            if (useAngle && renderer.isEqual(Renderer.ID_GL4ESPLUS)) {
                envMap.put("LIBEGL_NAME", "libEGL_angle.so");
                envMap.put("LIBGL_BACKEND_ANGLE", "1");
            } else {
                envMap.put("LIBEGL_NAME", renderer.getEglName());
                envMap.put("LIBGL_BACKEND_ANGLE", "0");
            }
        }
        if (renderer.isEqual(Renderer.ID_GL4ES) || renderer.isEqual(Renderer.ID_VGPU)) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NORMALIZE", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NOERROR", "1");
            if (!FCLBridge.BACKEND_IS_BOAT) {
                if (renderer.getId().equals(Renderer.ID_GL4ES)) {
                    envMap.put("POJAV_RENDERER", "opengles2");
                } else {
                    envMap.put("POJAV_RENDERER", "opengles2_vgpu");
                }
            }
        } else if (renderer.isEqual(Renderer.ID_GL4ESPLUS)) {
            envMap.put("LIBGL_ES", "3");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NORMALIZE", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_SHADERCONVERTER", "1");
            envMap.put("LIBGL_GL", "21");
            envMap.put("LIBGL_USEVBO", "1");
            if (!FCLBridge.BACKEND_IS_BOAT) {
                envMap.put("POJAV_RENDERER", "opengles3");
                envMap.put("POJAVEXEC_EGL", useAngle ? "libEGL_angle.so" : renderer.getEglName());
            }
        } else {
            envMap.put("MESA_GLSL_CACHE_DIR", config.getContext().getCacheDir().getAbsolutePath());
            envMap.put("MESA_GL_VERSION_OVERRIDE", renderer.isEqual(Renderer.ID_VIRGL) ? "4.3" : "4.6");
            envMap.put("MESA_GLSL_VERSION_OVERRIDE", renderer.isEqual(Renderer.ID_VIRGL) ? "430" : "460");
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("VTEST_SOCKET_NAME", new File(config.getContext().getCacheDir().getAbsolutePath(), ".virgl_test").getAbsolutePath());
            if (renderer.isEqual(Renderer.ID_VIRGL)) {
                if (FCLBridge.BACKEND_IS_BOAT) {
                    envMap.put("GALLIUM_DRIVER", "virpipe");
                } else {
                    envMap.put("POJAV_RENDERER", "gallium_virgl");
                }
                envMap.put("OSMESA_NO_FLUSH_FRONTBUFFER", "1");
            } else if (renderer.isEqual(Renderer.ID_ZINK)) {
                if (FCLBridge.BACKEND_IS_BOAT) {
                    envMap.put("GALLIUM_DRIVER", "zink");
                } else {
                    envMap.put("POJAV_RENDERER", "vulkan_zink");
                }
            } else if (renderer.isEqual(Renderer.ID_FREEDRENO)) {
                if (FCLBridge.BACKEND_IS_BOAT) {
                    envMap.put("GALLIUM_DRIVER", "freedreno");
                    envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
                } else {
                    envMap.put("POJAV_RENDERER", "gallium_freedreno");
                }
            }
        }
    }

    private static void setEnv(FCLConfig config, FCLBridge bridge, boolean render) {
        HashMap<String, String> envMap = new HashMap<>();
        addCommonEnv(config, envMap);
        addModLoaderEnv(config, envMap);
        if (render) {
            addRendererEnv(config, envMap);
        }
        printTaskTitle(bridge, "Env Map");
        for (String key : envMap.keySet()) {
            log(bridge, "Env: " + key + "=" + envMap.get(key));
            bridge.setenv(key, envMap.get(key));
        }
        printTaskTitle(bridge, "Env Map");
    }

    private static void setUpJavaRuntime(FCLConfig config, FCLBridge bridge) throws IOException {
        String jreLibDir = config.getJavaPath() + getJreLibDir(config.getJavaPath());
        String jliLibDir = new File(jreLibDir + "/jli/libjli.so").exists() ? jreLibDir + "/jli" : jreLibDir;
        String jvmLibDir = jreLibDir + getJvmLibDir(config.getJavaPath());
        // dlopen jre
        bridge.dlopen(jliLibDir + "/libjli.so");
        bridge.dlopen(jvmLibDir + "/libjvm.so");
        bridge.dlopen(jreLibDir + "/libfreetype.so");
        bridge.dlopen(jreLibDir + "/libverify.so");
        bridge.dlopen(jreLibDir + "/libjava.so");
        bridge.dlopen(jreLibDir + "/libnet.so");
        bridge.dlopen(jreLibDir + "/libnio.so");
        bridge.dlopen(jreLibDir + "/libawt.so");
        bridge.dlopen(jreLibDir + "/libawt_headless.so");
        bridge.dlopen(jreLibDir + "/libfontmanager.so");
        for (File file : locateLibs(new File(config.getJavaPath()))) {
            bridge.dlopen(file.getAbsolutePath());
        }
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                } else if (f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    private static void setupGraphicAndSoundEngine(FCLConfig config, FCLBridge bridge) {
        String nativeDir = config.getContext().getApplicationInfo().nativeLibraryDir;

        bridge.dlopen(nativeDir + "/libopenal.so");
        if (!config.getRenderer().getPath().isEmpty()) {
            List<String> envList;
            if (FCLBridge.BACKEND_IS_BOAT) {
                envList = config.getRenderer().getBoatEnv();
            } else {
                envList = config.getRenderer().getPojavEnv();
            }
            if (envList != null) {
                envList.forEach(env -> {
                    String[] split = env.split("=");
                    if (split[0].equals("DLOPEN")) {
                        String[] libs = split[1].split(",");
                        for (String lib : libs) {
                            bridge.dlopen(config.getRenderer().getPath() + "/" + lib);
                        }
                    }
                });
            }
            long handle = bridge.dlopen(config.getRenderer().getGLPath());
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                try {
                    Os.setenv("RENDERER_HANDLE", handle + "", true);
                } catch (ErrnoException ignore) {
                }
            }
//            bridge.dlopen(RendererPlugin.getSelected().getPath() + "/" + RendererPlugin.getSelected().getEglName());
        } else {
            bridge.dlopen(nativeDir + "/" + config.getRenderer().getGlName());
        }
    }

    private static void launch(FCLConfig config, FCLBridge bridge, String task) throws IOException {
        printTaskTitle(bridge, task + " Arguments");
        String[] args = rebaseArgs(config);
        boolean javaArgs = true;
        int mainClass = 0;
        boolean isToken = false;
        for (String arg : args) {
            if (javaArgs)
                javaArgs = !arg.equals("mio.Wrapper");
            String title = task.equals("Minecraft") ? javaArgs ? "Java" : task : task;
            String prefix = title + " argument: ";
            if (task.equals("Minecraft") && !javaArgs && mainClass < 2) {
                mainClass++;
                prefix = "MainClass: ";
            }
            if (isToken) {
                isToken = false;
                log(bridge, prefix + "***");
                continue;
            }
            if (arg.equals("--accessToken"))
                isToken = true;
            log(bridge, prefix + arg);
        }
        bridge.setLdLibraryPath(getLibraryPath(config.getContext(), config.getJavaPath(), config.getRenderer().getPath()));
        bridge.setupExitTrap(bridge);
        log(bridge, "Hook success");
        int exitCode = VMLauncher.launchJVM(args);
        bridge.onExit(exitCode);
    }

    public static FCLBridge launchMinecraft(FCLConfig config) {

        // initialize FCLBridge
        FCLBridge bridge = new FCLBridge();
        bridge.setLogPath(config.getLogDir() + "/latest_game.log");
        Thread gameThread = new Thread(() -> {
            try {
                logStartInfo(config, bridge, "Minecraft");
                logModList(bridge);

                // env
                setEnv(config, bridge, true);

                // setup java runtime
                setUpJavaRuntime(config, bridge);

                // setup graphic and sound engine
                setupGraphicAndSoundEngine(config, bridge);

                // set working directory
                log(bridge, "Working directory: " + config.getWorkingDir());
                bridge.chdir(config.getWorkingDir());

                // launch game
                launch(config, bridge, "Minecraft");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        gameThread.setPriority(Thread.MAX_PRIORITY);
        bridge.setThread(gameThread);

        return bridge;
    }

    public static FCLBridge launchJarExecutor(FCLConfig config) {

        // initialize FCLBridge
        FCLBridge bridge = new FCLBridge();
        bridge.setLogPath(config.getLogDir() + "/latest_jar_executor.log");
        Thread javaGUIThread = new Thread(() -> {
            try {

                logStartInfo(config, bridge, "Jar Executor");

                // env
                setEnv(config, bridge, true);

                // setup java runtime
                setUpJavaRuntime(config, bridge);

                // setup graphic and sound engine
                setupGraphicAndSoundEngine(config, bridge);

                // set working directory
                log(bridge, "Working directory: " + config.getWorkingDir());
                bridge.chdir(config.getWorkingDir());

                // launch jar executor
                launch(config, bridge, "Jar Executor");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        bridge.setThread(javaGUIThread);

        return bridge;
    }

    public static FCLBridge launchAPIInstaller(FCLConfig config) {

        // initialize FCLBridge
        FCLBridge bridge = new FCLBridge();
        bridge.setLogPath(config.getLogDir() + "/latest_api_installer.log");
        Thread apiInstallerThread = new Thread(() -> {
            try {

                logStartInfo(config, bridge, "API Installer");

                // env
                setEnv(config, bridge, false);

                // setup java runtime
                setUpJavaRuntime(config, bridge);

                // set working directory
                log(bridge, "Working directory: " + config.getWorkingDir());
                bridge.chdir(config.getWorkingDir());

                // launch api installer
                launch(config, bridge, "API Installer");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        bridge.setThread(apiInstallerThread);

        return bridge;
    }

    public static String getSocName() {
        String name = null;
        try {
            Process process = Runtime.getRuntime().exec("getprop ro.soc.model");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            name = reader.readLine();
            reader.close();
        } catch (Exception ignore) {
        }
        return (name == null || name.trim().isEmpty()) ? Build.HARDWARE : name;
    }

    public static String getDeviceName() {
        String modelName = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;
        String product = Build.PRODUCT;
        if (!manufacturer.isEmpty()) {
            manufacturer = Character.toUpperCase(manufacturer.charAt(0))
                    + manufacturer.substring(1).toLowerCase();
        }
        return String.format("%s %s %s", manufacturer, product, modelName);
    }

}
