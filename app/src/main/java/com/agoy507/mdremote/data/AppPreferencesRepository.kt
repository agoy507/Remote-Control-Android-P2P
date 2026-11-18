package com.agoy507.mdremote.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agoy507.mdremote.model.DeviceIdentity
import com.agoy507.mdremote.model.ServerEndpointConfig
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.TrustedDeviceEntry
import com.agoy507.mdremote.model.normalizeDeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kendalip2p_prefs")

class AppPreferencesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    val identityFlow: Flow<DeviceIdentity> = context.dataStore.data.map { prefs ->
        val id = prefs[KEY_DEVICE_ID] ?: ""
        val alias = prefs[KEY_DEVICE_ALIAS] ?: defaultDeviceAlias()
        val pwd = prefs[KEY_SESSION_PASSWORD] ?: ""
        DeviceIdentity(deviceId = id, deviceAlias = alias, sessionPassword = pwd)
    }

    val serverConfigFlow: Flow<ServerEndpointConfig> = context.dataStore.data.map { prefs ->
        ServerEndpointConfig(
            signalingUrl = prefs[KEY_SIGNALING_URL] ?: ServerEndpointConfig.DEFAULT_SIGNALING_URL,
            serverApiToken = prefs[KEY_SERVER_TOKEN] ?: "",
        )
    }

    val requireVisualApprovalFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_REQUIRE_APPROVAL] ?: true
    }

    val allowTouchControlFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALLOW_TOUCH_CONTROL] ?: true
    }

    val qualityPresetFlow: Flow<StreamQualityPreset> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_QUALITY_PRESET]
        StreamQualityPreset.entries.firstOrNull { it.name == raw } ?: StreamQualityPreset.ULTRA_LOW_LATENCY
    }

    val recentDevicesFlow: Flow<List<TrustedDeviceEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_RECENT_DEVICES] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<TrustedDeviceEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun ensureInitialized(): DeviceIdentity {
        val current = identityFlow.first()
        if (current.deviceId.isNotBlank() && current.sessionPassword.isNotBlank()) {
            return current
        }
        val generatedId = if (current.deviceId.isBlank()) generateNineDigitId() else current.deviceId
        val generatedPwd = if (current.sessionPassword.isBlank()) generateSessionPassword() else current.sessionPassword
        val alias = current.deviceAlias.ifBlank { defaultDeviceAlias() }

        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ID] = generatedId
            prefs[KEY_DEVICE_ALIAS] = alias
            prefs[KEY_SESSION_PASSWORD] = generatedPwd
        }
        return DeviceIdentity(generatedId, alias, generatedPwd)
    }

    suspend fun regenerateSessionPassword(): String {
        val newPwd = generateSessionPassword()
        context.dataStore.edit { prefs ->
            prefs[KEY_SESSION_PASSWORD] = newPwd
        }
        return newPwd
    }

    suspend fun updateSessionPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.length >= 4) {
            context.dataStore.edit { prefs ->
                prefs[KEY_SESSION_PASSWORD] = trimmed
            }
        }
    }

    suspend fun updateDeviceAlias(alias: String) {
        val clean = alias.trim().take(32)
        if (clean.isNotEmpty()) {
            context.dataStore.edit { prefs ->
                prefs[KEY_DEVICE_ALIAS] = clean
            }
        }
    }

    suspend fun updateRequireVisualApproval(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REQUIRE_APPROVAL] = enabled
        }
    }

    suspend fun updateAllowTouchControl(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ALLOW_TOUCH_CONTROL] = enabled
        }
    }

    suspend fun updateQualityPreset(preset: StreamQualityPreset) {
        context.dataStore.edit { prefs ->
            prefs[KEY_QUALITY_PRESET] = preset.name
        }
    }

    suspend fun saveServerConfig(config: ServerEndpointConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SIGNALING_URL] = config.signalingUrl.trim()
            prefs[KEY_SERVER_TOKEN] = config.serverApiToken.trim()
        }
    }

    suspend fun recordRecentDevice(entry: TrustedDeviceEntry) {
        val cleanId = normalizeDeviceId(entry.deviceId)
        if (cleanId.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_DEVICES]?.let {
                runCatching { json.decodeFromString<List<TrustedDeviceEntry>>(it) }.getOrNull()
            }.orEmpty()
            val updated = (listOf(entry.copy(deviceId = cleanId)) +
                current.filterNot { normalizeDeviceId(it.deviceId) == cleanId })
                .take(8)
            prefs[KEY_RECENT_DEVICES] = json.encodeToString(updated)
        }
    }

    suspend fun removeRecentDevice(deviceId: String) {
        val cleanId = normalizeDeviceId(deviceId)
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_DEVICES]?.let {
                runCatching { json.decodeFromString<List<TrustedDeviceEntry>>(it) }.getOrNull()
            }.orEmpty()
            val updated = current.filterNot { normalizeDeviceId(it.deviceId) == cleanId }
            prefs[KEY_RECENT_DEVICES] = json.encodeToString(updated)
        }
    }

    private fun generateNineDigitId(): String {
        val num = 100_000_000 + random.nextInt(900_000_000)
        return num.toString()
    }

    private fun generateSessionPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(8) {
            repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun defaultDeviceAlias(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return model.take(24)
    }

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_ALIAS = stringPreferencesKey("device_alias")
        private val KEY_SESSION_PASSWORD = stringPreferencesKey("session_password")
        private val KEY_SIGNALING_URL = stringPreferencesKey("signaling_url")
        // KEY_STUN_URL & KEY_TURN_URL dipertahankan agar DataStore lama tidak corrupt
        // tapi tidak lagi digunakan untuk membangun ICE servers (server yang provide sekarang)
        private val KEY_SERVER_TOKEN = stringPreferencesKey("server_token")
        private val KEY_REQUIRE_APPROVAL = booleanPreferencesKey("require_visual_approval")
        private val KEY_ALLOW_TOUCH_CONTROL = booleanPreferencesKey("allow_touch_control")
        private val KEY_QUALITY_PRESET = stringPreferencesKey("quality_preset")
        private val KEY_RECENT_DEVICES = stringPreferencesKey("recent_devices")
    }
}
