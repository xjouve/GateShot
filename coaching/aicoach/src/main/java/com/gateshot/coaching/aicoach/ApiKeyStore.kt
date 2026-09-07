package com.gateshot.coaching.aicoach

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared storage for the user's Anthropic API key, used by both the Settings screen and the
 * coaching-analysis flow.
 *
 * NOTE: this project's version catalog does not currently include `androidx.security-crypto`,
 * so we fall back to plain [SharedPreferences]. If `androidx.security-crypto` is added to
 * `gradle/libs.versions.toml` later, swap [prefs] to build an `EncryptedSharedPreferences`
 * instance (AES256_SIV key scheme / AES256_GCM value scheme) instead.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "gateshot_config"
    private const val KEY_API_KEY = "anthropic_api_key"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY_API_KEY, null)

    fun set(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun isSet(context: Context): Boolean = !get(context).isNullOrBlank()
}
