package com.mingqu.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 功能注册表 —— 以后要加新应用/新功能，就只改这一个文件 ★
 *
 * 界面（应用页）和 Hook 分发（HookEntry）会自动读取这里的配置。
 * 注意：新应用加进来后，还要到 LSPosed 里给本模块勾上该应用的作用域，
 * 并在 res/values/arrays.xml 的作用域列表里加一行。
 */
public class FeatureRegistry {

    public static List<AppEntry> allApps() {
        List<AppEntry> apps = new ArrayList<>();

        // ---------- 抖音精选 ----------
        apps.add(new AppEntry(
                "com.ss.android.yumme.video",
                "抖音精选",
                new FeatureEntry[]{
                        new FeatureEntry(
                                "block_update",
                                "屏蔽新版本更新",
                                "拦截“发现新版本”更新弹窗，不再打扰",
                                true,
                                new BlockUpdate()),
                        new FeatureEntry(
                                "live_max_quality",
                                "直播自动最高画质",
                                "进入直播间自动切换为最高清晰度",
                                true,
                                new LiveMaxQuality()),
                        new FeatureEntry(
                                "video_max_quality",
                                "视频自动最高画质",
                                "信息流短视频自动以最高清晰度播放",
                                true,
                                new VideoMaxQuality()),
                        new FeatureEntry(
                                "live_quality_label",
                                "直播面板标注分辨率帧率",
                                "画质面板档位名后追加该档分辨率+帧率",
                                true,
                                new LiveQualityLabel()),

                }));

        // =====================================================
        // 以后要加新应用，就在下面继续 add，例如：
        //
        // apps.add(new AppEntry(
        //         "com.xxx.yyy",                    // 应用包名
        //         "应用名",                          // 显示名称
        //         new FeatureEntry[]{
        //                 new FeatureEntry(
        //                         "feature_key",    // 功能唯一 key
        //                         "功能名",          // 显示名
        //                         "功能说明",        // 说明文字
        //                         true,              // 默认开启
        //                         new MyFeature())   // 实现 FeatureHook 的类
        //         }));
        // =====================================================

        return apps;
    }

    /** 按包名找应用定义，找不到返回 null */
    public static AppEntry findApp(String packageName) {
        for (AppEntry app : allApps()) {
            if (app.packageName.equals(packageName)) {
                return app;
            }
        }
        return null;
    }
}
