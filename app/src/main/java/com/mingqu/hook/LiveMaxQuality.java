package com.mingqu.hook;

import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 进入直播间自动切换为最高画质。
 *
 * 基于 jadx 反编译（v39.8.0）定位：
 * - 清晰度档位模型 com.bytedance.android.livesdkapi.player.resolution.PlayerResolution：
 *   标清 ld(1) / 高清 sd(2) / 超清 hd(3) / 蓝光 uhd(4) / 原画 origin(5) / 蓝光帧彩 xuhd(7) / 自动 auto(99)
 * - 画质切换入口 com.bytedance.android.livesdk.player.LivePlayerClient.switchResolution(String)
 *   （直播 UI 与内部恢复都经过它，单参版本委托三参版本）
 * - 播放器启动画质 com.ss.videoarch.liveplayer2.VeLivePlayer.setStartPlayResolution(VeLivePlayerResolution)
 *
 * 策略：把目标画质强制为最高档 xuhd；若当前直播间不支持，用 isSupportResolutionSwitch
 * 逐档回退 origin -> uhd -> hd，保证不选到不存在的档位。
 */
public class LiveMaxQuality implements FeatureEntry.FeatureHook {

    /** 从高到低的候选档位 */
    private static final String[] CANDIDATES = {"xuhd", "origin", "uhd", "hd"};

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：直播自动最高画质");

        // 1) 单参 switchResolution：直播 UI / 内部恢复的入口
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.livesdk.player.LivePlayerClient", cl,
                    "switchResolution", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            forceHighest(param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 画质Hook1失败: " + t);
        }

        // 2) 三参 switchResolution：核心实现（单参版本会委托进来）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.livesdk.player.LivePlayerClient", cl,
                    "switchResolution", String.class, String.class, Map.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            forceHighest(param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 画质Hook2失败: " + t);
        }

        // 3) 播放器启动画质：进房开始播放时生效
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.videoarch.liveplayer2.VeLivePlayer", cl,
                    "setStartPlayResolution",
                    "com.ss.videoarch.liveplayer2.VeLivePlayerResolution",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String best = pickSupportedOnPlayer(param.thisObject, cl);
                                if (best != null) {
                                    Object res = XposedHelpers.newInstance(
                                            XposedHelpers.findClass(
                                                    "com.ss.videoarch.liveplayer2.VeLivePlayerResolution", cl),
                                            best);
                                    param.args[0] = res;
                                    XposedBridge.log("[明渠] 启动画质强制最高: " + best);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 画质Hook3异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 画质Hook3失败: " + t);
        }
    }

    /** 在 LivePlayerClient 上把切画质参数强制为当前直播间支持的最高档 */
    private static void forceHighest(XC_MethodHook.MethodHookParam param) {
        try {
            Object client = param.thisObject;
            String original = (String) param.args[0];
            if (client == null || original == null) return;
            String best = pickSupportedOnClient(client);
            if (best != null && !best.equals(original)) {
                param.args[0] = best;
                XposedBridge.log("[明渠] 画质强制最高: " + original + " -> " + best);
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 画质强制异常: " + t);
        }
    }

    /** 用 isSupportResolutionSwitch 逐档探测客户端支持的最高档 */
    private static String pickSupportedOnClient(Object client) {
        for (String cand : CANDIDATES) {
            try {
                Object r = XposedHelpers.callMethod(client, "isSupportResolutionSwitch", cand);
                if (Boolean.TRUE.equals(r)) {
                    return cand;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 播放器实例上的探测（参数类型为 VeLivePlayerResolution） */
    private static String pickSupportedOnPlayer(Object player, ClassLoader cl) {
        for (String cand : CANDIDATES) {
            try {
                Object res = XposedHelpers.newInstance(
                        XposedHelpers.findClass(
                                "com.ss.videoarch.liveplayer2.VeLivePlayerResolution", cl),
                        cand);
                Object r = XposedHelpers.callMethod(player, "isSupportResolutionSwitch", res);
                if (Boolean.TRUE.equals(r)) {
                    return cand;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
