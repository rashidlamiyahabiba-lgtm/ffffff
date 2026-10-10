package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AccessibilitySignal
import com.example.data.DeviceProfile
import com.example.data.FirebaseSignalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SignalUiState<out T> {
    data object Loading : SignalUiState<Nothing>
    data class Success<T>(val data: T) : SignalUiState<T>
    data class Error(val message: String) : SignalUiState<Nothing>
}

class FirebaseSignalViewModel(
    private val repository: FirebaseSignalRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _lastActionBanner = MutableStateFlow<String?>(null)
    val lastActionBanner: StateFlow<String?> = _lastActionBanner.asStateFlow()

    private val _isSendingSignal = MutableStateFlow(false)
    val isSendingSignal: StateFlow<Boolean> = _isSendingSignal.asStateFlow()

    val targetDeviceProfile: StateFlow<DeviceProfile?> = repository
        .observeDeviceProfile(currentUserId)
        .catch { error ->
            Log.w(TAG, "Error observing target device profile", error)
            emit(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val signalsState: StateFlow<SignalUiState<List<AccessibilitySignal>>> = repository
        .observeSignals(currentUserId)
        .map<List<AccessibilitySignal>, SignalUiState<List<AccessibilitySignal>>> {
            SignalUiState.Success(it)
        }
        .catch { error ->
            Log.w(TAG, "Error observing Firebase signals", error)
            emit(SignalUiState.Error(error.localizedMessage ?: "Failed to load Firebase signals"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SignalUiState.Loading
        )

    fun syncUserDeviceWithFirebase(
        deviceId: String,
        deviceModel: String,
        isPowerOn: Boolean,
        isServiceEnabled: Boolean
    ) {
        viewModelScope.launch {
            repository.syncDeviceProfile(
                deviceId = deviceId,
                deviceModel = deviceModel,
                isPowerOn = isPowerOn,
                isServiceEnabled = isServiceEnabled
            )
        }
    }

    fun sendAccessibilitySignal(
        deviceId: String,
        signalType: String,
        command: String,
        messageBn: String,
        priority: String = "CRITICAL",
        isControlPanelRemoteDispatch: Boolean = false,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isSendingSignal.value = true
            val result = repository.sendSignal(
                deviceId = deviceId,
                signalType = signalType,
                command = command,
                messageBn = messageBn,
                priority = priority
            )
            _isSendingSignal.value = false
            result.fold(
                onSuccess = { signalId ->
                    val targetModel = targetDeviceProfile.value?.deviceModel?.takeIf { it.isNotBlank() } ?: "Connected Target Device"
                    val confirmMsg = "✅ Signal Dispatched ➔ $targetModel ($messageBn)"
                    _lastActionBanner.value = confirmMsg
                    if (!isControlPanelRemoteDispatch) {
                        repository.syncDeviceProfile(
                            deviceId = deviceId,
                            isPowerOn = signalType != "POWER_OFF",
                            isServiceEnabled = true,
                            lastSignalSummary = messageBn
                        )
                    }
                    onResult(true, signalId)
                },
                onFailure = { err ->
                    val errMsg = "⚠️ Signal failed: ${err.localizedMessage ?: "Please try again"}"
                    _lastActionBanner.value = errMsg
                    onResult(false, errMsg)
                }
            )
        }
    }

    fun markSignalExecuted(signalId: String, summaryBn: String) {
        viewModelScope.launch {
            repository.updateSignalStatus(
                signalId = signalId,
                newStatus = "EXECUTED",
                updatedMessageBn = summaryBn
            )
        }
    }

    private companion object {
        const val TAG = "FirebaseSignalVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
