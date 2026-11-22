package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manages agreement-related preferences for the application */
class AgreementPreferences(context: Context) {
    private val PREFS_NAME = "agreement_preferences"
    private val KEY_ACCEPTED_AGREEMENT_VERSION = "accepted_agreement_version"

    private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _agreementAcceptedFlow = MutableStateFlow(isAgreementAccepted())
    val agreementAcceptedFlow: StateFlow<Boolean> = _agreementAcceptedFlow.asStateFlow()

    /**
     * Whether the user has accepted the agreement. Accepting once is enough: a later version of the
     * agreement is not asked again.
     */
    fun isAgreementAccepted(): Boolean = hasAcceptedAnyAgreement()

    fun hasAcceptedAnyAgreement(): Boolean =
            !prefs.getString(KEY_ACCEPTED_AGREEMENT_VERSION, null).isNullOrBlank()

    /** Records acceptance of the agreement version bundled with this app release. */
    fun acceptCurrentAgreement() {
        prefs.edit()
            .putString(KEY_ACCEPTED_AGREEMENT_VERSION, CURRENT_AGREEMENT_VERSION)
            .apply()
        _agreementAcceptedFlow.value = true
    }

    companion object {
        /** The agreement version shown at first launch; recorded when the user accepts it. */
        const val CURRENT_AGREEMENT_VERSION = "2026-11-20"
    }
}
