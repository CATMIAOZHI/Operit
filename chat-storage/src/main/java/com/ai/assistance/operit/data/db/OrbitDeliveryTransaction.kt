package com.ai.assistance.operit.data.db

import com.ai.assistance.operit.data.model.OrbitDeliveryEntity

/** Call under the application's chat lock; the message and receipt are one SQLite commit. */
suspend fun AppDatabase.appendOrbitDelivery(deliveryId: String, now: Long): OrbitDeliveryEntity? =
    orbitTaskDao().appendDelivery(deliveryId, now, chatDao(), messageDao())
