package com.shilapi.governance.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Isolated persistence for vehicle process governance settings.
 *
 * Stored in `e01_governance_prefs.xml` so it remains completely independent
 * of CarPlay connection settings and pairing identities.
 */
class GovernancePersistence(context: Context) {
    companion object {
        const val PREFS_NAME = "e01_governance_prefs"
        const val KEY_NATP_SUPPRESSION_CONFIGURED = "natp_suppression_configured"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether voice suppression is configured as enabled by the driver.
     * Default is strictly false (Default OFF).
     */
    fun isSuppressionConfigured(): Boolean =
        prefs.getBoolean(KEY_NATP_SUPPRESSION_CONFIGURED, false)

    /**
     * Updates the persistent voice suppression configuration.
     */
    fun setSuppressionConfigured(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NATP_SUPPRESSION_CONFIGURED, enabled).apply()
    }
}
