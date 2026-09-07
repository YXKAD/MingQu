# 防止入口类被混淆/裁剪（如果以后开启 minify，必须保留）
-keep class com.mingqu.hook.HookEntry { *; }
