package com.mingqu.hook;

/**
 * 一个"功能"的定义。
 *
 * 比如"屏蔽未成年人模式弹窗"就是一个功能。
 * 每个功能在界面里会显示一个开关，开关状态决定 Hook 是否生效。
 */
public class FeatureEntry {

    /**
     * 功能的 Hook 逻辑。不同功能实现各自的 apply()。
     * apply() 运行在目标应用的进程里。
     */
    public interface FeatureHook {
        void apply(ClassLoader cl) throws Throwable;
    }

    /** 唯一 key，用于保存开关状态（同一个应用内不能重复） */
    public final String key;
    /** 界面显示的功能名 */
    public final String title;
    /** 界面显示的功能说明 */
    public final String desc;
    /** 默认是否开启 */
    public final boolean defaultOn;
    /** Hook 逻辑实现 */
    public final FeatureHook hook;

    public FeatureEntry(String key, String title, String desc, boolean defaultOn, FeatureHook hook) {
        this.key = key;
        this.title = title;
        this.desc = desc;
        this.defaultOn = defaultOn;
        this.hook = hook;
    }
}
