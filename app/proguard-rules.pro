# FFmpegKit JNI
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long, double, double, double);
    int safOpen(int);
    int safClose(int);
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
# 注意：`_Impl` 也是 RoomDatabase 的子类，这条同时保住 `AppDatabase` 与生成的
# `AppDatabase_Impl` 的类名/构造器（Room 通过 "<db>_Impl" 反射加载实现类）。
-keep class * extends androidx.room.RoomDatabase { *; }

# ---------------------------------------------------------------- P6-01 补充（安全 keep）
# 仅在 isMinifyEnabled=true 时生效；debug 不跑 R8，因此不影响日常构建。
# 依据与逐条取舍见 docs/r8-evaluation.md（依赖 AAR 自带 consumer 规则 + 本项目反射面盘点）。
# 全部为“只增不减”的规则：即使某条实际冗余，也不会改变 debug/false 配置下的任何行为。

# 崩溃栈可读性：保留行号与源文件名，配合 mapping.txt 才能 retrace 到具体行。
-keepattributes SourceFile,LineNumberTable

# WorkManager 通过类名反射实例化 Worker（work-runtime 2.12.0 的 consumer 规则已 keepnames +
# 保留构造器；这里显式重复一次，防止将来改成“按字符串类名入队”时被 shrink）。
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Manifest 声明的 MediaSessionService：media3-session 1.11.1 没有自带 proguard.txt，
# aapt 规则只保证类名与默认构造器；onGetSession/onTaskRemoved 由系统/Binder 回调。
-keep class com.estimatefaster.audioforge.playback.AudioForgeMediaSessionService {
    <init>();
    public *;
}

# 通知「停止」按钮的显式广播接收器：onReceive 由系统调用，代码里没有直接引用。
-keepclassmembers class com.estimatefaster.audioforge.domain.jobs.GenerationNotificationsReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
