package com.ai.assistance.operit.data.db

internal object OrbitProductSchema {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS orbit_notifications (orbitId TEXT NOT NULL PRIMARY KEY,
            quietEnabled INTEGER NOT NULL, quietStart INTEGER NOT NULL, quietEnd INTEGER NOT NULL,
            intervalMinutes INTEGER NOT NULL, digestMinute INTEGER NOT NULL, lastDeliveredAt INTEGER NOT NULL)""",
        "ALTER TABLE orbit_tasks ADD COLUMN scheduleMode TEXT NOT NULL DEFAULT 'INTERVAL'",
        "ALTER TABLE orbit_tasks ADD COLUMN goalId TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE orbit_tasks ADD COLUMN triggerTaskId TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE orbit_tasks ADD COLUMN sourceArtifactVersionId TEXT NOT NULL DEFAULT ''",
        "UPDATE orbit_tasks SET scheduleMode='ONCE' WHERE kind='ONCE'",
        "UPDATE orbit_tasks SET scheduleMode='DAILY' WHERE kind='DAILY'",
        "ALTER TABLE orbit_events ADD COLUMN sourceRunId TEXT NOT NULL DEFAULT ''",
        """CREATE TABLE IF NOT EXISTS orbit_goals (id TEXT NOT NULL PRIMARY KEY, orbitId TEXT NOT NULL,
            title TEXT NOT NULL, plan TEXT NOT NULL, state TEXT NOT NULL, version INTEGER NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_goals_orbitId ON orbit_goals(orbitId)",
        """CREATE TABLE IF NOT EXISTS orbit_artifact_versions (id TEXT NOT NULL PRIMARY KEY,
            artifactId TEXT NOT NULL, orbitId TEXT NOT NULL, taskId TEXT NOT NULL, runId TEXT NOT NULL,
            actionId TEXT NOT NULL, name TEXT NOT NULL, relativePath TEXT NOT NULL, sha256 TEXT NOT NULL,
            byteCount INTEGER NOT NULL, createdAt INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS index_orbit_artifact_versions_orbitId ON orbit_artifact_versions(orbitId)",
        "CREATE INDEX IF NOT EXISTS index_orbit_artifact_versions_artifactId ON orbit_artifact_versions(artifactId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_orbit_artifact_versions_actionId ON orbit_artifact_versions(actionId)",
    )
}
