package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 屏蔽推荐流直播间卡片：直播 item 渲染为空白（高度 1px，不显示不占位）
 */
public class BlockLiveCard implements FeatureEntry.FeatureHook {

    /** 直播 ViewHolder 相关的全部类 */
    private static final String[] LIVE_VH_CLASSES = {
            "com.ss.android.ugc.aweme.feed.viewholder.FeedCommonLiveViewHolder",
            "com.ss.android.ugc.aweme.feed.viewholder.DetailFeedLiveViewHolder",
            "com.ss.android.ugc.aweme.feed.viewholder.DetailPreviewLiveViewHolder",
            "com.ss.android.ugc.aweme.feed.viewholder.FeedTreasureBoxLiveViewHolder",
            "com.ss.android.ugc.aweme.feed.viewholder.FeedVoipShareLiveViewHolder",
    };

    /** 已被我们隐藏的直播卡片 itemView（弱引用，自动清理） */
    private static final java.util.Set<android.view.View> hiddenLiveViews =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    @Override
    public void apply(ClassLoader cl) throws Throwable {

        final java.util.List<Class<?>> mounted = new java.util.ArrayList<>();

        // 0) 数据层拦截：直播 item 直接从列表数据里过滤掉（不显示不占位）
        try {
            final Class<?> afa = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.adapter.AbstractFeedAdapter", cl);
            for (final String m : new String[]{"setData", "insert", "insertItemsForce", "replaceItemsForce"}) {
                try {
                    XposedBridge.hookAllMethods(afa, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 0 && param.args[0] instanceof java.util.List) {
                                    java.util.List<?> list = (java.util.List<?>) param.args[0];
                                    boolean hasLive = false;
                                    for (Object item : list) {
                                        try {
                                            if (XposedHelpers.callMethod(item, "getLiveRoom") != null) {
                                                hasLive = true;
                                                break;
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    if (hasLive) {
                                        java.util.List<Object> filtered = new java.util.ArrayList<>();
                                        for (Object item : list) {
                                            try {
                                                if (XposedHelpers.callMethod(item, "getLiveRoom") == null) {
                                                    filtered.add(item);
                                                }
                                            } catch (Throwable e) {
                                                filtered.add(item);
                                            }
                                        }
                                        param.args[0] = filtered;
                                        android.util.Log.i("MingQu", "[明渠] 数据过滤: 移除直播 " + (list.size() - filtered.size()) + " 条");
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            android.util.Log.i("MingQu", "[明渠] 直播数据过滤已挂载");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 直播数据过滤失败: " + t);
        }

        // 1) 每个直播 ViewHolder：构造后隐藏 + 子类 onViewAttachedToWindow（若 override）
        for (String clsName : LIVE_VH_CLASSES) {
            try {
                final Class<?> liveVh = XposedHelpers.findClass(clsName, cl);
                mounted.add(liveVh);
                XposedBridge.hookAllConstructors(liveVh, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Object holder = param.thisObject;
                        try {
                            // 延迟到 itemView 创建完成后，反射找最大 View 隐藏（不依赖字段名）
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        android.view.View content = findBiggestView(holder);
                                        android.view.View v = findRootItemView(content);
                                        if (v == null) {
                                            android.util.Log.i("MingQu", "[明渠] 延迟隐藏无View: " + liveVh.getSimpleName());
                                            return;
                                        }
                                        v.setVisibility(android.view.View.GONE);
                                        hiddenLiveViews.add(v);
                                        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                                        if (lp != null) {
                                            lp.height = 1;
                                            v.setLayoutParams(lp);
                                        }
                                        // 强制重布局 + 重绘，否则屏幕停留在旧帧
                                        v.requestLayout();
                                        v.invalidate();
                                        if (v.getParent() instanceof android.view.View) {
                                            ((android.view.View) v.getParent()).requestLayout();
                                        }
                                        android.util.Log.i("MingQu", "[明渠] 延迟隐藏成功: " + liveVh.getSimpleName()
                                                + " view=" + v.getClass().getSimpleName());
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }, 300);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                try {
                    XposedBridge.hookAllMethods(liveVh, "onViewAttachedToWindow", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                hideHolder(liveVh, param.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
                android.util.Log.i("MingQu", "[明渠] 直播卡片已挂载: " + clsName);
            } catch (Throwable ignored) {
                // 类不存在则跳过
            }
        }

        // 2) 父类 RecyclerView.ViewHolder 构造 —— 此时 itemView 一定已赋值（最可靠）
        try {
            XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$ViewHolder", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object h = param.thisObject;
                                for (Class<?> liveVh : mounted) {
                                    if (liveVh.isInstance(h)) {
                                        hideHolder(liveVh, h);
                                        android.util.Log.i("MingQu", "[明渠] 父类构造隐藏: " + liveVh.getSimpleName());
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            android.util.Log.i("MingQu", "[明渠] 父类构造隐藏已挂载");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 父类构造隐藏失败: " + t);
        }

        // 3) 父类 RecyclerView.ViewHolder.onViewAttachedToWindow —— 覆盖未 override 的 VH，每次滑回都隐藏
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$ViewHolder", cl),
                    "onViewAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object h = param.thisObject;
                                for (Class<?> liveVh : mounted) {
                                    if (liveVh.isInstance(h)) {
                                        hideHolder(liveVh, h);
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            android.util.Log.i("MingQu", "[明渠] 父类attach隐藏已挂载");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 父类attach隐藏失败: " + t);
        }

        // 4) 全局拦 View.setVisibility：被隐藏的直播卡片禁止任何方式重新显示
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.View", cl), "setVisibility",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 0 && (Integer) param.args[0] == android.view.View.VISIBLE) {
                                    android.view.View v = (android.view.View) param.thisObject;
                                    if (hiddenLiveViews.contains(v)) {
                                        // 强制保持隐藏（GONE 不占位），并把高度压到最小
                                        param.args[0] = android.view.View.GONE;
                                        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                                        if (lp != null) lp.height = 1;
                                        v.requestLayout();
                                        v.invalidate();
                                        if (v.getParent() instanceof android.view.View) {
                                            ((android.view.View) v.getParent()).requestLayout();
                                        }
                                        android.util.Log.i("MingQu", "[明渠] 拦截重新显示: " + v.getClass().getSimpleName());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            android.util.Log.i("MingQu", "[明渠] setVisibility拦截已挂载");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] setVisibility拦截失败: " + t);
        }

        // 2) 诊断：AbstractFeedAdapter.getItemViewType —— 打印直播 item 的 viewType（确认触发）
        try {
            Class<?> afa = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.adapter.AbstractFeedAdapter", cl);
            XposedBridge.hookAllMethods(afa, "getItemViewType", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object item = XposedHelpers.callMethod(
                                param.thisObject, "getWrappedOriginalItem", param.args[0]);
                        if (item != null) {
                            Object room = XposedHelpers.callMethod(item, "getLiveRoom");
                            if (room != null) {
                                android.util.Log.i("MingQu", "[明渠] 直播item出现 pos=" + param.args[0]);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 直播item诊断失败: " + t);
        }

        android.util.Log.i("MingQu", "[明渠] 已挂载：屏蔽直播间卡片");
    }

    private static void hideHolder(Class<?> liveVh, Object holder) {
        try {
            android.view.View v = findRootItemView(findBiggestView(holder));
            if (v == null) {
                android.util.Log.i("MingQu", "[明渠] 直播VH itemView为空: " + liveVh.getSimpleName());
                return;
            }
            v.setVisibility(android.view.View.GONE);
            hiddenLiveViews.add(v);
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 1;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 反射遍历 holder（含父类）所有字段，返回面积最大的内容 View */
    private static android.view.View findBiggestView(Object holder) {
        android.view.View fallback = null;
        long bestArea = -1;
        try {
            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object o = f.get(holder);
                        if (o instanceof android.view.View) {
                            android.view.View v = (android.view.View) o;
                            int w = v.getWidth();
                            int h = v.getHeight();
                            long area = (long) w * h;
                            if (area <= 0 && v.getLayoutParams() != null) {
                                int lw = v.getLayoutParams().width;
                                int lh = v.getLayoutParams().height;
                                if (lw == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                                    lw = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
                                }
                                if (lh == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                                    lh = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
                                }
                                if (lw > 0 && lh > 0) area = (long) lw * lh;
                            }
                            if (area > bestArea) {
                                bestArea = area;
                                fallback = v;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 从内容 View 沿 parent 链向上，找到"直接挂在 RecyclerView 下的根 itemView"（即直播间卡片容器） */
    private static android.view.View findRootItemView(android.view.View content) {
        if (content == null) return null;
        try {
            android.view.View cur = content;
            android.view.ViewParent p = cur.getParent();
            while (p instanceof android.view.View) {
                android.view.View pv = (android.view.View) p;
                android.view.ViewParent pp = pv.getParent();
                if (pp != null && pp.getClass().getName().toLowerCase().contains("recyclerview")) {
                    return pv; // 根 itemView（parent 是 RecyclerView）
                }
                p = pp;
            }
            // 没找到 RecyclerView 时，退而隐藏内容 View 的父容器
            if (content.getParent() instanceof android.view.View) {
                return (android.view.View) content.getParent();
            }
        } catch (Throwable ignored) {
        }
        return content;
    }
}
