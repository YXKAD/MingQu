package com.mingqu.hook;

import android.app.Dialog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 屏蔽"发现新版本"更新弹窗。
 *
 * v0.1 通用拦截：弹窗类名里带 update/version/upgrade 等字样的一律拦掉。
 * 更精准的做法等 jadx 反编译后填写（搜索"发现新版本""立即更新""稍后再说"）。
 */
public class BlockUpdate implements FeatureEntry.FeatureHook {

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：屏蔽新版本更新");

        // 通用拦截 1：DialogFragment 弹窗
        try {
            XposedHelpers.findAndHookMethod("androidx.fragment.app.DialogFragment", cl, "show",
                    "androidx.fragment.app.FragmentManager", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object thiz = param.thisObject;
                            if (thiz != null && isUpdateRelated(thiz.getClass().getName())) {
                                param.setResult(null);
                                XposedBridge.log("[明渠] 已拦截更新弹窗(DialogFragment): "
                                        + thiz.getClass().getName());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 更新拦截1失败: " + t);
        }

        // 通用拦截 2：普通 Dialog 弹窗
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object thiz = param.thisObject;
                            if (thiz != null && isUpdateRelated(thiz.getClass().getName())) {
                                param.setResult(null);
                                XposedBridge.log("[明渠] 已拦截更新弹窗(Dialog): "
                                        + thiz.getClass().getName());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 更新拦截2失败: " + t);
        }

        // TODO：jadx 分析后填写精确 hook（找到"发现新版本"弹窗的真实类/方法）
    }

    private static boolean isUpdateRelated(String className) {
        String s = className.toLowerCase();
        return s.contains("update") || s.contains("version")
                || s.contains("upgrade") || s.contains("checkupdate")
                || s.contains("appupdate") || s.contains("newversion");
    }
}
