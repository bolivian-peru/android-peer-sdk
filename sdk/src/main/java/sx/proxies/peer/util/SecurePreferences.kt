package sx.proxies.peer.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed encrypted preferences for SDK credentials.
 *
 * Wraps androidx.security:security-crypto:EncryptedSharedPreferences with
 * an AES256_GCM MasterKey. Use this for anything sensitive: device
 * identifiers, refresh tokens, auto-linked API keys.
 *
 * On first run, migrates any pre-existing values from the legacy plain
 * "proxies_peer_sdk" SharedPreferences (SDK v1.1.3 and earlier).
 */
object SecurePreferences {

    private const val SECURE_PREFS_NAME = "proxies_peer_sdk_secure"
    private const val LEGACY_PREFS_NAME = "proxies_peer_sdk"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: build(context).also {
                instance = it
                migrateLegacy(context, it)
            }
        }
    }

    private fun build(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Move device_id (and any other future keys) from the legacy plain
     * SharedPreferences to encrypted storage, then clear the legacy file.
     * Idempotent — safe to call on every launch.
     */
    private fun migrateLegacy(context: Context, securePrefs: SharedPreferences) {
        try {
            val legacy = context.applicationContext.getSharedPreferences(
                LEGACY_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            val legacyAll = legacy.all
            if (legacyAll.isEmpty()) return

            val editor = securePrefs.edit()
            var migratedCount = 0
            for ((key, value) in legacyAll) {
                // Only migrate if not already present in secure store. This
                // means if you encrypt-then-rewrite a key the secure value
                // wins (legacy is the source of truth only on first migrate).
                if (securePrefs.contains(key)) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                migratedCount++
            }
            editor.apply()

            legacy.edit().clear().apply()
            Log.i(TAG, "Migrated $migratedCount key(s) from legacy plain prefs to encrypted store")
        } catch (e: Throwable) {
            // Migration failure must never block SDK startup. Log + carry
            // on — the user will simply re-register the device.
            Log.w(TAG, "Legacy prefs migration failed: ${e.message}")
        }
    }
}
