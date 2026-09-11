package com.mingqu.hook;

import android.os.Build;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抖音精选 —— 机型伪装为 vivo Pad6 Pro。
 *
 * 服务端按机型判断设备能力：非高端机型不下发 2K/4K 视频源。
 * 伪装成 Pad6 Pro 后，服务端会下发 2K/4K 源，配合视频强开即可显示并播放。
 *
 * 值取自用户原机型伪装配置（vivo Pad6 Pro 模板）：
 *   MODEL=PA2671, DEVICE=DPD2329, PRODUCT=DPD2329, BRAND=vivo, MANUFACTURER=vivo
 * 仅影响本进程（抖音精选），不全局伪装。
 */
public class DeviceSpoof implements FeatureEntry.FeatureHook {

    @Override
    public void apply(ClassLoader cl) throws Throwable {
        XposedBridge.log("[明渠] 已挂载：机型伪装(vivo Pad6 Pro)");

        spoof("MODEL", "PA2671");
        spoof("DEVICE", "DPD2329");
        spoof("PRODUCT", "DPD2329");
        spoof("BRAND", "vivo");
        spoof("MANUFACTURER", "vivo");

        XposedBridge.log("[明渠] 伪装完成: MODEL=" + Build.MODEL
                + " DEVICE=" + Build.DEVICE + " PRODUCT=" + Build.PRODUCT
                + " BRAND=" + Build.BRAND + " MANUFACTURER=" + Build.MANUFACTURER);
    }

    /** 用 XposedHelpers 改静态字段（支持 final，LSPosed 下可靠） */
    private void spoof(String field, String value) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, field, value);
        } catch (Throwable t) {
            XposedBridge.log("[明渠] 伪装 " + field + " 失败: " + t);
        }
    }
}
