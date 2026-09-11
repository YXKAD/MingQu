package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 视频自动最高画质 v2（信息流短视频）。
 *
 * 基于 jadx 反编译（v39.8.0）定位：
 * - 全局手动分辨率状态：com.ss.android.ugc.aweme.o.oplayer.util.OVideoResolutionUtils
 *   （静态字段 LIZLLL 保存当前选择；LIZ() 读取，新视频默认画质来源，如 MediaPrerenderComponent）
 * - 手动档位模型 com.ss.android.ugc.aweme.player.sdk.model.ManualResolution：
 *   Manual_Low(360p) → ... → Manual_Extremely_High(2160p) → Manual_Extremely_High_HDR
 * - 手动选择入口（画质面板点选）：com.ss.android.ugc.aweme.feed.plato.business.
 *   contentconsumption.resolution.FeedManualResolutionComponent.LJJLIIJ(ManualResolution,ManualResolution)
 * - 画质应用控制器：X.C1130890fRh.LIZLLL(ManualResolution, boolean)
 *   （按当前会话支持性应用画质；可从 this.d.getPlayer().getSupportedManualResolutions() 拿支持列表）
 *
 * 行为（用户确认的语义）：
 * - 进程冷启动（重启抖音精选）→ 武装：默认画质强制最高档，且每个视频按自身支持列表钳制到最高（向下兼容）；
 * - 会话中用户手动切换 → 解除武装，尊重用户选择、不重置；
 * - 下次冷启动（后台清理后重启）→ 再次武装、强制最高。
 */
public class VideoMaxQuality implements FeatureEntry.FeatureHook {

    /** 进程级武装：冷启动为 true；手动切换后解除，直到进程重启 */
    private static volatile boolean armed = true;

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：视频自动最高画质(v2)");

        // 1) 默认画质读取器：武装期间返回最高档（每个新视频的默认画质来源）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.android.ugc.aweme.o.oplayer.util.OVideoResolutionUtils", cl,
                    "LIZ",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!armed) {
                                return;
                            }
                            Object max = maxManualResolution(cl);
                            if (max != null) {
                                param.setResult(max);
                                XposedBridge.log("[明渠] 视频默认画质强制最高: " + max);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 视频Hook1失败(默认画质): " + t);
        }

        // 2) 画质面板"用户点击"回调：解除武装，尊重用户选择。
        //    注意：FeedManualResolutionComponent.LJJLIIJ 是内部应用方法（每个视频都会调），
        //    不能在那里解除；真正的用户点击在 lambda kotlin.jvm.internal.ALambdaS312S0300000_22.invoke$8
        try {
            XposedHelpers.findAndHookMethod(
                    "kotlin.jvm.internal.ALambdaS312S0300000_22", cl,
                    "invoke$8",
                    "kotlin.jvm.internal.ALambdaS312S0300000_22", Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (armed) {
                                armed = false;
                                Object tapped = param.args.length > 1 ? param.args[1] : null;
                                XposedBridge.log("[明渠] 检测到手动切画质(点击面板)，本次会话不再强制: " + tapped);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 视频Hook2失败(手动点击): " + t);
        }

        // 3) 画质应用控制器（真实类名 X.0fRh）：武装期间按每个视频的支持列表钳制到最高（向下兼容）
        try {
            XposedHelpers.findAndHookMethod(
                    "X.0fRh", cl,
                    "LIZLLL",
                    "com.ss.android.ugc.aweme.player.sdk.model.ManualResolution",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!armed) {
                                return;
                            }
                            try {
                                Object best = highestSupported(param.thisObject, cl);
                                if (best != null && !best.equals(param.args[0])) {
                                    Object orig = param.args[0];
                                    param.args[0] = best;
                                    XposedBridge.log("[明渠] 视频画质按支持列表钳制最高: " + orig + " -> " + best);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 视频Hook3异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 视频Hook3失败(应用控制器): " + t);
        }
    }

    /** 取 ManualResolution 最高档常量（2160p，非 HDR，避免非 HDR 视频异常） */
    private static Object maxManualResolution(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.player.sdk.model.ManualResolution", cl);
            for (Object o : c.getEnumConstants()) {
                if ("Manual_Extremely_High".equals(((Enum) o).name())) {
                    return o;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从当前视频支持列表里取最高档（thisObject = C1130890fRh 控制器） */
    private static Object highestSupported(Object controller, ClassLoader cl) {
        try {
            Object d = XposedHelpers.getObjectField(controller, "d");
            if (d == null) {
                return null;
            }
            Object player = XposedHelpers.callMethod(d, "getPlayer");
            if (player == null) {
                return null;
            }
            Object[] supported = (Object[]) XposedHelpers.callMethod(
                    player, "getSupportedManualResolutions");
            if (supported == null || supported.length == 0) {
                return null;
            }
            Object best = null;
            int bestInt = -1;
            for (Object o : supported) {
                if (o instanceof Enum) {
                    String n = ((Enum) o).name();
                    if ("Manual_Auto".equals(n) || "Manual_Undefine".equals(n)) {
                        continue;
                    }
                }
                int res = (Integer) XposedHelpers.callMethod(o, "LJ");
                if (res > bestInt) {
                    bestInt = res;
                    best = o;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
