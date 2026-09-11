package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
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

    /** 当前视频最高支持档显示名（由 filterAuto 缓存，供"智能"档改名用） */
    private static volatile String lastHighestName = null;

    /** 当前实际播放分辨率显示名（由 getCurrentResolution 探针缓存，供"智能"档改名用） */
    private static volatile String lastCurrentResName = null;

    /** 最近一次拿到的播放器实例（反射兜底取当前分辨率） */
    private static volatile Object lastPlayer = null;

    /** 当前视频档位"宽×高 fps"队列（由 SimBitRate hook 按构建顺序记录，setItems 时消费） */
    private static final java.util.List<String> bitRateQueue = new java.util.ArrayList<>();

    /** 队列脏标记：setItems 消费后置 true，下一轮 SimBitRate 构建时重建 */
    private static volatile boolean bitRateQueueDirty = true;

    /** 动态 hook 过一次 IPlayer 实现类（防重复） */
    private static volatile boolean playerProbeDone = false;

    /** 过滤 Manual_Auto/Undefine（面板"智能"档）——设备能力列表用：只过滤，不缓存显示值 */
    private static void filterCapability(MethodHookParam param) {
        try {
            Object[] full = (Object[]) param.getResult();
            java.util.List<Object> kept = new java.util.ArrayList<>();
            if (full != null) {
                for (Object o : full) {
                    if (o instanceof Enum) {
                        String n = ((Enum) o).name();
                        if ("Manual_Auto".equals(n) || "Manual_Undefine".equals(n)) {
                            continue;
                        }
                    }
                    kept.add(o);
                }
            }
            if (kept.size() != (full == null ? 0 : full.length)) {
                Object typed = java.lang.reflect.Array.newInstance(
                        full.getClass().getComponentType(), kept.size());
                param.setResult(kept.toArray((Object[]) typed));
                XposedBridge.log("[明渠] 设备能力列表已过滤智能档(Auto): " + full.length + " -> " + kept.size());
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 设备能力过滤异常: " + t);
        }
    }

    /** 过滤 Manual_Auto/Undefine（面板"智能"档），after 阶段调用（当前视频源：缓存最高显示名） */
    private static void filterAuto(MethodHookParam param) {
        try {
            lastPlayer = param.thisObject; // 缓存播放器实例（反射兜底取当前分辨率）
        } catch (Throwable ignored) {
        }
        try {
            Object[] full = (Object[]) param.getResult();
            StringBuilder sb = new StringBuilder("[明渠] 视频支持列表(设备能力): ");
            java.util.List<Object> kept = new java.util.ArrayList<>();
            if (full != null) {
                for (Object o : full) {
                    if (o instanceof Enum) {
                        String n = ((Enum) o).name();
                        sb.append(n).append(" ");
                        if ("Manual_Auto".equals(n) || "Manual_Undefine".equals(n)) {
                            continue; // 丢掉智能/未定义
                        }
                    }
                    kept.add(o);
                }
            }
            XposedBridge.log(sb.toString());
            // 缓存当前视频最高支持档显示名（供"智能"档改名）
            String highest = null;
            int bestRes = -1;
            if (full != null) {
                for (Object o : full) {
                    if (o instanceof Enum) {
                        String n = ((Enum) o).name();
                        if ("Manual_Auto".equals(n) || "Manual_Undefine".equals(n)) {
                            continue;
                        }
                        int res = -1;
                        try {
                            res = (Integer) XposedHelpers.callMethod(o, "LJ");
                        } catch (Throwable ignored2) {
                        }
                        if (res > bestRes) {
                            bestRes = res;
                            highest = resolutionName(n);
                        }
                    }
                }
            }
            lastHighestName = highest;
            if (kept.size() != (full == null ? 0 : full.length)) {
                Object typed = java.lang.reflect.Array.newInstance(
                        full.getClass().getComponentType(), kept.size());
                param.setResult(kept.toArray((Object[]) typed));
                XposedBridge.log("[明渠] 已过滤智能档(Auto): " + full.length + " -> " + kept.size());
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 支持列表过滤异常: " + t);
        }
    }

    /** 分辨率高度 int -> 显示名（"智能"档背后的实际分辨率） */
    private static String resolutionIntToName(int ri) {
        if (ri >= 2160) {
            return "4K";
        }
        if (ri >= 1440) {
            return "2K";
        }
        if (ri >= 1080) {
            return "1080P";
        }
        if (ri >= 720) {
            return "720P";
        }
        if (ri >= 540) {
            return "540P";
        }
        if (ri >= 480) {
            return "480P";
        }
        if (ri > 0) {
            return ri + "P";
        }
        return "自动";
    }

    /** IResolution.toString() 显示名（如 "1080p 60fps"/"4k"/"auto"）-> 具体分辨率显示 */
    private static String resolutionDisplay(String r) {
        if (r == null) {
            return "自动";
        }
        String s = r.trim().toLowerCase();
        if (s.contains("auto")) {
            return "自动";
        }
        if (s.contains("undefine") || s.isEmpty()) {
            return "自动";
        }
        if (s.contains("hdr")) {
            return "HDR";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\\\d+)p").matcher(s);
        if (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 2160) {
                return "4K";
            }
            if (v >= 1440) {
                return "2K";
            }
            return v + "P";
        }
        if (s.contains("4k")) {
            return "4K";
        }
        if (s.contains("2k")) {
            return "2K";
        }
        if (s.contains("2160")) {
            return "4K";
        }
        if (s.contains("1440")) {
            return "2K";
        }
        return s.toUpperCase();
    }

    /** ManualResolution 枚举名 -> 显示名 */
    private static String resolutionName(String n) {
        if (n == null) {
            return "自动";
        }
        if (n.contains("Extremely_High")) {
            return "4K";
        }
        if (n.contains("Super_High")) {
            return "2K";
        }
        if (n.contains("High")) {
            return "1080P";
        }
        if (n.contains("Sub_High")) {
            return "720P";
        }
        if (n.contains("Standard")) {
            return "540P";
        }
        if (n.contains("SubStand")) {
            return "480P";
        }
        if (n.contains("Low")) {
            return "360P";
        }
        return "自动";
    }

    /** 动态 hook：getSupportedManualResolutions + getCurrentResolution（player 真实实现类） */
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
                        protected void afterHookedMethod(MethodHookParam param) {
                            filterAuto(param);
                        }
                    });
                    XposedBridge.log("[明渠] 已动态hook支持列表方法(强开): " + m);
                    break;
                }
            }
            // 探针：当前实际播放分辨率（"智能"档改名用）——getMethods 含继承实现
            boolean curHooked = false;
            for (java.lang.reflect.Method m : pc.getMethods()) {
                if ("getCurrentResolution".equals(m.getName()) && m.getParameterCount() == 0) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object res = param.getResult();
                                    if (res != null) {
                                        int ri = -1;
                                        try {
                                            ri = (Integer) XposedHelpers.getObjectField(res, "resolutionInt");
                                        } catch (Throwable t1) {
                                            try {
                                                ri = (Integer) XposedHelpers.callMethod(res, "LIZIZ");
                                            } catch (Throwable t2) {
                                            }
                                        }
                                        lastCurrentResName = resolutionIntToName(ri);
                                        XposedBridge.log("[明渠] 当前播放分辨率: " + lastCurrentResName + " (ri=" + ri + ")");
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[明渠] 当前分辨率探针异常: " + t);
                                }
                            }
                        });
                        curHooked = true;
                        XposedBridge.log("[明渠] 已动态hook当前分辨率: " + m);
                    } catch (Throwable th) {
                        XposedBridge.log("[明渠] hook当前分辨率失败: " + th);
                    }
                    break;
                }
            }
            if (!curHooked) {
                XposedBridge.log("[明渠] 未找到可hook的getCurrentResolution: " + pc.getName());
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
                                        lastPlayer = player;
        
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

    /** 处理画质面板：递归找选择面板(DuxSingleSelectionPanelView)隐藏/改名，外面标签一并改名 */
    private static void processPanel(final android.view.View decor) {
        try {
            decor.post(new Runnable() {
                @Override
                public void run() {
                    String cn = decor.getClass().getName();
                    // 刷新显示值：当前视频源最高档 + 当前播放分辨率
                    tryRefreshContext();
                    android.view.View sel = findSelectionPanel(decor);
                    if (sel != null) {
                        int concrete = countConcreteResolutions(sel);
                        if (concrete > 0) {
                            hideSmartText(sel);   // 面板内有具体档：隐藏"智能"整项
                        } else {
                            renameSmartText(sel); // 只有"智能"：面板内改为具体分辨率
                        }
                        renameSmartText(decor);   // 外面画质标签也改（面板内已隐藏的幂等）
                        return;
                    }
                    android.view.View title = findTitle(decor);
                    if (title == null) {
                        XposedBridge.log("[明渠] 弹窗非画质面板(无标题)，跳过: " + cn);
                        return;
                    }
                    // 标题向上取面板容器（3 层）
                    android.view.View computed = title;
                    for (int i = 0; i < 3 && computed.getParent() instanceof android.view.ViewGroup; i++) {
                        computed = (android.view.ViewGroup) computed.getParent();
                    }
                    final android.view.View root = computed;
                    int concrete = countConcreteResolutions(root);
                    if (concrete > 0) {
                        hideSmartText(root);
                        renameSmartText(decor);
                    } else {
                        renameSmartText(decor);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 画质面板处理异常: " + t);
        }
    }

    /** 递归找 DuxSingleSelectionPanelView（选择面板/档位列表本体） */
    private static android.view.View findSelectionPanel(android.view.View v) {
        try {
            if (v.getClass().getName().contains("DuxSingleSelectionPanelView")) {
                return v;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View r = findSelectionPanel(g.getChildAt(i));
                    if (r != null) {
                        return r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 诊断：打印视图树结构（深度限制） */
    private static void dumpTree(android.view.View v, int depth, int maxDepth) {
        if (v == null || depth > maxDepth) {
            return;
        }
        try {
            String txt = "";
            if (v instanceof android.widget.TextView) {
                txt = ((android.widget.TextView) v).getText().toString();
                if (txt.length() > 8) {
                    txt = txt.substring(0, 8);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(depth).append(":").append(v.getClass().getSimpleName()).append(" [").append(txt).append("]");
            XposedBridge.log("[明渠] " + sb.toString());
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && i < 12; i++) {
                    dumpTree(g.getChildAt(i), depth + 1, maxDepth);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 刷新显示上下文：当前视频源最高档（覆盖设备能力值）+ 当前播放分辨率 */
    private static void tryRefreshContext() {
        XposedBridge.log("[明渠] 刷新画质上下文: lastPlayer="
                + (lastPlayer == null ? "null" : lastPlayer.getClass().getSimpleName())
                + " cur=" + lastCurrentResName + " highest=" + lastHighestName);
        if (lastPlayer != null) {
            // 当前视频源支持列表（服务端按机型下发）——取最高档作为"智能"显示值
            try {
                Object[] sup = (Object[]) XposedHelpers.callMethod(
                        lastPlayer, "getSupportedManualResolutions");
                String highest = null;
                int best = -1;
                if (sup != null) {
                    for (Object o : sup) {
                        if (o instanceof Enum) {
                            String n = ((Enum) o).name();
                            if ("Manual_Auto".equals(n) || "Manual_Undefine".equals(n)) {
                                continue;
                            }
                        }
                        int res = -1;
                        try {
                            res = (Integer) XposedHelpers.callMethod(o, "LJ");
                        } catch (Throwable ignored) {
                        }
                        if (res > best) {
                            best = res;
                            if (o instanceof Enum) {
                                highest = resolutionName(((Enum) o).name());
                            }
                        }
                    }
                }
                if (highest != null) {
                    lastHighestName = highest;
                    XposedBridge.log("[明渠] 当前视频源最高档: " + highest);
                }
            } catch (Throwable t) {
                XposedBridge.log("[明渠] 取当前视频源最高档失败: " + t);
            }
            // 当前实际播放分辨率
            try {
                Object res = XposedHelpers.callMethod(lastPlayer, "getCurrentResolution");
                if (res != null) {
                    int ri = -1;
                    try {
                        ri = (Integer) XposedHelpers.getObjectField(res, "resolutionInt");
                    } catch (Throwable t1) {
                    }
                    lastCurrentResName = resolutionIntToName(ri);
                    XposedBridge.log("[明渠] 反射取当前分辨率: " + lastCurrentResName);
                }
            } catch (Throwable t) {
                XposedBridge.log("[明渠] 反射取分辨率失败: " + t);
            }
        }
    }

    /** 视图树里找"清晰度设置"标题 */
    private static android.view.View findTitle(android.view.View v) {
        try {
            if (v instanceof android.widget.TextView) {
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if (txt.contains("清晰度设置")) {
                    return v;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View r = findTitle(g.getChildAt(i));
                    if (r != null) {
                        return r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 视图树里是否存在"清晰度设置"标题 */
    private static boolean containsTitle(android.view.View v) {
        try {
            if (v instanceof android.widget.TextView) {
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if (txt.contains("清晰度设置")) {
                    return true;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (containsTitle(g.getChildAt(i))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 统计画质面板上下文里具体档位（数字P）数量 */
    private static int countConcreteResolutions(android.view.View v) {
        int n = 0;
        try {
            if (v instanceof android.widget.TextView) {
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if (txt.matches(".*[0-9]+P.*")) {
                    n++;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    n += countConcreteResolutions(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 判断"智能"文本是否画质档位项：向上找 DuxSelectionPanelItemView（文本+选框所在的项容器） */
    private static boolean isQualityItem(android.view.View tv) {
        try {
            android.view.View cur = tv;
            for (int i = 0; i < 6; i++) {
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof android.view.ViewGroup)) {
                    return false;
                }
                android.view.ViewGroup g = (android.view.ViewGroup) p;
                if (g.getClass().getName().contains("SelectionPanelItemView")) {
                    return true; // 在选项项内 → 是档位项
                }
                cur = g;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** DFS 扫描：把"智能"档改为具体分辨率（当前实际播放分辨率优先，其次最高支持档） */
    private static void renameSmartText(android.view.View v) {
        try {
            if (v instanceof android.widget.TextView) {
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if ("智能".equals(txt)) {
                    // 所有"智能"文本（面板项 + 外面画质标签）固定为"1080P"（用户最终确定）
                    String name = "1080P";
                    android.widget.TextView tv = (android.widget.TextView) v;
                    tv.setText(name);
                    // 防止长文本被省略号截断（如 1080P -> "1…"）
                    tv.setSingleLine(false);
                    tv.setEllipsize(null);
                    v.requestLayout();
                    XposedBridge.log("[明渠] 智能档改具体分辨率: " + name);
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    renameSmartText(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 向上找到选项项容器（DuxSelectionPanelItemView，含文本+选框），隐藏整项不留空白 */
    private static android.view.View findItemContainer(android.view.View tv) {
        android.view.View cur = tv;
        android.view.View prev = tv;
        for (int i = 0; i < 6; i++) {
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof android.view.ViewGroup)) {
                break;
            }
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            String cn = g.getClass().getName();
            if (cn.contains("SelectionPanelItemView")) {
                return g; // 选项项容器（文本+选框都在里面）
            }
            if (cn.contains("RecyclerView")) {
                return prev; // 真列表容器
            }
            prev = g;
            cur = g;
        }
        return prev; // 兜底：最深的小容器
    }

    /** DFS 扫描：隐藏画质面板里的"智能"档（连同选框整行隐藏，不留空白） */
    private static void hideSmartText(android.view.View v) {
        try {
            if (v instanceof android.widget.TextView) {
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if ("智能".equals(txt) && isQualityItem(v)) {
                    android.view.View target = findItemContainer(v);
                    target.setVisibility(android.view.View.GONE);
                    XposedBridge.log("[明渠] 已隐藏智能档整行(有具体档位): " + target.getClass().getSimpleName());
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    hideSmartText(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 判断是否画质面板上下文：向上 6 层内存在"清晰度设置"或"数字P"档位名 */
    private static boolean inResolutionPanel(android.view.View smartText) {
        android.view.View cur = smartText;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (cur instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) cur;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View ch = g.getChildAt(i);
                    if (ch instanceof android.widget.TextView) {
                        String s = ((android.widget.TextView) ch).getText().toString();
                        if (s.contains("清晰度设置") || s.matches(".*[0-9]+P.*")) {
                            return true;
                        }
                    }
                }
            }
            cur = (android.view.View) cur.getParent();
            depth++;
        }
        return false;
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
