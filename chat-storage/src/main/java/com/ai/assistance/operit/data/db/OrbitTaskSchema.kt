package com.ai.assistance.operit.data.db

internal object OrbitTaskSchema {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS orbit_tasks (
            id TEXT NOT NULL PRIMARY KEY, orbitId TEXT NOT NULL, chatId TEXT NOT NULL,
            title TEXT NOT NULL, instruction TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL,
            intervalMinutes INTEGER NOT NULL, dailyMinute INTEGER NOT NULL, notifyPolicy TEXT NOT NULL,
            state TEXT NOT NULL, version INTEGER NOT NULL, nextRunAt INTEGER NOT NULL,
            lastObservation TEXT NOT NULL, lastFingerprint TEXT NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_tasks_orbitId ON orbit_tasks(orbitId)",
        """CREATE TABLE IF NOT EXISTS orbit_events (
            id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, taskVersion INTEGER NOT NULL,
            dueAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_events_taskId ON orbit_events(taskId)",
        """CREATE TABLE IF NOT EXISTS orbit_runs (
            id TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, taskId TEXT NOT NULL,
            taskVersion INTEGER NOT NULL, state TEXT NOT NULL, owner TEXT NOT NULL,
            generation INTEGER NOT NULL, bootId TEXT NOT NULL, leaseUntilElapsed INTEGER NOT NULL,
            leaseUntilWall INTEGER NOT NULL, sourceText TEXT NOT NULL, fingerprint TEXT NOT NULL,
            hasSource INTEGER NOT NULL, observation TEXT NOT NULL, error TEXT NOT NULL,
            startedAt INTEGER NOT NULL, finishedAt INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_orbit_runs_eventId ON orbit_runs(eventId)",
        "CREATE INDEX IF NOT EXISTS index_orbit_runs_taskId ON orbit_runs(taskId)",
        """CREATE TABLE IF NOT EXISTS orbit_deliveries (
            id TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, taskId TEXT NOT NULL,
            taskVersion INTEGER NOT NULL, orbitId TEXT NOT NULL, chatId TEXT NOT NULL,
            title TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL,
            state TEXT NOT NULL, messageId INTEGER, messageTimestamp INTEGER, notificationSent INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_orbit_deliveries_runId ON orbit_deliveries(runId)",
        "CREATE INDEX IF NOT EXISTS index_orbit_deliveries_taskId ON orbit_deliveries(taskId)",
    )
}
