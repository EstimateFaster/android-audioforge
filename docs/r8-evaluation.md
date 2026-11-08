# R8 / 代码缩小评估（P6-01 R8）

> 状态：**评估完成；构建开关未改**（本文与 `app/proguard-rules.pro` 的补充规则是本任务唯一产物）
> 基线：`main@8df1b89`（AGP 9.5.1 / Gradle 9.9.0 / JDK 17 / R8 full mode 默认开启）
> 对照：执行计划 §6.4（构建配置）、§6.10（keep 规则）、§13（安全）、§14.6（验收门）、§15 P6-01
> 结论：**可以开启 `isMinifyEnabled=true` + `isShrinkResources=true`**，但必须在真机上跑完本文 §6 的 smoke 矩阵，
> 且先把 release 构建纳入 CI（当前 main CI 只构建 debug），否则只会在打 tag 时才发现问题。

---

## 1. 结论（TL;DR）

1. **风险总体可控**。本项目没有 Hilt/Dagger、没有 Gson/Moshi、没有 `Class.forName` 式插件系统；
   反射面只有三处：Room 的 `_Impl`、WorkManager 的 Worker、Navigation 的 route serializer。
   前两处依赖库自带 consumer 规则即已覆盖，第三处由 kotlinx.serialization 1.9.0 自带的
   consumer 规则覆盖（我们手写的老式规则是冗余但安全的）。
2. **收益主要在 dex，不在原生库**。universal APK 里 ~44 MB 是 FFmpeg `.so`（双 ABI），R8 动不了；
   dex 约占 14–16 MB，预计缩小 25%–40%（**估算 −4 ~ −6.5 MB / −7% ~ −11% APK**）。
   真正的大头是 P6-02 的 ABI split，不是 R8。
3. **resource shrinker 收益极小**：本项目 `res/` 只有 84 KB（无 PNG/XML 布局），预计只省几十 KB；
   但风险也极小——代码里没有 `getIdentifier`/资源名拼接，assets（36 KB）不受 shrinker 影响。
4. **最大风险是流程，不是规则**：`.github/workflows/ci.yml` 只跑
   `lintDebug testDebugUnitTest assembleDebug`，release/minify 只有 tag 触发的 `release.yml` 才构建。
   开启 minify 后，日常 PR 无法发现 shrink 崩溃，等于把风险推到发布当天。
   → 建议在 P6-09 前补一个 `assembleRelease`（未签名即可）的 CI job，并归档 mapping。
5. **本任务不打开开关**（按要求只改文档与 `proguard-rules.pro`）。开关的两行改动应在
   P6-01 的后续提交里做，并用本文 §6 的流程验证后再合入。

---

## 2. 现状

### 2.1 release

`app/build.gradle.kts` 的 `release` 块（第 52–59 行）：

| 开关 | 当前值 | 说明 |
|---|---|---|
| `isMinifyEnabled` | `false` | R8 完全不跑：不 shrink、不 optimize、不 obfuscate |
| `isShrinkResources` | `false` | 资源收缩也关闭（它依赖 `isMinifyEnabled=true`） |
| `proguardFiles` | `proguard-android-optimize.txt` + `proguard-rules.pro` | 已声明，但 minify 关闭时**不会被应用** |
| `signingConfig` | 仅在 `ANDROID_KEYSTORE_PATH` 存在时设为 release | 本地无 keystore → 产出 `app-release-unsigned.apk` |

因此：

- 当前 release APK 与 debug APK 的业务代码都**没有被 shrink/obfuscate**，差异只有签名、`BuildConfig.DEBUG`、
  applicationId 与 debug-only 依赖（`ui-tooling` 等）；
- `proguard-rules.pro` 目前是"死文件"——只作为未来开启 R8 的合同存在；
- 本地 `assembleRelease` 能构建成功，但产物未签名；正式签名只在 CI（`release.yml` 用 Secrets 解码 keystore）。
- v0.1.0 的 universal APK 约 60.5 MB（`docs/progress.md` 已发布记录），其中 FFmpeg 原生库约 44 MB
  （本地缓存 AAR 实测：arm64 20.4 MB + x86_64 23.5 MB，未压缩），`res/` 84 KB、assets 36 KB。

### 2.2 debug 对照

| 维度 | debug | release（当前） |
|---|---|---|
| minify/shrink | 否（AGP 固定不 shrink debug） | 否 |
| `applicationId` | `com.estimatefaster.audioforge.debug` | `com.estimatefaster.audioforge` |
| `versionName` | `0.1.0-debug` | `0.1.0` |
| 额外依赖 | `ui-tooling`、`ui-test-manifest` | 无 |
| `BuildConfig.DEBUG` | `true` → `AudioForgeApp` 启动跑一次 Room 探针 | `false` → 探针分支被 `const` 折叠，release 不会执行 |
| 可调试 | 是（默认 debuggable） | 否 |

要点：**debug 永远不压缩**。所以"debug 上能跑"不能证明 shrink 后的 release 能跑；
P6-01 之后每次 release 候选都必须单独 smoke（见 §6）。

