package com.timeagent.fish

/**
 * Failure categories from spec 07 §2.1. They describe the last start/run attempt and
 * never replace the authoritative history of the DSH session.
 */
enum class FishRuntimeFailure {
    ROOTFS_CORRUPT,
    STORAGE_INSUFFICIENT,
    PROCESS_EXIT,
    PROTOCOL_INCOMPATIBLE,
    NETWORK,
    INVALID_KEY,
    UNKNOWN;

    /** Terminal failures are not retried automatically: the same attempt would fail again. */
    val terminal: Boolean
        get() = this == ROOTFS_CORRUPT || this == STORAGE_INSUFFICIENT ||
            this == PROTOCOL_INCOMPATIBLE || this == INVALID_KEY

    val label: String
        get() = when (this) {
            ROOTFS_CORRUPT -> "运行环境损坏"
            STORAGE_INSUFFICIENT -> "存储空间不足"
            PROCESS_EXIT -> "运行进程退出"
            PROTOCOL_INCOMPATIBLE -> "协议不兼容"
            NETWORK -> "网络连接失败"
            INVALID_KEY -> "API Key 无效"
            UNKNOWN -> "未知错误"
        }
}

/** The single state machine shared by the runtime settings page, chat header and overlay. */
sealed interface FishRuntimeStatus {
    val ready: Boolean get() = this is Ready || this is Busy
    val active: Boolean get() = ready || this is Starting || this is Installing || this is Stopping
    val canStart: Boolean get() = this is Stopped || this is NotInstalled || this is Failed
    val needsKey: Boolean
        get() = this is NeedsKey || (this is Failed && reason == FishRuntimeFailure.INVALID_KEY)
    val unsupported: Boolean get() = this is UnsupportedDevice

    object NotInstalled : FishRuntimeStatus
    data class Installing(val stage: String = "", val progress: Float? = null) : FishRuntimeStatus
    object Stopped : FishRuntimeStatus
    object Starting : FishRuntimeStatus
    object Ready : FishRuntimeStatus
    object Busy : FishRuntimeStatus
    object Stopping : FishRuntimeStatus
    data class Failed(
        val reason: FishRuntimeFailure,
        val detail: String,
        val exitCode: Int? = null,
    ) : FishRuntimeStatus
    data class NeedsKey(val detail: String = "") : FishRuntimeStatus
    object UnsupportedDevice : FishRuntimeStatus
}

enum class FishRuntimeAction { INSTALL, START, RETRY, ENTER_KEY, STOP, VIEW_LOG }

val FishRuntimeStatus.primaryAction: FishRuntimeAction?
    get() = when (this) {
        FishRuntimeStatus.NotInstalled -> FishRuntimeAction.INSTALL
        FishRuntimeStatus.Stopped -> FishRuntimeAction.START
        is FishRuntimeStatus.Failed ->
            if (reason == FishRuntimeFailure.INVALID_KEY) FishRuntimeAction.ENTER_KEY else FishRuntimeAction.RETRY
        is FishRuntimeStatus.NeedsKey -> FishRuntimeAction.ENTER_KEY
        FishRuntimeStatus.Ready, FishRuntimeStatus.Busy -> FishRuntimeAction.STOP
        is FishRuntimeStatus.Installing -> FishRuntimeAction.VIEW_LOG
        FishRuntimeStatus.Starting -> FishRuntimeAction.VIEW_LOG
        FishRuntimeStatus.Stopping, FishRuntimeStatus.UnsupportedDevice -> null
    }

/**
 * Compatibility view consumed by the existing fish screens and [FishRuntimeService],
 * which only understand stage/progress/ready/error.
 */
data class FishRuntimeView(
    val stage: String,
    val progress: Float?,
    val ready: Boolean,
    val error: String?,
)

fun FishRuntimeStatus.toView(): FishRuntimeView = when (this) {
    FishRuntimeStatus.NotInstalled -> FishRuntimeView(
        stage = "运行环境尚未安装",
        progress = null,
        ready = false,
        error = null,
    )
    is FishRuntimeStatus.Installing -> FishRuntimeView(
        stage = stage.ifBlank { "正在准备运行环境" },
        progress = progress,
        ready = false,
        error = null,
    )
    // FishRuntimeService treats this exact stage as "the process is gone".
    FishRuntimeStatus.Stopped -> FishRuntimeView("已停止", null, false, null)
    FishRuntimeStatus.Starting -> FishRuntimeView("启动中", null, false, null)
    FishRuntimeStatus.Ready -> FishRuntimeView("运行中", null, true, null)
    FishRuntimeStatus.Busy -> FishRuntimeView("运行中", null, true, null)
    FishRuntimeStatus.Stopping -> FishRuntimeView("停止中", null, false, null)
    is FishRuntimeStatus.Failed -> FishRuntimeView(
        stage = "启动失败",
        progress = null,
        ready = false,
        error = detail.ifBlank { reason.label },
    )
    is FishRuntimeStatus.NeedsKey -> FishRuntimeView(
        stage = "需要 API Key",
        progress = null,
        ready = false,
        error = detail.ifBlank { "请先在模型设置中填写 API Key" },
    )
    FishRuntimeStatus.UnsupportedDevice -> FishRuntimeView(
        stage = "此设备不支持内置角色",
        progress = null,
        ready = false,
        error = "内置角色运行环境目前只支持 arm64-v8a；全部本地业务仍可使用",
    )
}

/** Maps a raw runtime message onto the categories required by the spec. */
internal fun classifyRuntimeFailure(message: String?, exitCode: Int? = null): FishRuntimeFailure {
    val text = message.orEmpty().lowercase()
    return when {
        text.contains("enospc") || text.contains("no space") || text.contains("空间不足") ||
            text.contains("存储不足") || text.contains("disk full") -> FishRuntimeFailure.STORAGE_INSUFFICIENT
        text.contains("rootfs") || text.contains("安装标记") || text.contains("运行目录") ||
            text.contains("not_a_directory") || text.contains("asset_hash") -> FishRuntimeFailure.ROOTFS_CORRUPT
        text.contains("protocol") || text.contains("协议") || text.contains("dshversion") ||
            text.contains("sdk 版本") || text.contains("插件未注册") || text.contains("plugin") ->
            FishRuntimeFailure.PROTOCOL_INCOMPATIBLE
        text.contains("401") || text.contains("unauthorized") || text.contains("api key") ||
            text.contains("apikey") || text.contains("invalid key") || text.contains("余额") ||
            text.contains("auth") -> FishRuntimeFailure.INVALID_KEY
        text.contains("econn") || text.contains("network") || text.contains("网络") ||
            text.contains("timeout") || text.contains("超时") || text.contains("unreachable") ->
            FishRuntimeFailure.NETWORK
        text.contains("退出") || text.contains("exit") || exitCode != null ->
            FishRuntimeFailure.PROCESS_EXIT
        else -> FishRuntimeFailure.UNKNOWN
    }
}

internal fun exitCodeFrom(message: String?): Int? =
    Regex("code\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
