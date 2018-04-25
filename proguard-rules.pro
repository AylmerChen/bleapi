# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

#注意库中的混淆规则也会影响到引用的库，所以需要尽可能缩小适用范围

#把混淆类中的方法名也混淆了
-useuniqueclassmembernames

#不混淆内部类
-keep class com.bleapi.*$* {
    *;
}

#不混淆公共方法和静态常量
-keep class com.bleapi.** {
    public <methods>;
    public static final <fields>;
}