### 2.3 当前 keep 规则（`app/proguard-rules.pro` 基线 20 行）

| 规则 | 覆盖对象 | 评价 |
|---|---|---|
| `-keep class com.arthenica.ffmpegkit.** { *; }` | 整个 FFmpegKit 包 | 比 AAR 自带规则宽得多；安全但可以收窄（§5.3） |
| `-keepclassmembers ... FFmpegKitConfig { native …; log/statistics/safOpen/safClose }` | JNI 回调 | **必要**（与 AAR 自带规则等价，重复是双保险） |
| `-keepattributes *Annotation*, InnerClasses` | 注解/内部类属性 | 必要（R8 full mode 只保留被 keep 规则命中的注解） |
| `-dontnote kotlinx.serialization.**` | 减少噪音 | 无害 |
| `-keepclassmembers class **$$serializer { *; }` | 生成的 serializer | 老式写法；与 1.9.0 consumer 规则部分重叠（后者只保 `descriptor`） |
| `-keepclasseswithmembers class ** { KSerializer serializer(...); }` | companion 的 `serializer()` | **必要**（Navigation `toRoute<T>()` 通过反射解析 route serializer） |
| `-keep class * extends androidx.room.RoomDatabase { *; }` | Room DB / `_Impl` | 有效但过宽；Room 自带规则只 keep `<init>()` |

> 本任务在该文件末尾追加了 4 条防御性规则（见 §5.2），追加后共 48 行；因 `isMinifyEnabled=false`，
> 追加内容对当前 debug/release 构建**没有任何影响**。

### 2.4 CI / 发布

- `.github/workflows/ci.yml`：`lintDebug testDebugUnitTest assembleDebug` + `scripts/verify-apk.sh`（仅 debug APK）+ 上传 Room schema/单测报告。**没有 release 构建**。
- `.github/workflows/release.yml`：tag 触发；解码 keystore → `assembleRelease` → `verify-apk.sh` → 改名 + sha256 → GitHub Release。**没有上传 mapping**。
- `lint { abortOnError = true; checkReleaseBuilds = true }` → `assembleRelease` 会附带跑 `lintVitalRelease`，开启 R8 后这条路径也会首次被日常验证。

---

## 3. 依赖自带的 consumer 规则（实测，AGP 9 + R8 full mode）

从本地 Gradle 缓存（`/tmp/gradle-home/caches/modules-2/files-2.1`）解开各 AAR/JAR 的 `proguard.txt` /
`META-INF/proguard/*.pro` 核对，结论如下（**这些规则在 `isMinifyEnabled=true` 时自动生效，不占用我们的文件**）：

| 依赖 | 版本 | 自带规则要点 | 对本项目意味着 |
|---|---|---|---|
| Room | 2.8.5 | `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`；`-dontwarn androidx.room.paging.**`、`LiveData` | `AppDatabase` 与生成的 `AppDatabase_Impl`（也是 `RoomDatabase` 子类）类名 + 构造器被保留，反射 `Class.forName("<db>_Impl")` 安全 |
| WorkManager | 2.12.0 | `-keepnames class * extends ListenableWorker`；`-keepclassmembers public class * extends ListenableWorker { public <init>(...); }`；keep `WorkerParameters` / `InputMerger` | Worker 类名与构造器保留；但 `-keepnames` **不阻止 shrink**，仍要求 Worker 在代码里可达（本项目 `OneTimeWorkRequestBuilder<GenerationWorker>()` 可达） |
| FFmpegKit | 8.1.9（`dev.ffmpegkit-maintained:ffmpeg-kit-audio`） | `-keep class ...FFmpegKitConfig { native …; log/statistics/safOpen/safClose }` + `AbiDetect` 的 native 方法 | JNI 回调与 native 方法名保留；**AAR 不 keep 整个包**（我们自己的宽规则是额外保险） |
| OkHttp | 5.5.1（`okhttp-android` AAR） | `-dontwarn okhttp3.internal.platform.**`、`org.conscrypt.**`、`org.bouncycastle.**`、`javax.annotation.**`、animal_sniffer | 可选安全提供者的 `Class.forName` 探测不会让构建失败 |
| AndroidX Startup | 1.2.0（由 OkHttp 的 manifest 引入） | `-keepnames class * extends androidx.startup.Initializer`；`-keep class * extends Initializer { <init>(); }`（并强制进 primary dex） | `okhttp3.internal.platform.PlatformInitializer`（OkHttp manifest 的 `InitializerProvider` meta-data 按**类名字符串**反射实例化）被保留 |
| kotlinx.serialization | 1.9.0（core JAR） | `META-INF/proguard/kotlinx-serialization-common.pro`：保留 `Companion` 字段、companion 的 `serializer(...)`、`INSTANCE.serializer()`、`RuntimeVisibleAnnotations`、`$$serializer.descriptor` | 我们手写的 serialization 规则基本冗余；**Navigation 的类型安全路由靠这套规则 + 注解保留** |
| Compose | runtime/ui 1.12.1（BOM 2026.09.00） | `assumenosideeffects ComposerKt.sourceInformation*`、`throw*Exception` keep、`ModifierNodeElement` keep 等 | Compose 已适配 R8 full mode，不要自己加 `-keep class androidx.compose.**` |
| Media3 | 1.11.1 | `media3-common`：大量 `-dontwarn`（Guava/checkerframework）；`media3-exoplayer`：`DefaultRenderersFactory`/`DefaultDownloaderFactory` 反射用的构造器 keep；`media3-ui`：有规则；**`media3-session` 没有 `proguard.txt`** | 见 §4.4：session 的组件靠 manifest（aapt）+ 静态引用，必须真机 smoke，不能靠库规则"兜底" |
| Navigation | 2.10.2 | `navigation-common`：`NavArgs.fromBundle`（Safe Args 用）+ `Navigator` 注解保留；`navigation-compose` 无规则 | 我们没用 Safe Args；route serializer 的 keep 由 serialization 规则负责 |

