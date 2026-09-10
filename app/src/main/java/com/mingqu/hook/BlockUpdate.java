package com.mingqu.hook;

import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 屏蔽"发现新版本"更新弹窗。
 *
 * 基于 jadx 反编译（v39.8.0）定位：
 * - com.ss.android.ugc.aweme.update.UpdateHelper.LJIJI(Context,String,String,String)
 *   方法内部构建并展示"发现新版本"弹窗（资源 0x7f118336），直接拦截整个方法。
 * - com.ss.android.ugc.aweme.update.UpdateActivity 为更新页 Activity，拦截其 onCreate。
 */
public class BlockUpdate implements FeatureEntry.FeatureHook {

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：屏蔽新版本更新");

        // 1) 拦截"发现新版本"更新弹窗
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.android.ugc.aweme.update.UpdateHelper", cl,
                    "LJIJI", Context.class, String.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                            XposedBridge.log("[明渠] 已拦截更新弹窗 UpdateHelper.LJIJI");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 更新Hook1失败: " + t);
        }

        // 2) 拦截更新页 Activity（点击更新通知等入口）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.android.ugc.aweme.update.UpdateActivity", cl,
                    "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                XposedHelpers.callMethod(param.thisObject, "finish");
                                XposedBridge.log("[明渠] 已拦截更新页 UpdateActivity");
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 更新页拦截异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 更新Hook2失败: " + t);
        }
    }
}
