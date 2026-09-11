# 明渠 · MingQu

> 我本将心向明月，奈何明月照沟渠。

一个基于 **LSPosed / Xposed** 的通用 Hook 工具：模块界面内逐应用、逐功能管理开关，方便逐步扩展适配更多应用。夜色与月光主题。

## 已适配应用与功能

| 应用 | 包名 | 功能 | 说明 |
|---|---|---|---|
| 抖音精选 | `com.ss.android.yumme.video` | 屏蔽新版本更新 | 拦截 UpdateHelper.LJIJI 构建的"发现新版本"弹窗及更新页 UpdateActivity |
| 抖音精选 | `com.ss.android.yumme.video` | 直播自动最高画质 | 进房自动蓝光(最高可用档，逐档探测向下兼容)；手动切换尊重选择，重启恢复强制 |
| 抖音精选 | `com.ss.android.yumme.video` | 视频自动最高画质 | 冷启动后信息流视频自动最高清晰度（按每视频支持列表向下兼容）；手动切换后不再强制，重启恢复 |
| 抖音精选 | `com.ss.android.yumme.video` | 直播面板标注分辨率帧率 | 画质面板档位旁标注该档分辨率+帧率（subname 副标题，不改档位名，点击切换不受影响） |
| 抖音精选 | `com.ss.android.yumme.video` | 机型伪装 | 伪装为 vivo Pad6 Pro，解锁服务端 2K/4K 视频源与直播原画档位（可单独关闭） |
| 抖音精选 | `com.ss.android.yumme.video` | 屏蔽推荐流直播间卡片 | 数据层过滤：直播 item 在进入列表前移除（不显示、不占位） |
| 抖音精选 | `com.ss.android.yumme.video` | 双击点赞→打开评论区（测试中） | 双击不再点赞，改为打开评论区；暂停拦截已生效，暂停标志残留已知（手动暂停再恢复后消失） |

> 均基于 jadx 反编译 v39.8.0 精确定位；日志前缀 `[明渠]` 可在 LSPosed → 日志 中查看命中情况。

## 界面

三页结构（参考 Rain 模块交互）：

- **模块**：框架状态、已适配/已启用统计、设备信息
- **应用**：每个应用一张卡片，逐功能开关
- **设置**：使用说明、关于

## 构建

环境要求：JDK 21、Android SDK（`local.properties` 配 `sdk.dir`）、Gradle 8.13（自带 wrapper）。

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 若工程路径含中文，需要在 `gradle.properties` 里保留 `android.overridePathCheck=true`（Windows 下 AGP 的路径检查会拒绝非 ASCII 路径）。

## 安装与启用

1. 安装 APK（已 root 设备可用 `adb shell su -c "pm install -r xxx.apk"` 绕过厂商确认框）
2. 打开 **LSPosed → 模块**，启用"明渠"
3. 点进明渠，勾选目标应用作用域（如 抖音精选）
4. 在系统设置里强制停止目标应用，重新打开生效
5. 查看 **LSPosed → 日志**，搜索 `[明渠]` 前缀确认 Hook 是否挂载

## 添加新应用/新功能

只需改 **一个文件** `app/src/main/java/com/mingqu/hook/FeatureRegistry.java`，在 `allApps()` 里照葫芦画瓢 `add` 一行，界面和 Hook 分发会自动生效。同步做两件事：

1. `app/src/main/res/values/arrays.xml` 的作用域列表加一行包名
2. LSPosed 里给明渠勾选该应用作用域

每个功能是一个实现 `FeatureEntry.FeatureHook` 的类，Hook 运行在目标应用进程，逐功能独立 try/catch，单项失效不影响目标应用。

## 进阶：逆向定位 Hook 点

1. 提取目标应用 APK：`adb shell pm path <包名>` → `adb pull`，或用 MT 管理器 root 提取
2. 用 [jadx](https://github.com/skylot/jadx) 反编译
3. 搜索关键中文字符串（如"发现新版本""立即更新""超清""蓝光"）定位弹窗/清晰度相关类与方法
4. 把精确 hook 填入对应的功能类，替换掉通用拦截或 TODO

## 免责声明

本项目仅供学习交流与技术研究，请勿用于任何违反平台规则或法律法规的用途。使用风险自负。

## 参考

- [Rain (love.nairain.hook)](https://github.com/Xposed-Modules-Repo/love.nairain.hook)：交互与理念参考（框架状态 / 应用作用域管理 / 逐功能容错）
- [LSPosed 官方仓库](https://github.com/LSPosed/LSPosed)