> 证据只用于判断"要不要自己写规则"，**不替代构建验证**；版本升级（尤其 ffmpeg fork、Media3、Compose BOM）
> 后必须重跑 §6。

---

## 4. 风险清单

### 4.1 总表

| # | 面 | 触发点 | 失败症状 | 概率 | 影响 | 处置 |
|---|---|---|---|---|---|---|
| R1 | 流程 | **CI 不构建 release** | 打 tag 时才崩；发布阻塞 | 高 | 高 | §6；建议 P6-09 前补 CI job |
| R2 | Room | `Room.databaseBuilder(ctx, AppDatabase::class.java, …)` 反射加载 `AppDatabase_Impl` | 启动即 `ClassNotFoundException` / `IllegalStateException: cannot find implementation` | 低 | 高 | 保留现有 `RoomDatabase` 子类 keep（§5.1） |
| R3 | kotlinx.serialization + Navigation | `composable<T>()` / `entry.toRoute<T>()`（`ui/navigation/AppNavHost.kt`） | 导航时 `SerializationException: Serializer for class … is not found` | 中 | 中 | 保留注解 + companion `serializer()`（已有）；smoke 覆盖深链 |
| R4 | Compose | 全部 UI；`ComponentPreview` 等 `@Preview` | 渲染崩溃/`NoSuchMethodError` | 低 | 高 | 不自己加规则；依赖库规则 + smoke |
| R5 | Media3 | `AudioForgeMediaSessionService`（manifest service）、`MediaController`、`DefaultMediaNotificationProvider` | 播放无声 / 通知消失 / 绑定失败 / 锁屏控件失效 | 中低 | 中 | 显式 keep service（§5.2）+ 播放 smoke |
| R6 | FFmpegKit | JNI：`FFmpegKitConfig.log/statistics/safOpen/safClose`；`executeAsync` 回调 | 渲染首段即失败：`UnsatisfiedLinkError` / 回调不触发 / session 永不完成 | 低中 | 高 | 保留 JNI 规则（已有）+ 真实渲染 smoke |
| R7 | WorkManager | `GenerationWorker` / `ProbeWorker` 按类名反射实例化；`jobs` 表里持久化的是**混淆前**类名 | `ClassNotFoundException` / Worker 构造失败 → 批次永远 queued | 低 | 高 | AAR 规则 + 显式 keep 构造器（§5.2）；smoke 覆盖"杀进程后恢复" |
| R8 | OkHttp + Startup | `PlatformInitializer` 由 manifest meta-data 类名反射创建；平台探测 `Class.forName` | 冷启动 `ClassNotFoundException`；所有网络请求 `NoClassDefFoundError` | 低 | 高 | 库规则 + `-dontwarn`（已有）；smoke 覆盖解析一章 |
| R9 | 反射/序列化字段 | `Charset.forName("GBK")`、`MessageDigest.getInstance("SHA-1")`、枚举 `wire` 字符串、`@SerialName`、JSON 列（`statsJson` 等） | 编码识别失败 / 缓存键变化 / LLM JSON 解析失败 | 极低 | 中 | 无需规则：都是平台实现或编译期字符串（§4.8） |
| R10 | 资源收缩 | `isShrinkResources` + 无动态资源查找 | 媒体通知小图标丢失（`Resources$NotFoundException`） | 低 | 低 | §6 smoke 播放通知 |
| R11 | 映射/可观测性 | mapping 未归档、`SourceFile/LineNumberTable` 被优化掉 | 线上崩溃无法定位 | 中 | 中 | keep 属性（§5.2）+ 归档 mapping（§6.4） |
| R12 | 调试差异 | debug 不压缩 | "本地好的，release 崩" | 中 | 中 | 每个 release 候选跑 §6；不要用 debug 结果背书 |

### 4.2 Room 实体 / DAO

- 实体（`data/db/Entities.kt`）与 DAO 由 KSP 生成**直接调用**代码，无反射；实体类名、字段名可以被混淆
  （Room 用 SQL 字符串里的**列名**，列名来自注解常量，与混淆无关）。
- 唯一的反射点是 `Room.databaseBuilder(...).build()` 用 `"<数据库类名>_Impl"` 找实现类：
  `AppDatabase_Impl` 必须**类名不变**且**构造器可用**。
