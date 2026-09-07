package com.mingqu.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 *
 * 它只做一件事：根据当前被启动的应用包名，去 FeatureRegistry 里找对应的应用定义，
 * 然后逐个启用其中"开关已打开"的功能（每个功能独立 try/catch，互不影响）。
 *
 * 新加应用不需要改这个文件，只需要改 FeatureRegistry。
 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        AppEntry app = FeatureRegistry.findApp(lpparam.packageName);
        if (app == null) {
            return; // 不是本模块适配的应用，不处理
        }

        XposedBridge.log("[HookTool] 命中应用: " + app.packageName
                + " | 进程: " + lpparam.processName);

        ClassLoader cl = lpparam.classLoader;
        for (FeatureEntry feature : app.features) {
            boolean on = Prefs.readInHook(app.packageName, feature.key, feature.defaultOn);
            if (!on) {
                XposedBridge.log("[HookTool] 已关闭，跳过: " + feature.key);
                continue;
            }
            try {
                feature.hook.apply(cl);
                XposedBridge.log("[HookTool] 已启用: " + feature.key);
            } catch (Throwable t) {
                // 独立容错：单个功能失败只影响它自己，绝不拖垮目标应用
                XposedBridge.log("[HookTool] 功能加载失败 " + feature.key + " : " + t);
            }
        }
    }
}
