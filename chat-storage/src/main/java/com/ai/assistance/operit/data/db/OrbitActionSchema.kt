package com.ai.assistance.operit.data.db

internal object OrbitActionSchema {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS orbit_actions (
            id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, runId TEXT NOT NULL,
            generation INTEGER NOT NULL, toolName TEXT NOT NULL, operationHash TEXT NOT NULL,
            state TEXT NOT NULL, summary TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_actions_taskId ON orbit_actions(taskId)",
        "CREATE INDEX IF NOT EXISTS index_orbit_actions_runId ON orbit_actions(runId)",
        """CREATE TABLE IF NOT EXISTS orbit_task_chats (
            chatId TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, generation INTEGER NOT NULL, rootChatId TEXT NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_task_chats_runId ON orbit_task_chats(runId)",
        """CREATE TABLE IF NOT EXISTS orbit_task_inputs (
            taskId TEXT NOT NULL PRIMARY KEY, taskVersion INTEGER NOT NULL, requestedUrl TEXT NOT NULL,
            sessionId TEXT NOT NULL, userNote TEXT NOT NULL, pageObservation TEXT NOT NULL)""",
    )
}