- 现有规则 `-keep class * extends androidx.room.RoomDatabase { *; }` 同时命中 `AppDatabase` 与 `AppDatabase_Impl`
  （`_Impl` 也继承 `RoomDatabase`），因此安全；但它会把 `AppDatabase` 的所有成员都 keep，
  属于"宽但便宜"（4 个类、几十个方法）。
- 库自带规则 `-keep class * extends androidx.room.RoomDatabase { void <init>(); }` 其实已经够用；
  收窄到 `<init>()` 可省几百字节，不值得在 v1 冒"规则边界"风险。
- 结论：**无需新增规则**；不要为了"省体积"把这条删掉或改成只 keep `AppDatabase`（那样 `_Impl` 会被改名）。

### 4.3 kotlinx.serialization

- 使用点：`LlmModels`/`AnalyzeModels`/`VoicePool` 的 DTO（编译期直接调用 `serializer()`，最安全的那一类），
  以及 `ui/navigation/Route.kt`（**类型安全路由**）。
- 路由是唯一"运行时按类型找 serializer"的路径：`composable<Route.ProjectDetail>` / `entry.toRoute<…>()`
  在运行时用 `serializer(KType)` 解析；R8 full mode 下如果
  ① `@Serializable` 注解被剥掉，或 ② companion 的 `serializer()` 被改名/删除 → `SerializationException`。
- 1.9.0 的 consumer 规则已覆盖 ①（`RuntimeVisibleAnnotations`）与 ②（`serializer(...)` keep）；
  我们文件里的老式规则（`$$serializer`、`-keepclasseswithmembers`）与之重叠，**保留不冲突**。
- `@SerialName("response_format")` 等名字字符串在**生成的 serializer 字节码里**是字面量，混淆不影响。
- 结论：现有规则足够；**不要**为了"保险"加 `-keep @Serializable class ** { *; }`——那会显著削弱 shrink，
  而且掩盖真正的问题（注解被剥/companion 被改名）。

### 4.4 Compose / Media3

- **Compose**：runtime 1.12.1 自带 R8 full mode 适配规则（`assumenosideeffects` + 异常构造器 keep）；
  在 full mode 下不需要额外 keep。已知的"老规则"（`-keep class androidx.compose.runtime.**`）只会增大体积。
  `@Preview` 函数在 release 里是死代码，会被正常删除，不是风险。
- **Media3**：`media3-exoplayer`/`media3-common`/`media3-ui` 有规则，**`media3-session` 1.11.1 没有 `proguard.txt`**。
  session 的组件靠：
  1. manifest 里的 `AudioForgeMediaSessionService`（aapt 规则保留类名 + 默认构造器）；
  2. 代码里 `ComponentName(ctx, AudioForgeMediaSessionService::class.java)` 与 `SessionToken`（显式组件，运行时取名字，混淆无影响）；
  3. `DefaultMediaNotificationProvider` 的 `R.string.*`/`R.drawable.ic_stat_audioforge`（静态引用，资源 shrinker 会保留）。
  `media3-session` 的 AAR manifest 还会并进一个 `BluetoothValidationActivity`（exported、受 `BLUETOOTH_PRIVILEGED` 保护），
  由 aapt 规则保留，无需我们处理。
- 结论：Media3 的失败模式是"能启动但播放/通知异常"，必须真机播放 smoke（§6.3 第 6 项），不能只看启动。

### 4.5 FFmpegKit

- 真正的反射面是 **JNI 反向调用 Java**：`FFmpegKitConfig.log(long,int,byte[])`、`statistics(...)`、`safOpen/safClose`
  由 native 侧按名字回调。AAR 的 `proguard.txt` 已经 keep 了它们（含 `native <methods>`）与 `AbiDetect`。
- 我们的调用（`FFmpegKit.executeAsync`、`ReturnCode.isSuccess`、`session.output`）都是静态引用，R8 不会误删。
- 现有 `-keep class com.arthenica.ffmpegkit.** { *; }` 比 AAR 规则宽，**可以去掉**（§5.3），
  但它是社区 fork（`dev.ffmpegkit-maintained`）升级时的保险；v1 建议保留，等 P6-02 测完体积再决定。
- 结论：规则已足够；失败症状是"渲染第一段就异常/回调不来"，用真实渲染 smoke 抓。

### 4.6 WorkManager

- WorkManager 从 `WorkSpec.workerClassName`（DB 里的**字符串**）反射创建 Worker。混淆本身没问题
  （入队时写的就是当前 APK 的类名），但类必须**不被 shrink**且构造器**不被删**。
- 本项目两个 Worker 都有静态引用（`OneTimeWorkRequestBuilder<GenerationWorker>()` / `<ProbeWorker>()`）
  → 不会被 shrink；AAR 的 `-keepnames` + 构造器规则再兜一层。
- 更隐蔽的是**跨版本升级**：旧 APK 入队的 work 存的是旧类名；只要类名没被 R8 改（`-keepnames` 保证），
  升级后仍能恢复。若将来为了体积给 Worker 加 `allowobfuscation`，就会出现"升级后旧队列永远失败"。
