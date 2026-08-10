package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统计身份：按 API 配置实例 + provider + model 区分的最小身份。
 *
 * 同一 provider/model 配置在不同 API 配置实例下是不同的统计身份，避免不同价格或
 * 路由相互覆盖。旧 DataStore 累计数据不区分配置实例，其身份使用空 [configId]。
 * [identityId] 由 [com.ai.assistance.operit.data.stats.TokenStatIdentityResolver] 稳定生成。
 */
@Entity(
    tableName = "token_stat_identities",
    indices = [
        Index(value = ["configId", "provider", "model"], unique = true),
        Index(value = ["displayModelId"]),
    ],
)
data class TokenStatIdentityEntity(
    @PrimaryKey @ColumnInfo(name = "identityId") val identityId: String,
    @ColumnInfo(name = "configId") val configId: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "displayModelId") val displayModelId: String,
) {
    /** 兼容旧系统约定的 “provider:model” 复合标识（含空配置实例）。 */
    val providerModel: String
        get() = "$provider:$model"
}
