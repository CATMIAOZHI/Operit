package com.ai.assistance.operit.core.tools.phone

/** The host, never tool parameters, supplies turn and conversation identities. */
internal class PhoneControlLease {
    data class Owner(val chat: String, val turn: String)
    data class Session(val id: String, val owner: Owner)
    private val turns = mutableSetOf<String>()
    private val stopped = mutableSetOf<String>()
    private var session: Session? = null
    private var observation: String? = null

    @Synchronized fun register(turn: String) { turns.add(turn) }

    @Synchronized fun begin(owner: Owner, id: String): Session {
        check(owner.turn in turns && owner.turn !in stopped) { "This turn cannot control the phone." }
        check(session == null) { "The phone is already controlled. Stop the existing session first." }
        return Session(id, owner).also { session = it; observation = null }
    }

    @Synchronized fun requireSession(owner: Owner, id: String): Session {
        val current = session
        check(current?.id == id && current.owner == owner && owner.turn in turns) {
            "Phone control session expired or belongs to another conversation."
        }
        return requireNotNull(current)
    }

    @Synchronized fun observed(owner: Owner, id: String, token: String) {
        requireSession(owner, id)
        observation = token
    }

    @Synchronized fun consume(owner: Owner, id: String, token: String) {
        requireSession(owner, id)
        check(token.isNotBlank() && observation == token) { "Observe the screen again before acting." }
        observation = null
    }

    @Synchronized fun stop(id: String? = session?.id): Session? {
        val current = session ?: return null
        if (current.id != id) return null
        stopped.add(current.owner.turn)
        session = null
        observation = null
        return current
    }

    @Synchronized fun finish(turn: String): Session? {
        turns.remove(turn)
        val ended = if (session?.owner?.turn == turn) stop() else null
        stopped.remove(turn)
        return ended
    }

    @Synchronized fun active(): Session? = session

    /** Startup failure is retryable; never undo a concurrent explicit stop. */
    @Synchronized fun abortStart(id: String): Session? {
        val current = session?.takeIf { it.id == id } ?: return null
        session = null
        observation = null
        return current
    }
}