- `SystemForegroundService` 来自库 manifest 合并（`tools:node="merge"`），不受我们规则影响。
- 结论：AAR 规则已覆盖；§5.2 追加一条显式构造器 keep 作为"未来改用字符串入队"的防御。必须 smoke
  "后台批次 → 杀进程 → 重开恢复"。

### 4.7 OkHttp

- OkHttp 5.5.1 在 Android 上通过 **androidx.startup** 的 `InitializationProvider` + manifest meta-data
  `okhttp3.internal.platform.PlatformInitializer` 初始化平台层（类名是**字符串**，反射实例化）。
  `startup-runtime` 1.2.0 的 consumer 规则会 keep 所有 `Initializer` 实现（名字 + 无参构造器 + primary dex），
  所以正常路径安全。
- 平台探测（Conscrypt/BouncyCastle/OpenJSSE）用 `Class.forName`，AAR 的 `-dontwarn` 保证构建期不报缺类。
- 结论：无需新增规则；失败症状是冷启动崩溃或所有网络请求失败，用"解析一章"smoke 覆盖。

### 4.8 反射 / 序列化字段（逐项排除）

| 位置 | 机制 | 混淆/收缩影响 | 结论 |
|---|---|---|---|
| `AppDatabase.create()` | `Class.forName("…AppDatabase_Impl")` | 类名必须保留 | §5.1 已覆盖 |
| `GenerationWorker` / `ProbeWorker` | WorkManager 按字符串类名实例化 | 类名 + 构造器 | §5.2 已覆盖 |
| `Route.*` | kotlinx `serializer(KType)` 反射解析 | 注解 + companion `serializer()` | §5.1 已覆盖 |
| `VoicePool.parse` / LLM DTO | 编译期 `serializer()`（reified 内联） | 无 | 安全 |
| `Charset.forName("GBK")` / `MessageDigest.getInstance("SHA-1")` | 平台 provider | 平台类不受 shrinker 影响 | 安全 |
| 枚举 `wire` / `status` 列 / `JobEntity.statsJson` | 编译期字符串常量 | 字符串不随混淆变化 | 安全 |
| `@SerialName` 字段名 | 生成 serializer 里的字面量 | 不受影响 | 安全 |
| `xx::class.java.simpleName`（多处错误文案） | 类名仅用于展示 | 可能显示混淆名（a/b/c） | **唯一可感知副作用**：错误提示里可能出现短类名；不影响功能，接受 |
| `SecureKeyStore`（`KeyStore.getInstance("AndroidKeyStore")`、别名 `secure_*`） | 平台 API + 字符串别名 | 无 | 安全；但 **keystore 别名不能变**，与 R8 无关 |

> `simpleName` 的副作用值得注意：`ExportManager`、`ImportViewModel` 等把 `e::class.java.simpleName` 拼进用户可见文案。
> 混淆后可能变成 `a`/`b`。这类文案本来就是兜底（正常路径有 `message`），不作处理；若要保留可读性，
> 只能改成固定中文文案（属于 UI 任务，不在 P6-01 范围）。

---

## 5. 建议 keep 规则

### 5.1 必选（缺了会真的坏）

```proguard
# JNI 回调：FFmpegKit 的 native 侧按名字回调这些方法（AAR 自带等价规则，重复是双保险）
-keepclassmembers class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long, double, double, double);
    int safOpen(int);
    int safClose(int);
}

# kotlinx.serialization 运行时按类型找 serializer（Navigation 类型安全路由依赖这条）
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 通过 "<db>_Impl" 反射加载实现类；子类 keep 同时保住 AppDatabase 与 AppDatabase_Impl 的类名+构造器
-keep class * extends androidx.room.RoomDatabase { *; }
```

说明：

- 前两组**已经在 `app/proguard-rules.pro` 里**，不要删。
- 第三组保留了 `AppDatabase` 的全部成员（过宽但便宜）；收窄到 `{ <init>(); }` 也能工作（Room 自带规则就是这么写的），
  但**不能**只 keep `AppDatabase` 而不 keep `_Impl`。

### 5.2 建议（低成本防御，可去掉但建议保留）

```proguard
# 崩溃栈可读性：保留行号/源文件名，配合 mapping.txt 才能 retrace 到具体行
-keepattributes SourceFile,LineNumberTable

# WorkManager 按类名反射创建 Worker（AAR 已 keepnames + 构造器；这里防将来改字符串入队）
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Manifest 里的 MediaSessionService：系统绑定/回调 onGetSession、onTaskRemoved（aapt 只保构造器）
-keep class com.estimatefaster.audioforge.playback.AudioForgeMediaSessionService {
    <init>();
    public *;
}

# 通知「停止」按钮的显式广播接收器：onReceive 由系统调用，代码里没有直接引用
-keepclassmembers class com.estimatefaster.audioforge.domain.jobs.GenerationNotificationsReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
```

