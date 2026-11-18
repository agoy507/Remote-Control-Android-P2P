package com.agoy507.mdremote.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agoy507.mdremote.model.ControllerSessionState
import com.agoy507.mdremote.model.DeviceIdentity
import com.agoy507.mdremote.model.HostStatus
import com.agoy507.mdremote.model.IncomingConnectionRequest
import com.agoy507.mdremote.model.RemoteSystemAction
import com.agoy507.mdremote.model.ServerEndpointConfig
import com.agoy507.mdremote.model.ServerHealthResult
import com.agoy507.mdremote.model.SignalingStatus
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.TrustedDeviceEntry
import com.agoy507.mdremote.service.AccessibilityBridge
import com.agoy507.mdremote.service.RemoteSessionCoordinator
import com.agoy507.mdremote.webrtc.WebRtcRemoteManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator = RemoteSessionCoordinator.getInstance(application)
    private val prefs = coordinator.preferences

    val webRtcManager: WebRtcRemoteManager
        get() = coordinator.webRtc

    val identity: StateFlow<DeviceIdentity> = prefs.identityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceIdentity("", "", ""))

    val serverConfig: StateFlow<ServerEndpointConfig> = prefs.serverConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerEndpointConfig())

    val requireVisualApproval: StateFlow<Boolean> = prefs.requireVisualApprovalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val allowTouchControl: StateFlow<Boolean> = prefs.allowTouchControlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val qualityPreset: StateFlow<StreamQualityPreset> = prefs.qualityPresetFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StreamQualityPreset.ULTRA_LOW_LATENCY,
        )

    val recentDevices: StateFlow<List<TrustedDeviceEntry>> = prefs.recentDevicesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val signalingStatus: StateFlow<SignalingStatus> = coordinator.signalingStatus
    val hostStatus: StateFlow<HostStatus> = coordinator.hostStatus
    val incomingRequest: StateFlow<IncomingConnectionRequest?> = coordinator.incomingRequest
    val controllerState: StateFlow<ControllerSessionState> = coordinator.controllerState

    private val _accessibilityEnabled = MutableStateFlow(
        AccessibilityBridge.isAccessibilityEnabledInSettings(application),
    )
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _isCheckingServer = MutableStateFlow(false)
    val isCheckingServer: StateFlow<Boolean> = _isCheckingServer.asStateFlow()

    private val _serverHealthResult = MutableStateFlow<ServerHealthResult?>(null)
    val serverHealthResult: StateFlow<ServerHealthResult?> = _serverHealthResult.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.ensureInitialized()
        }
        viewModelScope.launch {
            AccessibilityBridge.isConnected.collect { connected ->
                _accessibilityEnabled.value =
                    connected || AccessibilityBridge.isAccessibilityEnabledInSettings(getApplication())
            }
        }
    }

    fun refreshAccessibilityStatus() {
        _accessibilityEnabled.value = AccessibilityBridge.isAccessibilityEnabledInSettings(getApplication())
    }

    fun regenerateSessionPassword() {
        viewModelScope.launch { prefs.regenerateSessionPassword() }
    }

    fun updateSessionPassword(password: String) {
        viewModelScope.launch { prefs.updateSessionPassword(password) }
    }

    fun updateDeviceAlias(alias: String) {
        viewModelScope.launch {
            prefs.updateDeviceAlias(alias)
            coordinator.reconnectSignaling()
        }
    }

    fun setRequireVisualApproval(enabled: Boolean) {
        viewModelScope.launch { prefs.updateRequireVisualApproval(enabled) }
    }

    fun setAllowTouchControl(enabled: Boolean) {
        viewModelScope.launch { prefs.updateAllowTouchControl(enabled) }
    }

    fun startHostMode(projectionIntent: Intent) {
        coordinator.startHostMode(projectionIntent)
    }

    fun stopHostMode() {
        coordinator.stopHostMode()
    }

    fun approveIncomingConnection() {
        coordinator.approveIncomingConnection()
    }

    fun rejectIncomingConnection() {
        coordinator.rejectIncomingConnection()
    }

    fun connectToTarget(targetId: String, sessionPassword: String) {
        coordinator.connectToTarget(targetId, sessionPassword)
    }

    fun disconnectControllerSession() {
        coordinator.disconnectControllerSession()
    }

    fun clearControllerError() {
        if (controllerState.value is ControllerSessionState.Error) {
            coordinator.disconnectControllerSession()
        }
    }

    fun sendTouchTap(xNorm: Float, yNorm: Float, isLongPress: Boolean = false) {
        coordinator.sendTouchTap(xNorm, yNorm, isLongPress)
    }

    fun sendTouchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 220L) {
        coordinator.sendTouchSwipe(startX, startY, endX, endY, durationMs)
    }

    fun sendSystemAction(action: RemoteSystemAction) {
        coordinator.sendSystemAction(action)
    }

    fun sendTextInput(text: String) {
        coordinator.sendTextInput(text)
    }

    fun changeStreamQuality(preset: StreamQualityPreset) {
        coordinator.changeStreamQuality(preset)
    }

    fun saveAndReconnectServer(config: ServerEndpointConfig) {
        viewModelScope.launch {
            prefs.saveServerConfig(config)
            coordinator.reconnectSignaling()
        }
    }

    fun testServerHealth(config: ServerEndpointConfig) {
        viewModelScope.launch {
            _isCheckingServer.value = true
            _serverHealthResult.value = coordinator.testServerConnection(config)
            _isCheckingServer.value = false
        }
    }

    fun reconnectSignaling() {
        coordinator.reconnectSignaling()
    }

    fun removeRecentDevice(deviceId: String) {
        viewModelScope.launch { prefs.removeRecentDevice(deviceId) }
    }
}
