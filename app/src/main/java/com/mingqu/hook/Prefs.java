package com.mingqu.hook;

import android.content.Context;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 配置读写工具。
 *
 * 这个模块的界面（模块自己进程）和 Hook（运行在目标应用进程里）是两个不同进程，
 * 它们共用同一份开关配置：
 *  - 界面侧：直接读写自己 App 的 SharedPreferences
 *  - Hook 侧：用 XSharedPreferences 跨进程读取界面保存的配置（LSPosed 官方支持）
 *
 * 每个开关的 key 格式统一为 "包名_功能key"。
 */
public class Prefs {

    public static final String PREFS_NAME = "hook_config";
    public static final String MODULE_PKG = "com.mingqu.hook";

    // ================= 模块界面进程使用 =================

    public static boolean read(Context ctx, String pkg, String key, boolean def) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key(pkg, key), def);
    }

    public static void write(Context ctx, String pkg, String key, boolean value) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key(pkg, key), value).apply();
    }

    // ================= Hook 进程使用（运行在目标应用里）=================

    public static boolean readInHook(String pkg, String key, boolean def) {
        try {
            XSharedPreferences xsp = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
            xsp.reload(); // 重新从磁盘读取，确保拿到最新配置
            return xsp.getBoolean(key(pkg, key), def);
        } catch (Throwable t) {
            // 读取失败时使用默认值，不影响目标应用
            return def;
        }
    }

    private static String key(String pkg, String key) {
        return pkg + "_" + key;
    }
}