| 规则 | 为什么需要 | 可否去掉 |
|---|---|---|
| `-keepattributes SourceFile,LineNumberTable` | 没有它，线上堆栈只有行号 0/无文件名，mapping 也救不回可读性 | 不建议；成本几十 KB |
| Worker 构造器 | AAR 规则已覆盖，属于未来保险 | 可去掉（当前无功能差异） |
| `AudioForgeMediaSessionService` | `media3-session` 无 consumer 规则；框架/Binder 回调的 override 不保证被 R8 保留 | 可去掉，但必须靠播放 smoke 兜底 |
| `GenerationNotificationsReceiver.onReceive` | 同上，aapt 只 keep 构造器 | 可去掉，但"停止"按钮可能静默失效 |

> 这四条已追加到 `app/proguard-rules.pro` 末尾（带注释的 `P6-01` 段），并注明**只在 minify 开启时生效**。

### 5.3 现有可收窄 / 可去掉的规则（记录，不在本次改）

| 规则 | 现状 | 可收窄为 | 何时做 |
|---|---|---|---|
| `-keep class com.arthenica.ffmpegkit.** { *; }` | 宽 | 删掉，只留 `FFmpegKitConfig` 回调 + `AbiDetect`（AAR 自带） | P6-02 测体积时；先在有真实渲染 smoke 的前提下 |
| `-keep class * extends androidx.room.RoomDatabase { *; }` | 宽 | `{ <init>(); }` | 同上；收益 < 1 KB，优先级低 |
| `-keepclassmembers class **$$serializer { *; }` | 与 1.9.0 consumer 规则部分重叠（后者只保 `descriptor`） | 是否可删需实测（先跑 `Route` 导航 smoke） | 升级 serialization 时顺手评估；不确定就保留 |
| `-dontnote kotlinx.serialization.**` | 噪音开关 | 可删（会多几行 note） | 无所谓 |

### 5.4 不要加的规则

| 不要写 | 原因 |
|---|---|
| `-keep class androidx.compose.** { *; }` | 库已适配 full mode；这会让 dex 变大且掩盖真实问题 |
| `-keep @kotlinx.serialization.Serializable class ** { *; }` | 会让所有 DTO 无法 shrink；当前规则已覆盖运行时查找 |
| `-keep class ** { *; }` / `-dontwarn **` | 等于关掉 shrink/诊断，问题会以更贵的方式暴露 |
| `-keep class * implements java.io.Serializable` | 项目不用 Java 序列化（已 grep 确认无 `Parcelable`/`Serializable` 字段） |
| `-keepattributes Signature` | 当前没有泛型反射；加了只增体积（若要上 kotlin-reflect 再评估） |

---

## 6. 验证方案

> 硬约束：本仓库**不做本地构建/CI**（AGENTS.md、`docs/progress.md` 2026-11-08 日志）。
> 下面的命令是给"执行 P6-01 开关提交"的人/agent 在 GitHub Actions 或获准环境里用的**流程定义**，
> 本次任务没有执行。凡涉及改 `app/build.gradle.kts` 的步骤都是**临时本地实验，禁止提交**。

### 6.1 实验流程（不改提交历史）

```bash
# 0) 基线（当前 minify=false），保存 APK 供体积对比
./gradlew :app:assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release-unsigned.apk /tmp/af-baseline-unsigned.apk

# 1) 临时打开 R8（只改这两行；验证完立刻还原，绝不提交）
sed -i 's/isMinifyEnabled = false/isMinifyEnabled = true/; s/isShrinkResources = false/isShrinkResources = true/' \
  app/build.gradle.kts
git diff --stat app/build.gradle.kts   # 必须只有这两行

# 2) 构建（AGP 9 会顺带跑 lintVitalRelease，因 checkReleaseBuilds=true）
./gradlew :app:assembleRelease --no-daemon --stacktrace
```

构建产物：

| 文件 | 用途 |
|---|---|
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 无 keystore 时的未签名产物 |
| `app/build/outputs/mapping/release/mapping.txt` | **必须归档**，retrace 用 |
| `app/build/outputs/mapping/release/seeds.txt` | R8 保留项，审计"为什么留下" |
| `app/build/outputs/mapping/release/usage.txt` | 被删项，审计"为什么没了"（可 grep `Worker`/`Route`） |
| `app/build/outputs/mapping/release/configuration.txt` | 实际生效的完整规则（含 AAR consumer 规则），排障用 |
| `app/build/outputs/mapping/release/resources.txt` | 资源收缩记录 |

### 6.2 签名与安装（无正式 keystore 时）

release 必须签名才能安装。用 **debug keystore** 签缩小后的 release APK，只验证"能不能跑"，**不发布**：

```bash
BT="$ANDROID_HOME/build-tools/37.0.0"
KS="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"   # 无 ANDROID_USER_HOME 时在 ~/.android

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out /tmp/af-r8-debugsigned.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

"$BT/apksigner" verify --verbose /tmp/af-r8-debugsigned.apk

# 注意：debug 变体 applicationId 带 .debug 后缀，release 是正式包名；
# 若机器上装过 CI 签名的 release（签名不同），必须先卸载：
adb uninstall com.estimatefaster.audioforge || true
adb install -r /tmp/af-r8-debugsigned.apk
```

