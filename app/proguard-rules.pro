# ===== 加固：R8 激进混淆 =====
# 所有类重打包进默认包，逆向时看到的全是 a.b.c 这种无法辨认的路径
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-adaptclassstrings

# 只留 Compose/Kotlin 运行时真正需要的属性。
# 关键是不要 *Annotation*：Kotlin 的 Metadata 注解带着完整原始类名与函数签名，
# 留着它等于把混淆结果白送，用 kotlinx-metadata 一读就还原。
-keepattributes Signature,InnerClasses,EnclosingMethod
# AGP 自带的 proguard-android-optimize.txt 里有 -keepattributes *Annotation*，
# 会把 kotlin.Metadata（含原始类名/签名）一起留下，这里显式排除掉
-keepattributes !*Annotation*
# 崩溃栈里的文件名统一抹掉，行号与局部变量表也不要
-renamesourcefileattribute X

# ===== 擦除日志 =====
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
# 注意：不要对 android.os.Debug 用 -assumenosideeffects。
# R8 会据此把 isDebuggerConnected() / waitingForDebugger() 的调用整个删掉、用默认值 false 顶替，
# 等于把 Guard 里的调试器检测这道闸自己擦掉了。擦 Log 可以（那些方法本来只返回 int），擦 Debug 是自伤。

# ===== 缺类告警 =====
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.**
-dontwarn androidx.**

# 入口类保留（Manifest 引用，R8 本来也会 handle，这里显式声明防误删）
-keep class com.AusKa.ToolBox.BoxApp { *; }
-keep class com.AusKa.ToolBox.MainActivity { *; }

# 学习通助手的 JS 桥：R8 会把没被引用的 @JavascriptInterface 方法当死代码删掉，
# 删掉之后 addJavascriptInterface 注入的对象在页面里就只剩空壳。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.AusKa.ToolBox.chaoxing.CsBridge { *; }
