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

    /** 动态 hook 过一次 IPlayer 实现类（防重复） */
    private static volatile boolean playerProbeDone = false;

    /** 动态 hook：getSupportedManualResolutions（player 真实实现类） */
    private static void probePlayerSupport(Object player, ClassLoader cl) {
        if (playerProbeDone) {
            return;
        }
        try {
            Class<?> pc = player.getClass();
            playerProbeDone = true;
            XposedBridge.log("[明渠] 播放器实现类: " + pc.getName());
            for (java.lang.reflect.Method m : pc.getDeclaredMethods()) {
                if ("getSupportedManualResolutions".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 强开：返回全手动档位（含 1440p/2160p），放回被机型过滤砍掉的 2K/4K
                                Object[] full = allManualResolutions(cl);
                                param.setResult(full);
                                StringBuilder sb = new StringBuilder("[明渠] 视频支持列表强开全档: ");
                                for (Object o : full) {
                                    sb.append(((Enum) o).name()).append(" ");
                                }
                                XposedBridge.log(sb.toString());
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 支持列表强开异常: " + t);
                            }
                        }
                    });
                    XposedBridge.log("[明渠] 已动态hook支持列表方法(强开): " + m);
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 动态探针失败: " + t);
        }
    }

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
                            // 探针：无论是否武装，都尝试动态 hook 支持列表实现类
                            try {
                                Object d = XposedHelpers.getObjectField(param.thisObject, "d");
                                if (d != null) {
                                    Object player = XposedHelpers.callMethod(d, "getPlayer");
                                    if (player != null) {
                                        probePlayerSupport(player, cl);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
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

        // 4) 探针：getSupportedManualResolutions 独立 Hook（排查关闭机型伪装后丢 2K/4K）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.android.ugc.aweme.player.sdk.api.IPlayer", cl,
                    "getSupportedManualResolutions",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object[] arr = (Object[]) param.getResult();
                                StringBuilder sb = new StringBuilder("[明渠] 视频支持列表(独立): ");
                                if (arr != null) {
                                    for (Object o : arr) {
                                        if (o instanceof Enum) {
                                            sb.append(((Enum) o).name()).append(" ");
                                        }
                                    }
                                }
                                XposedBridge.log(sb.toString());
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 支持列表探针异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 支持列表探针失败: " + t);
        }

        // 5) 探针：视频画质弹窗打开（方法枚举，兼容签名；找面板档位列表构建链）
        try {
            Class<?> fmc = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.resolution.FeedManualResolutionComponent",
                    cl);
            XC_MethodHook popupHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object notice = param.args.length > 0 ? param.args[0] : null;
                    XposedBridge.log("[明渠] 视频画质弹窗打开(" + param.method.getName() + "): "
                            + (notice == null ? "null" : notice.getClass().getName())
                            + "\n" + android.util.Log.getStackTraceString(new Throwable()));
                    if (notice != null) {
                        try {
                            for (java.lang.reflect.Field f : notice.getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                Object v = f.get(notice);
                                if (v != null && (v instanceof java.util.List
                                        || v.getClass().getName().contains("Player")
                                        || v.getClass().getName().contains("Resolution")
                                        || v.getClass().getName().contains("Manual")
                                        || v.getClass().getName().contains("View"))) {
                                    XposedBridge.log("[明渠] 弹窗字段 " + f.getName() + " (" + v.getClass().getSimpleName() + "): " + v);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[明渠] 弹窗字段遍历异常: " + t);
                        }
                    }
                }
            };
            int n = 0;
            for (java.lang.reflect.Method m : fmc.getDeclaredMethods()) {
                String mn = m.getName();
                if ("beforeShow".equals(mn) || "onShow".equals(mn)) {
                    XposedBridge.hookMethod(m, popupHook);
                    n++;
                }
            }
            XposedBridge.log("[明渠] 视频弹窗探针挂载数: " + n);
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 视频弹窗探针失败: " + t);
        }

        // 6) 探针：ManualResolution.toString/LJ（面板构建时显示档位名会调）→ 打印调用栈定位面板构建链
        try {
            Class<?> mrCls = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.player.sdk.model.ManualResolution", cl);
            final int[] cnt = {0};
            XC_MethodHook mrHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (cnt[0] < 6) {
                        cnt[0]++;
                        XposedBridge.log("[明渠] ManualRes " + param.method.getName() + " -> " + param.getResult()
                                + "\n" + android.util.Log.getStackTraceString(new Throwable()));
                    }
                }
            };
            int mn2 = 0;
            for (java.lang.reflect.Method m : mrCls.getDeclaredMethods()) {
                if ("toString".equals(m.getName()) || "LJ".equals(m.getName())) {
                    XposedBridge.hookMethod(m, mrHook);
                    mn2++;
                }
            }
            XposedBridge.log("[明渠] ManualRes探针挂载数: " + mn2);
        } catch (Throwable t) {
            XposedBridge.log("[明渠] ManualRes探针失败: " + t);
        }

        // 7) 探针：FeedManualResolutionComponent 全方法（抓视频画质面板打开/构建的真实调用链）
        try {
            Class<?> fmc2 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.resolution.FeedManualResolutionComponent",
                    cl);
            final int[] cnt2 = {0};
            XC_MethodHook fmcHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (cnt2[0] < 40) {
                        cnt2[0]++;
                        StringBuilder sb = new StringBuilder("[明渠] FMC调用: " + param.method.getName() + "(");
                        if (param.args != null) {
                            for (Object a : param.args) {
                                sb.append(String.valueOf(a)).append(", ");
                            }
                        }
                        sb.append(")");
                        XposedBridge.log(sb.toString());
                        XposedBridge.log("[明渠] FMC栈: " + android.util.Log.getStackTraceString(new Throwable()));
                    }
                }
            };
            int mn3 = 0;
            for (java.lang.reflect.Method m : fmc2.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, fmcHook);
                mn3++;
            }
            XposedBridge.log("[明渠] FMC全方法探针挂载数: " + mn3);
        } catch (Throwable t) {
            XposedBridge.log("[明渠] FMC探针失败: " + t);
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

    /** 全手动档位（排除 Auto/Undefine）——用于强开 2K/4K */
    private static Object[] allManualResolutions(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.player.sdk.model.ManualResolution", cl);
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : c.getEnumConstants()) {
                String n = ((Enum) o).name();
                // 排除 Auto/Undefine/HDR（普通视频无 HDR 源，强制会崩溃）
                if (!"Manual_Auto".equals(n) && !"Manual_Undefine".equals(n) && !n.endsWith("_HDR")) {
                    out.add(o);
                }
            }
            // 返回正确类型的数组（ManualResolution[]，否则 Xposed 类型检查崩溃）
            Object typed = java.lang.reflect.Array.newInstance(c, out.size());
            return out.toArray((Object[]) typed);
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 取全档位失败: " + t);
            return null;
        }
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
            probePlayerSupport(player, cl);
            Object[] supported = (Object[]) XposedHelpers.callMethod(
                    player, "getSupportedManualResolutions");
            if (supported == null || supported.length == 0) {
                return null;
            }
            // 探针：打印当前视频实际支持列表（排查机型伪装关闭后丢 2K/4K）
            StringBuilder sb = new StringBuilder("[明渠] 视频支持列表: ");
            for (Object o : supported) {
                if (o instanceof Enum) {
                    sb.append(((Enum) o).name()).append(" ");
                }
            }
            XposedBridge.log(sb.toString());
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