> 另一种等价做法（同样只能本地临时改、不提交）：在 release 块里临时加
> `signingConfig = signingConfigs.getByName("debug")`。用 apksigner 外挂签名更干净，推荐前者。

### 6.3 真机 smoke 矩阵（按风险项覆盖）

| # | 场景 | 覆盖的风险 | 通过标准 |
|---|---|---|---|
| 1 | 冷启动 → 首页 | R4/R10/R12 + 全局 | 无崩溃；首页卡片与设置入口正常 |
| 2 | 进入项目详情 + 深链 `audioforge://project/<id>` | R3（route serializer）、R2 | 导航成功，参数正确 |
| 3 | 导入一本小说（SAF/URI） | 编码识别、FileProvider | 章节切分与上次一致 |
| 4 | 解析 1 章（真实 LLM） | R3/R8（OkHttp+Startup）、R2 | 片段落库，无 `SerializationException` |
| 5 | 渲染 1 章（真实 TTS + FFmpeg） | R6（JNI）、R2 | 音频文件生成；ffmpeg 日志正常；时长正确 |
| 6 | 播放章节 + 通知/锁屏控件 | R5（Media3）、R10（图标） | 有声音；媒体通知出现；锁屏可控 |
| 7 | 后台批次 → 通知「停止」 | R7（Worker 反射）、receiver keep | 批次执行；停止生效；job 收尾为 cancelled |
| 8 | 后台批次中杀进程 → 重开 | R7（类名/构造器） | 任务可恢复或明确失败，不永久卡 running |
| 9 | 导出/分享音频 | FileProvider | 分享面板出现，文件可播放 |
| 10 | 设置里保存/清除 API Key | DataStore + Keystore | Key 保存后解析可用；清除后失效 |
| 11 | 声音池/提示词加载 | assets + serialization | 选角/音色列表正常（≥63 类） |
| 12 | `adb logcat -b crash` 全量扫 | 漏网崩溃 | 空；且无 `ClassNotFoundException` / `NoSuchMethodError` / `SerializationException` / `UnsatisfiedLinkError` |

设备要求（§14.3）：至少 1 台真机（Honor 20/Android 10 或 Pixel/15+）跑 1–12；模拟器只作为补充。
说明：FFmpeg 的 ABI 只有 `arm64-v8a`/`x86_64`，模拟器必须是 x86_64 或 arm64 镜像。

### 6.4 mapping 保留与 retrace

- 每次 shrink 构建把 `mapping.txt`/`seeds.txt`/`usage.txt`/`configuration.txt`/`resources.txt`
  归档到 **仓库外**（如 CI artifact，或内部存储 `af-mapping/<versionCode>/`）。
- **不要提交进 git**：mapping 是代码结构信息、体积数百 KB~数 MB，且 `.gitignore` 未覆盖。
- 排障：

```bash
# 以本机 SDK 实际提供的 retrace 为准（cmdline-tools 的 bin/retrace 或 lib/retrace.jar）
"$ANDROID_HOME/cmdline-tools/latest/bin/retrace" \
  app/build/outputs/mapping/release/mapping.txt crash.log
```

- 归档要求写进 P6-09（`release.yml` 上传 mapping + checksums）；本次任务不改 workflow。

### 6.5 通过 / 回退判据

**通过**（全部满足才能提交开关）：

1. `assembleRelease` 成功；R8 无未归因的 `Missing class` 警告（每条 `-dontwarn` 必须有注释说明为什么安全）。
2. APK 体积相对基线**下降 ≥ 3%**（低于此值说明收益不足，记录原因再决定是否值得承担风险）。
3. §6.3 的 12 项全过，`logcat -b crash` 干净；三次冷启动无 ANR。
4. mapping 五件套已归档，且能用 retrace 还原一条人工构造的堆栈。
5. 开关提交后 `assembleDebug` 仍绿（debug 不受影响）。

**回退**（任一满足即回退，不硬撑）：

- 出现 2 个以上无法在 1 小时内定位的 shrink 崩溃；
- 渲染（FFmpeg）或后台批次（WorkManager）任一核心链路在真机不稳定；
- 体积收益 < 3% 且投入已超过 4 小时。

---

## 7. 体积 / 启动影响预估

> 以下为**估算**，必须用 §6.1 的基线/实验两次 `assembleRelease` 实测替换。
> 测算依据：v0.1.0 universal APK 60.5 MB（`docs/progress.md`）；FFmpeg `.so` 实测未压缩
> arm64 20.4 MB + x86_64 23.5 MB ≈ 44 MB；`res/` 84 KB、assets 36 KB；其余约 14–16 MB 为 dex + 元数据。

