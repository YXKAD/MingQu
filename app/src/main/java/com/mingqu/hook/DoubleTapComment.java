package com.mingqu.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 视频双击：拦截点赞 + 打开评论区（窗口期内播放器不暂停，进度条正常；暂停标为已知残留）
 * 状态：测试中
 */
public class DoubleTapComment implements FeatureEntry.FeatureHook {

    /** 双击打开评论后的暂停拦截窗口（毫秒时间戳），窗口期内 pause 被拦截——视频继续播 */
    private static volatile long pauseBlockUntil = 0;

    @Override
    public void apply(ClassLoader cl) throws Throwable {

        // 双击检测：hook 所有 View 的 dispatchTouchEvent（覆盖任何容器）
        try {
            final XC_MethodHook dtHook = new XC_MethodHook() {
                private long lastDown = 0;
                private long lastUp = 0;
                private boolean consuming = false;

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                        int act = ev.getActionMasked();
                        if (consuming) {
                            if (act == android.view.MotionEvent.ACTION_UP
                                    || act == android.view.MotionEvent.ACTION_CANCEL) {
                                consuming = false;
                            }
                            param.setResult(true);
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (act == android.view.MotionEvent.ACTION_DOWN) {
                            long gapUp = now - lastUp;
                            long gapDown = now - lastDown;
                            if ((gapUp > 0 && gapUp < 150) || (gapDown > 50 && gapDown < 200)) {
                                android.util.Log.i("MingQu", "[明渠] ★双击！拦截点赞+打开评论区");
                                consuming = true;
                                param.setResult(true);
                                pauseBlockUntil = System.currentTimeMillis() + 2000;
                                openComment((android.view.View) param.thisObject);
                                return;
                            }
                            lastDown = now;
                        } else if (act == android.view.MotionEvent.ACTION_UP) {
                            lastUp = now;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.View", cl), "dispatchTouchEvent", dtHook);
            android.util.Log.i("MingQu", "[明渠] 已挂载：双击→评论区");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 双击检测挂载失败: " + t);
        }

        // 上层暂停入口：X.0moZ.pause（评论打开时异步调用的控制器暂停——播放器+进度条一起拦）
        try {
            Class<?> pz = XposedHelpers.findClass("X.0moZ", cl);
            XposedBridge.hookAllMethods(pz, "pause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long now = System.currentTimeMillis();
                    if (now < pauseBlockUntil) {
                        // 只拦评论打开这"第一次"暂停（拦完即关窗口，之后手动暂停不受影响）
                        pauseBlockUntil = 0;
                        param.setResult(null);
                        android.util.Log.i("MingQu", "[明渠] 拦截上层暂停(0moZ)");
                    }
                }
            });
            android.util.Log.i("MingQu", "[明渠] 已挂载：上层暂停拦截(0moZ)");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 0moZ挂载失败: " + t);
        }

        // 播放器暂停兜底：X.0mmG.pause（其他暂停路径）
        try {
            Class<?> pc = XposedHelpers.findClass("X.0mmG", cl);
            XposedBridge.hookAllMethods(pc, "pause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long now = System.currentTimeMillis();
                    if (now < pauseBlockUntil) {
                        // 只拦评论打开这"第一次"暂停（拦完立即关闭窗口，之后手动暂停不受影响）
                        pauseBlockUntil = 0;
                        param.setResult(null);
                        android.util.Log.i("MingQu", "[明渠] 拦截播放器暂停(0mmG)");
                    }
                }
            });
            android.util.Log.i("MingQu", "[明渠] 已挂载：评论打开不暂停(0mmG)");
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 0mmG挂载失败: " + t);
        }
    }

    /** 双击时自动打开评论区：找 contentDescription 含"评论"的按钮并点击 */
    private static void openComment(android.view.View anyView) {
        try {
            android.app.Activity act = null;
            android.content.Context ctx = anyView.getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof android.app.Activity) {
                    act = (android.app.Activity) ctx;
                    break;
                }
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
            if (act == null) {
                android.util.Log.i("MingQu", "[明渠] 评论打开失败：无Activity");
                return;
            }
            final android.view.View btn = findCommentView(act.getWindow().getDecorView());
            if (btn == null) {
                android.util.Log.i("MingQu", "[明渠] 评论按钮未找到");
                return;
            }
            act.getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        btn.performClick();
                        android.util.Log.i("MingQu", "[明渠] 已点击评论按钮");
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            android.util.Log.i("MingQu", "[明渠] 评论打开异常: " + t);
        }
    }

    private static android.view.View findCommentView(android.view.View v) {
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.toString().contains("评论")) {
                // 只接受当前真实可见（在屏幕上）的按钮，避免点到 RecyclerView 里旧视频的按钮
                android.graphics.Rect r = new android.graphics.Rect();
                if (v.isShown() && v.getGlobalVisibleRect(r)) {
                    return v;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    android.view.View r = findCommentView(vg.getChildAt(i));
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
