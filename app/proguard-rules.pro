# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# 模块本身不做混淆（minifyEnabled false），入口类 cn.cf3012.xdf.XDFHook
# 由 LSPosed 通过 assets/xposed_init 反射创建，如开启混淆需 keep：
# -keep class cn.cf3012.xdf.XDFHook { *; }