| 部分 | 现状（估） | R8 后（估） | 说明 |
|---|---|---|---|
| 原生库（FFmpeg） | ~44 MB | ~44 MB（不变） | R8 不动 `.so`；这部分要等 P6-02 ABI split（arm64 单包约省 20 MB） |
| dex | ~14–16 MB | −25% ~ −40% | shrink + optimize + 合并类；Compose/Media3 占大头，Room/serialization 的 DTO 大多仍被引用 |
| `resources.arsc` + `res/` | ~100 KB 级 | −10% ~ −40% | 本项目资源极少；无 PNG/XML 布局，收益只有几十 KB |
| assets | 36 KB | 不变 | shrinker 不管 assets |
| **APK 合计** | **~60 MB** | **−4 ~ −6.5 MB（−7% ~ −11%）** | 百分比看起来不大，因为原生库占比太高 |

启动影响（定性 + 量级）：

- **冷启动**：R8 optimize 会内联/合并大量小方法，dex 更小、类加载更少；同时 `BuildConfig.DEBUG` 折叠掉
  debug-only 的 Room 探针分支。预计 **0 ~ −5%**（Honor 20 类老设备更明显），不要承诺"大幅变快"。
- **安装/首次启动**：dex 变小会缩短 dex2oat 与安装时间，这部分收益比运行时更大。
- **dex 方法数**：开启 R8 后方法数下降，但本项目（Compose + Media3 + Room）大概率已在多 dex 区间，
  不改变多 dex 架构；收益是 dex 更小、加载更少，而不是"从多 dex 回到单 dex"。
- **包体感知**：用户看到的下载体积几乎只受原生库影响 → **别把 R8 当"瘦身方案"**；
  P6-02（ABI split）才是，v1.1 的 baseline profile 再谈启动优化。

---

## 8. 回滚策略

1. **一行回退**：把 `app/build.gradle.kts` 的两行改回
   `isMinifyEnabled = false` / `isShrinkResources = false` → 立即恢复当前行为。
   签名、包名、`versionCode` 不变，已安装用户无需迁移；`proguard-rules.pro` 的补充规则与本文保留（无副作用）。
2. **规则级回退**：先加窄 keep（`-keep class X { <init>(); }`）而不是 `{ *; }`；
   若确认是某个库的问题，优先在 `proguard-rules.pro` 里加最窄的规则并写明原因 + 验证方式。
3. **发布级回退**：切换到上一版 APK（同包名同签名，`adb install -r -d` 可降级；Google Play 不涉及，本项目只侧载）。
4. **不变量**：
   - 正式 release 只由 CI 签名构建；本地 debug 签名的 release 仅用于验证，绝不发布。
   - 每次 shrink 构建的 mapping 必须与 APK 同版本归档，否则回滚后旧崩溃不可读。
   - 回退不删规则文件、不删文档；规则与结论要留下，供下次开启时复用。

---

## 9. 后续任务 / 未决问题

| 项 | 说明 | 归属 |
|---|---|---|
| 开启 `isMinifyEnabled`/`isShrinkResources` | 按 §6 验证通过后提交（与本文分开，便于单独回退） | P6-01 后续提交 |
| release 构建进 CI | 建议 `assembleRelease`（未签名）+ 上传 mapping；否则 PR 阶段发现不了 shrink 崩溃 | P6-09 前置 |
| 上传 mapping 到 Release/Artifact | `release.yml` 增加 mapping 归档；本文 §6.4 已定义格式 | P6-09 |
| ABI split | R8 之后体积收益主要来源；与本文无冲突 | P6-02 |
| `simpleName` 文案 | 混淆后错误提示可能出现短类名；是否改成固定中文文案 | UI/文案任务 |
| 收窄 FFmpegKit 宽 keep、Room keep | 见 §5.3；需真实渲染 smoke 背书 | P6-02 评估 |
| `docs/progress.md` 勾选 | 本任务按约定不改 progress（避免与并行任务冲突） | 由汇总任务更新 |

未决问题：

- 社区 fork `dev.ffmpegkit-maintained:8.1.9` 的 AAR 规则是否与上游一致？本地缓存 AAR 只 keep
  `FFmpegKitConfig`/`AbiDetect`，**升级时需重新解包核对**。
- AGP 9.5.1 下 R8 的默认 `fullMode` 是否有变化？当前按 full mode 评估；若默认值改变，需重跑 §4 的反射面。
- `lintVitalRelease` 开启 R8 后是否会出现新的 `MissingClass` 类 lint 错误？以 §6.1 实跑为准。

---

## 10. 参考

- Android：Shrink, obfuscate, and optimize your app — <https://developer.android.com/build/shrink-code>
- Android：Keep attributes / mapping 与 retrace — <https://developer.android.com/build/shrink-code#keep-attributes>
- Navigation 类型安全路由（serializer 与 R8）— <https://developer.android.com/guide/navigation/design/type-safety>
- kotlinx.serialization R8/ProGuard 规则（1.9.0 起随 core JAR 发布）— <https://github.com/Kotlin/kotlinx.serialization>
- Room 与 R8（自动配置）— <https://developer.android.com/training/data-storage/room/prepare>
- Media3 / ExoPlayer 的 R8 说明 — <https://developer.android.com/media/media3>
- 项目内对照：`docs/android-execution-plan.md` §6.4、§6.10、§14.6、§15 P6-01；`docs/android-port-feasibility.md` 风险 R4。
