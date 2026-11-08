package com.estimatefaster.audioforge.feature.projects

import com.estimatefaster.audioforge.domain.usecase.ImportNovelUseCase
import kotlin.math.roundToInt

/**
 * 导入 Sheet 的状态机阶段（P4-04 / 执行计划 §12.4 S03）。
 *
 * 正常路径：`Idle → Selected → Importing → Success`。
 * 分支：`Importing → NeedsEncoding →（选编码）Importing`；
 * `Importing → Duplicate`；任意确认前/导入失败 → `Error`（可重试或重选）。
 */
enum class ImportPhase {
    /** 还没选择文件。 */
    Idle,

    /** 文件已选中且通过确认前校验，等待用户点「导入」。 */
    Selected,

    /** 正在复制/解码/写库；进度由 [ImportUiState.progress] 表示。 */
    Importing,

    /** 导入成功；`novelId` 在 [ImportUiState.successNovelId]。 */
    Success,

    /** 自动识别编码失败，等待用户从 [ImportUiState.encodingCandidates] 选一个重试。 */
    NeedsEncoding,

    /** 同名项目已存在；等待用户「打开已有项目」或取消。 */
    Duplicate,

    /** 确认前校验失败或导入失败；具体原因在 [ImportUiState.error]。 */
    Error,
}

/**
 * 已选中的 SAF 源文件。
 *
 * [uri] 保存字符串而不是 `android.net.Uri`，让状态机与纯 JVM 测试都不依赖 Android；
 * 真正打开流由可注入的 [ImportSource] 完成。
 */
data class ImportFileSelection(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long?,
) {
    /** 已知且非空的大小才能算出确定的复制进度。 */
    val hasKnownSize: Boolean
        get() = sizeBytes != null && sizeBytes > 0L
}

/** 重名信息；[existingNovelId] 用于「打开已有项目」。 */
data class ImportDuplicate(
    val existingNovelId: Long,
    val title: String,
)

/**
 * 确认前校验失败的原因。
 *
 * 状态里只放枚举，用户文案由 UI 从 `strings.xml` 取，避免在 ViewModel 里写死中文。
 */
enum class ImportValidationError {
    NotTxt,
    EmptyFile,
    TooLarge,
    InvalidSize,
}

/**
 * 导入失败的类型化原因。
 *
 * [ImportFailed.message] 来自 [ImportNovelUseCase]（已是可读中文），其余类型由 UI
 * 映射到 `strings.xml`；这样领域层不需要知道资源 id。
 */
sealed interface ImportError {

    data class Validation(val reason: ImportValidationError) : ImportError

    /** 元数据读不到，或 `ContentResolver.openInputStream` 返回 null。 */
    data object SourceUnavailable : ImportError

    /** 用例返回的失败原因（文件损坏、写库失败等）。 */
    data class ImportFailed(val message: String) : ImportError
}

/**
 * 导入 Sheet 的不可变 UI 状态。
 *
 * - [progress] 始终收敛在 0..1；[percent] 派生值已处理 NaN/越界。
 * - [isProgressDeterminate] 为 false（SAF 未提供 `SIZE`）时，UI 用不确定进度条。
 * - [selection] 在 `Error` 状态仍保留，便于用户看到是哪个文件失败；校验失败时
 *   也可能保留，方便「重新选择」。
 */
data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val selection: ImportFileSelection? = null,
    val progress: Float = 0f,
    val encodingCandidates: List<String> = emptyList(),
    val selectedEncoding: String? = null,
    val duplicate: ImportDuplicate? = null,
    val successNovelId: Long? = null,
    val error: ImportError? = null,
) {
    val isImporting: Boolean
        get() = phase == ImportPhase.Importing

    /** SAF 未提供大小时无法计算百分比，进度条改为不确定态。 */
    val isProgressDeterminate: Boolean
        get() = selection?.hasKnownSize == true

    /** 0..1 的安全进度；NaN 和越界都收敛，可直接传给进度条。 */
    val safeProgress: Float
        get() = clampProgress01(progress)

    /** 0..100 的整数百分比（四舍五入）。 */
    val percent: Int
        get() = (safeProgress * 100f).roundToInt()

    /** `Selected` 且有文件时才允许启动导入。 */
    val canStartImport: Boolean
        get() = phase == ImportPhase.Selected && selection != null

    /** 用例失败/源打不开时可重试；确认前校验失败必须重新选文件。 */
    val canRetry: Boolean
        get() = phase == ImportPhase.Error &&
            selection != null &&
            (error is ImportError.ImportFailed || error is ImportError.SourceUnavailable)

    /** 编码失败后至少要有一个候选才能继续。 */
    val canChooseEncoding: Boolean
        get() = phase == ImportPhase.NeedsEncoding && encodingCandidates.isNotEmpty()
}

/**
 * 确认前校验（P4-04）：非 txt、空文件、超大文件都不调用用例。
 *
 * 大小未知（`null`）时放行：用例复制时会边读边按 64MB 上限限制。
 * 规则与 [ImportNovelUseCase] 保持同一份常量，避免 UI 与领域层阈值漂移。
 *
 * @return `null` 表示可以导入；否则是具体原因。
 */
fun validateImportSelection(
    displayName: String,
    sizeBytes: Long?,
): ImportValidationError? {
    val name = displayName.trim()
    if (!name.endsWith(ImportNovelUseCase.TXT_SUFFIX, ignoreCase = true)) {
        return ImportValidationError.NotTxt
    }
    if (sizeBytes != null) {
        when {
            sizeBytes < 0L -> return ImportValidationError.InvalidSize
            sizeBytes == 0L -> return ImportValidationError.EmptyFile
            sizeBytes > ImportNovelUseCase.MAX_IMPORT_BYTES -> return ImportValidationError.TooLarge
        }
    }
    return null
}

/** NaN/±Infinity 安全地收敛到 0..1。 */
private fun clampProgress01(value: Float): Float = when {
    value.isNaN() -> 0f
    value < 0f -> 0f
    value > 1f -> 1f
    else -> value
}
