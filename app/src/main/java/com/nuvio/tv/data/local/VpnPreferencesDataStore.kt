package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.security.SecureStringCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val cipher: SecureStringCipher
) {
    companion object {
        private const val FEATURE = "vpn_settings"
        private const val SHARED_PROFILE_ID = 1
    }

    // IPTV, plugins and VPN are shared by every profile: they always live in the primary
    // profile's store. Only the home catalog stays per profile.
    private val sharedProfileId: Flow<Int> = flowOf(SHARED_PROFILE_ID)

    private fun store() = factory.get(SHARED_PROFILE_ID, FEATURE)

    private val configKey = stringPreferencesKey("wireguard_config")
    private val autoConnectKey = booleanPreferencesKey("auto_connect_intent")

    // The raw config (including the WireGuard PrivateKey) is encrypted at rest with an
    // Android Keystore-backed key - see SecureStringCipher. decrypt() transparently
    // returns a pre-existing plaintext config unchanged, so upgrading doesn't lose it;
    // it gets encrypted the next time the user saves a config.
    val config: Flow<String> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { cipher.decrypt(it[configKey] ?: "") }
    }

    /** Whether the user's last explicit action was to turn the VPN on - not whether the
     *  last attempt actually succeeded, so a transient failure doesn't disable auto-connect
     *  on the next launch. Only an explicit disconnect clears it; a denied VPN permission
     *  prompt does not, so the next app startup asks for the system permission again. */
    val autoConnect: Flow<Boolean> = sharedProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[autoConnectKey] ?: false }
    }

    suspend fun setConfig(rawConfig: String) {
        store().edit { it[configKey] = cipher.encrypt(rawConfig.trim()) }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        store().edit { it[autoConnectKey] = enabled }
    }
}
