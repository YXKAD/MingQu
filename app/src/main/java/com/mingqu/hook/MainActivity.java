package com.mingqu.hook;

import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * 模块管理界面。
 * 三个页面：模块（状态）、应用（功能开关）、设置（说明/关于）。
 * 界面里的开关会保存到本模块自己的 SharedPreferences，
 * Hook 运行时用 XSharedPreferences 读取同一份配置。
 */
public class MainActivity extends AppCompatActivity {

    private FrameLayout content;
    private TextView tabModule, tabApps, tabSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        tabModule = findViewById(R.id.tabModule);
        tabApps = findViewById(R.id.tabApps);
        tabSettings = findViewById(R.id.tabSettings);

        tabModule.setOnClickListener(v -> showModulePage());
        tabApps.setOnClickListener(v -> showAppsPage());
        tabSettings.setOnClickListener(v -> showSettingsPage());

        showModulePage();
    }

    // ================= 页面切换 =================

    private void selectTab(int tabId) {
        int selected = 0xFFE9C77B; // 月光金
        int normal = 0xFF9AA7B8;   // 月下灰蓝
        tabModule.setTextColor(tabId == R.id.tabModule ? selected : normal);
        tabApps.setTextColor(tabId == R.id.tabApps ? selected : normal);
        tabSettings.setTextColor(tabId == R.id.tabSettings ? selected : normal);
    }

    private void setPage(View page) {
        content.removeAllViews();
        content.addView(page);
    }

    private LinearLayout newColumn() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(16), dp(16), dp(16));
        return ll;
    }

    private void finishPage(LinearLayout column) {
        ScrollView sv = new ScrollView(this);
        sv.addView(column);
        setPage(sv);
    }

    // ================= 模块页 =================

    private void showModulePage() {
        selectTab(R.id.tabModule);
        LinearLayout ll = newColumn();
        addText(ll, "明渠", 28, true);
        addText(ll, "我本将心向明月，奈何明月照沟渠", 13, false);
        addSpace(ll, 8);

        // 框架状态卡
        int total = 0, enabled = 0;
        List<AppEntry> apps = FeatureRegistry.allApps();
        for (AppEntry app : apps) {
            for (FeatureEntry f : app.features) {
                total++;
                if (Prefs.read(this, app.packageName, f.key, f.defaultOn)) enabled++;
            }
        }
        LinearLayout status = card(ll);
        addText(status, "框架状态", 18, true);
        addText(status, "框架服务已连接 · LSPosed", 14, false);
        addText(status, "框架已激活", 14, false);
        addSpace(status, 8);
        addText(status, "已适配 " + total + " 项功能 ｜ 已启用 " + enabled + " 项", 16, true);

        // 应用数量卡
        LinearLayout appCard = card(ll);
        addText(appCard, "已适配应用", 18, true);
        for (AppEntry app : apps) {
            addText(appCard, "• " + app.label + "（" + app.features.length + " 项功能）", 14, false);
        }

        // 设备信息卡
        LinearLayout info = card(ll);
        addText(info, "设备信息", 18, true);
        addText(info, "应用版本  " + appVersion(), 14, false);
        addText(info, "Android 版本  " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）", 14, false);
        addText(info, "设备型号  " + Build.MODEL, 14, false);

        finishPage(ll);
    }

    // ================= 应用页 =================

    private void showAppsPage() {
        selectTab(R.id.tabApps);
        LinearLayout ll = newColumn();
        addText(ll, "应用", 24, true);
        addText(ll, "先到 LSPosed 勾选作用域，再开关下面的功能", 13, false);

        for (AppEntry app : FeatureRegistry.allApps()) {
            LinearLayout c = card(ll);
            addText(c, app.label, 18, true);
            addText(c, app.packageName, 12, false);
            for (FeatureEntry f : app.features) {
                switchRow(c, f.title, f.desc, Prefs.read(this, app.packageName, f.key, f.defaultOn),
                        (buttonView, isChecked) ->
                                Prefs.write(this, app.packageName, f.key, isChecked));
            }
        }
        finishPage(ll);
    }

    // ================= 设置页 =================

    private void showSettingsPage() {
        selectTab(R.id.tabSettings);
        LinearLayout ll = newColumn();
        addText(ll, "设置", 24, true);

        LinearLayout usage = card(ll);
        addText(usage, "使用说明", 18, true);
        addText(usage, "1. 在 LSPosed 中启用本模块\n"
                + "2. 勾选要生效的应用作用域\n"
                + "3. 在系统设置里强制停止目标应用，重新打开\n"
                + "4. 到 LSPosed → 日志 查看 [HookTool] 开头的日志", 14, false);

        LinearLayout about = card(ll);
        addText(about, "关于", 18, true);
        addText(about, "明渠 · 通用 Hook 工具 v" + appVersion(), 14, false);
        addText(about, "每个功能独立容错，单项失败不影响应用。", 13, false);
        addText(about, "仅供学习交流与技术研究。", 13, false);

        finishPage(ll);
    }

    // ================= UI 小工具 =================

    /**
     * 创建一个卡片，返回卡片内部的纵向 LinearLayout。
     * 注意：MaterialCardView 是 FrameLayout（层叠布局），
     * 直接往里面加多个文字会叠在一起，所以必须先放一个内层 LinearLayout。
     */
    private LinearLayout card(LinearLayout parent) {
        MaterialCardView c = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        c.setRadius(dp(16));
        c.setCardElevation(dp(1));
        c.setUseCompatPadding(true);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(12));
        c.addView(inner);

        parent.addView(c);
        return inner;
    }

    private void switchRow(ViewGroup card, String title, String desc,
                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout txts = new LinearLayout(this);
        txts.setOrientation(LinearLayout.VERTICAL);
        txts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addText(txts, title, 15, true);
        if (desc != null && !desc.isEmpty()) addText(txts, desc, 12, false);

        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);

        row.addView(txts);
        row.addView(sw);
        card.addView(row);
    }

    private TextView addText(ViewGroup parent, String text, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(2), 0, dp(2));
        parent.addView(tv);
        return tv;
    }

    private void addSpace(ViewGroup parent, int dpSize) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpSize)));
        parent.addView(v);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
