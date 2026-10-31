package com.timeagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_memories")
data class PersonalMemoryEntity(
    @PrimaryKey val id: String, val kind: String, val text: String,
    val sourceType: String = "USER_INPUT", val sourceText: String = "", val sourceId: String? = null,
    val enabled: Boolean = true, val confirmed: Boolean = true,
    val validFrom: Long? = null, val validUntil: Long? = null,
    val ruleType: String = "NONE", val keyword: String = "", val weekday: Int? = null,
    val startMinute: Int? = null, val endMinute: Int? = null,
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0, val deletedAt: Long? = null,
)
