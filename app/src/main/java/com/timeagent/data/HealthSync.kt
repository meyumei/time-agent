package com.timeagent.data

/** Normalized health record read from an external source (Health Connect or a Gadgetbridge export). */
enum class HealthSampleKind { SLEEP, EXERCISE }

data class HealthSample(
    val uid: String, val kind: HealthSampleKind, val title: String,
    val startAt: Long, val endAt: Long, val originPackage: String,
    val detail: String = "",
) {
    /** Deterministic record id makes a lost sync row re-link instead of duplicating. */
    fun recordId(): String = "health:${kind.name.lowercase()}:$uid"
}

data class HealthSyncOutcome(val inserted: Int, val updated: Int, val protected: Int, val unchanged: Int, val deduped: Int = 0, val sourceMessages: List<String> = emptyList()) {
    fun summary(): String = (listOf("新增 $inserted · 跟随更新 $updated · 手工保护 $protected · 无变化 $unchanged · 去重 $deduped") + sourceMessages).joinToString("\n")
}

/** Friendly label for the app a sample came from; also used for sleep source preference matching. */
fun healthOriginLabel(packageName: String): String = when {
    packageName.isBlank() -> "未知来源"
    packageName.contains("mi.health") || packageName.contains("xiaomi") -> "小米运动健康"
    packageName.contains("huawei") -> "华为运动健康"
    packageName.contains("gotokeep") || packageName.contains("keep") -> "Keep"
    packageName.contains("trainnote") -> "训记"
    packageName.contains("freeyourgadget") || packageName.contains("gadgetbridge") -> "Gadgetbridge"
    packageName.contains("heytap") || packageName.contains("oppo") -> "OPPO 健康"
    packageName.contains("fitbit") -> "Fitbit"
    packageName.contains("garmin") -> "Garmin"
    packageName.contains("coros") -> "高驰"
    else -> packageName
}
