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
-assumenosideeffects class android.os.Debug {
    public static *** isDebuggerConnected();
    public static *** waitingForDebugger();
}

# ===== 缺类告警 =====
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.**
-dontwarn androidx.**

# 入口类保留（Manifest 引用，R8 本来也会 handle，这里显式声明防误删）
-keep class com.tencent.ToolBox.BoxApp { *; }
-keep class com.tencent.ToolBox.MainActivity { *; }
