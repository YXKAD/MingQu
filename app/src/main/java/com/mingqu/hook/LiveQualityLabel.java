package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 直播画质面板：在档位名旁标注"分辨率+帧率"。
 *
 * 实现：不改 getName()（保持原生档位名，点击切换链路完全不受影响），
 * 把"分辨率 帧率fps"写入 item 的 subname（副标题字段）——面板副标题显示标注。
 *
 * 分辨率用静态映射（基于抖音直播常见档位 + 实测校准：标清 720×540、高清/超清/蓝光 1440×1080、
 * 原画 1920×1440），帧率取 item 的真实 fps。个别特殊推流直播间可能偏差。
 */
public class LiveQualityLabel implements FeatureEntry.FeatureHook {

    /** sdkKey → 分辨率 */
    private static String resolutionOf(String sdk) {
        if (sdk == null) {
            return null;
        }
        switch (sdk) {
            case "ld":
                return "720×540";
            case "sd":
            case "hd":
            case "uhd":
            case "xuhd":
            case "fhd":
                return "1440×1080";
            case "origin":
                return "1920×1440";
            default:
                return null;
        }
    }

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        // 1) 档位名保持不变，把"分辨率 帧率fps"写入 subname 副标题字段
        XposedHelpers.findAndHookMethod(
                "com.bytedance.android.livesdk.chatroom.ui.AudienceResolutionItemInfo",
                cl, "getName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object item = param.thisObject;
                            String sdk = String.valueOf(XposedHelpers.callMethod(item, "getSdkKey"));
                            int fps = (Integer) XposedHelpers.callMethod(item, "getFps");
                            String res = resolutionOf(sdk);
                            if (res != null) {
                                String sub = res + " " + fps + "fps";
                                XposedHelpers.callMethod(item, "setSubname", sub);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[明渠] 面板档位标注异常: " + t);
                        }
                    }
                });

        // 2) 探针：确认渲染层是否读取 subname（显示标注）
        XposedHelpers.findAndHookMethod(
                "com.bytedance.android.livesdk.chatroom.ui.AudienceResolutionItemInfo",
                cl, "getSubname",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("[明渠] 渲染读 subname: " + param.getResult());
                    }
                });
    }
}
