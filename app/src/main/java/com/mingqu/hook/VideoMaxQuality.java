package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 视频自动切换最高画质（信息流短视频）。
 *
 * 基于 jadx 反编译（v39.8.0）定位：
 * - com.ss.android.ugc.aweme.feed.controller.FeedPlayerWrapper.LIZ(IResolution)
 *   是信息流播放器设置分辨率的入口（内部委托给底层播放器）；
 * - 分辨率档位 com.ss.android.ugc.aweme.player.sdk.model.IResolution：
 *   360p / 480p / 720p / 1080p / 2k / 4k(2160p) / HDR 等；
 * - FeedPlayerWrapper.getSupportedResolutions() 返回当前视频支持的分辨率列表。
 *
 * 策略：设置分辨率时，从当前视频的支持列表里选"分辨率数值最高"的一档强制替换，
 * 保证信息流视频始终以最高画质播放。
 */
public class VideoMaxQuality implements FeatureEntry.FeatureHook {

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：视频自动最高画质");

        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.android.ugc.aweme.feed.controller.FeedPlayerWrapper", cl,
                    "LIZ", "com.ss.android.ugc.aweme.player.sdk.model.IResolution",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object wrapper = param.thisObject;
                                Object[] supported = (Object[]) XposedHelpers.callMethod(
                                        wrapper, "getSupportedResolutions");
                                Object best = pickHighest(supported);
                                if (best != null && !best.equals(param.args[0])) {
                                    Object orig = param.args[0];
                                    param.args[0] = best;
                                    XposedBridge.log("[明渠] 视频画质强制最高: " + orig + " -> " + best);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 视频画质Hook异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 视频画质Hook失败: " + t);
        }
    }

    /** 从支持列表里选分辨率最高的档位（跳过 Undefine / Auto） */
    private static Object pickHighest(Object[] supported) {
        if (supported == null) {
            return null;
        }
        Object best = null;
        int bestInt = -1;
        for (Object o : supported) {
            try {
                if (o instanceof Enum) {
                    String n = ((Enum) o).name();
                    if ("Undefine".equals(n) || "Auto".equals(n)) {
                        continue;
                    }
                }
                int res = (Integer) XposedHelpers.callMethod(o, "LIZIZ");
                if (res > bestInt) {
                    bestInt = res;
                    best = o;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }
}
