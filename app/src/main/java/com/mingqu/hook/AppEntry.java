package com.mingqu.hook;

/**
 * 一个"应用"的定义。
 *
 * 比如"抖音精选"就是一个应用，它下面挂着一组功能（FeatureEntry）。
 */
public class AppEntry {

    /** 应用的包名（LSPosed 作用域和 Hook 分发都靠它） */
    public final String packageName;
    /** 界面显示的应用名 */
    public final String label;
    /** 这个应用下的所有功能 */
    public final FeatureEntry[] features;

    public AppEntry(String packageName, String label, FeatureEntry[] features) {
        this.packageName = packageName;
        this.label = label;
        this.features = features;
    }
}
