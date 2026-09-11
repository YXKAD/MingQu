package com.mingqu.hook;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 直播自动最高画质 v2。
 *
 * 基于 jadx 反编译（v39.8.0）定位：
 * - 进房初始画质与手动切换都经过 Lynx 直播视图：
 *   com.bytedance.ies.xelement.live.LynxLiveView.setQualities(String)
 *   （JS 推送 qualities 属性 → 存入 qualitiesTemp → 初始化播放请求 resolution / 调客户端切画质）
 * - 混合直播盒子（EC 栈）：X.ViewOnAttachStateChangeListenerC718120S1s.switchResolution(String)
 *   与 X.C908350Zel.LIZLLL(String) 同样会调客户端切画质
 * - 客户端层：com.bytedance.android.livesdk.player.LivePlayerClient
 *   switchResolution(String) / switchResolution(String,String,Map)，支持 isSupportResolutionSwitch 探测
 * - 播放器启动画质：com.ss.videoarch.liveplayer2.VeLivePlayer.setStartPlayResolution(...)
 *
 * 行为（用户确认的语义）：
 * - 每个房间视图实例的"首次"画质设置 = 进房初始 → 强制为该直播间支持的最高档（逐档探测，向下兼容）；
 * - 之后的画质设置 = 手动切换 → 尊重用户选择，不拉回；
 * - 重启抖音精选 = 新进程、新视图实例 → 每个房间重新强制最高。
 */
public class LiveMaxQuality implements FeatureEntry.FeatureHook {

    /** 从高到低的直播档位候选 */
    private static final String[] CANDIDATES = {"xuhd", "origin", "uhd", "hd"};

    /** 进程级武装：冷启动为 true；首次手动切换后解除，直到进程重启 */
    private static volatile boolean armed = true;

    /** 每个视图实例只强制首次（进房初始），之后尊重手动 */
    private static final WeakHashMap<Object, Boolean> VIEW_DONE = new WeakHashMap<>();

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：直播自动最高画质(v2)");

        // 1) Lynx 直播视图：进房初始 & 手动切换都经过 setQualities
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.ies.xelement.live.LynxLiveView", cl,
                    "setQualities", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleViewSwitch(param, cl, "LynxLiveView");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook1失败(LynxLiveView): " + t);
        }

        // 2) 混合直播盒子视图（EC 栈，真实类名 X.0S1s）
        try {
            XposedHelpers.findAndHookMethod(
                    "X.0S1s", cl,
                    "switchResolution", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleViewSwitch(param, cl, "HybridBox");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook2失败(HybridBox): " + t);
        }

        // 3) 直播视图配置控制器（真实类名 X.0Zel）
        try {
            XposedHelpers.findAndHookMethod(
                    "X.0Zel", cl,
                    "LIZLLL", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleViewSwitch(param, cl, "ViewConfig");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook3失败(ViewConfig): " + t);
        }

        // 4) livesdk 客户端：武装期间强制（兜底，覆盖不走视图层的内部恢复）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.livesdk.player.LivePlayerClient", cl,
                    "switchResolution", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            forceOnClient(param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook4失败(Client): " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.android.livesdk.player.LivePlayerClient", cl,
                    "switchResolution", String.class, String.class, Map.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            forceOnClient(param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook5失败(Client3): " + t);
        }

        // 5) 播放器启动画质：进房开始播放时生效（武装期间）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.ss.videoarch.liveplayer2.VeLivePlayer", cl,
                    "setStartPlayResolution",
                    "com.ss.videoarch.liveplayer2.VeLivePlayerResolution",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!armed) {
                                return;
                            }
                            try {
                                String best = pickSupportedOnPlayer(param.thisObject, cl);
                                if (best != null) {
                                    Object res = XposedHelpers.newInstance(
                                            XposedHelpers.findClass(
                                                    "com.ss.videoarch.liveplayer2.VeLivePlayerResolution", cl),
                                            best);
                                    param.args[0] = res;
                                    XposedBridge.log("[明渠] 直播启动画质强制最高: " + best);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[明渠] 直播Hook6异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播Hook6失败(启动画质): " + t);
        }
    }

    /** 视图层切换：实例首次=进房初始→强制；后续=手动→尊重并解除客户端层武装 */
    private static void handleViewSwitch(XC_MethodHook.MethodHookParam param, ClassLoader cl, String tag) {
        try {
            Object view = param.thisObject;
            if (view == null) {
                return;
            }
            boolean first;
            synchronized (VIEW_DONE) {
                first = !VIEW_DONE.containsKey(view);
                if (first) {
                    VIEW_DONE.put(view, Boolean.TRUE);
                } else {
                    armed = false;
                }
            }
            if (first) {
                String best = pickSupportedThroughView(view, cl);
                String orig = (String) param.args[0];
                if (best != null && !best.equals(orig)) {
                    param.args[0] = best;
                    XposedBridge.log("[明渠] 直播进房画质强制最高[" + tag + "]: " + orig + " -> " + best);
                }
            } else {
                XposedBridge.log("[明渠] 直播手动切换[" + tag + "]，尊重用户选择: " + param.args[0]);
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播视图Hook异常[" + tag + "]: " + t);
        }
    }

    /** 客户端层：武装期间强制为该客户端支持的最高档 */
    private static void forceOnClient(XC_MethodHook.MethodHookParam param) {
        try {
            Object client = param.thisObject;
            String original = (String) param.args[0];
            if (client == null || original == null) {
                return;
            }
            XposedBridge.log("[明渠] 直播客户端切画质调用: " + original + " (armed=" + armed + ")");
            if (!armed) {
                return;
            }
            String best = pickSupportedOnClient(client);
            if (best != null && !best.equals(original)) {
                param.args[0] = best;
                XposedBridge.log("[明渠] 直播画质强制最高(Client): " + original + " -> " + best);
            }
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 直播客户端Hook异常: " + t);
        }
    }

    /** 从视图链拿客户端并探测支持的最高档；拿不到就退回最高候选 */
    private static String pickSupportedThroughView(Object view, ClassLoader cl) {
        // 尝试从视图上直接探测（部分视图对象本身可探测）
        String best = pickSupportedOnClient(view);
        if (best != null) {
            return best;
        }
        // LynxLiveView: getView().getPlayerView$x_element_live_release().getClient()
        try {
            Object container = XposedHelpers.callMethod(view, "getView");
            if (container != null) {
                Object playerView = XposedHelpers.callMethod(
                        container, "getPlayerView$x_element_live_release");
                if (playerView != null) {
                    Object client = XposedHelpers.callMethod(playerView, "getClient");
                    if (client != null) {
                        best = pickSupportedOnClient(client);
                        if (best != null) {
                            return best;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // HybridBox: 字段 n 是 IECLivePlayerClient
        try {
            Object client = XposedHelpers.getObjectField(view, "n");
            if (client != null) {
                best = pickSupportedOnClient(client);
                if (best != null) {
                    return best;
                }
            }
        } catch (Throwable ignored) {
        }
        return CANDIDATES[0]; // 探测不到时退回最高候选，让播放器向下兼容
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
