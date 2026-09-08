package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 展示模型分组：默认把规范化后同名模型合并为一个展示模型，并允许用户设置手动别名。
 *
 * 合并只影响展示与聚合；每条事件仍按自己的身份与价格计算。
 * [displayModelId] 默认为规范化模型名；用户设置别名后 [displayName] 保存别名，
 * 其余身份仍通过 [displayModelId] 归属同一分组。
 */
@Entity(
    tableName = "token_stat_display_models",
    indices = [
        Index(value = ["normalizedModel"], unique = true),
    ],
)
data class TokenStatDisplayModelEntity(
    @PrimaryKey @ColumnInfo(name = "displayModelId") val displayModelId: String,
    @ColumnInfo(name = "normalizedModel") val normalizedModel: String,
    @ColumnInfo(name = "displayName") val displayName: String,
)
