package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.FirebaseSignalRepository
import com.example.util.VibrationEvent
import kotlinx.coroutines.launch

/**
 * Dual-Mode Architecture (দ্বিমুখী ব্যবস্থাপনা):
 * 1. USER_CLEAN_MODE: Ultra-clean, zero-clutter interface for visually impaired users (TalkBack friendly).
 *    Contains only essential status, 1-tap Permission/WhatsApp start button, and Top-Right Settings Icon.
 * 2. DATABASE_CONTROL_PANEL_MODE: Opened by tapping the Settings Icon. Allows instant login with the
 *    same phone number (`PhoneNumberSessionManager`) to send real-time signals, pairing codes,
 *    power on/off, and control the service on the target phone.
 */
enum class DualAppMode {
    USER_CLEAN_MODE,
    DATABASE_CONTROL_PANEL_MODE
}

@Composable
fun AccessibilityScreen(
    viewModel: MainViewModel,
    currentUserId: String = "",
    userEmail: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isServiceEnabled = uiState.isServiceEnabled
    val deviceIdentity by viewModel.deviceUserIdentity.collectAsState()
    val loggedInPhone by PhoneNumberSessionManager.loggedInPhone.collectAsState()
    val preferredMode by PhoneNumberSessionManager.preferredMode.collectAsState()

    var currentMode by remember(preferredMode) { mutableStateOf(preferredMode) }

    val effectiveControlUserId = loggedInPhone.ifBlank { currentUserId }
    val effectiveControlLabel = if (loggedInPhone.isNotBlank()) {
        "Phone: $loggedInPhone"
    } else {
        userEmail
    }

    // Back button from Database Control Panel returns cleanly to User Clean Mode
    BackHandler(enabled = currentMode == DualAppMode.DATABASE_CONTROL_PANEL_MODE) {
        currentMode = DualAppMode.USER_CLEAN_MODE
        PhoneNumberSessionManager.setPreferredMode(context, DualAppMode.USER_CLEAN_MODE)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBatteryOptimizationStatus(context)
        viewModel.checkServerPowerNow()
        viewModel.announceInitialPermissionStepGuideIfNeeded(context)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("accessibility_screen"),
        containerColor = Color(0xFFF8FAFC)
    ) { paddingValues ->
        AnimatedContent(
            targetState = currentMode,
            label = "dual_mode_transition"
        ) { mode ->
            when (mode) {
                DualAppMode.USER_CLEAN_MODE -> {
                    // Register this phone as the Target Phone in Firestore when logged in
                    LaunchedEffect(effectiveControlUserId, deviceIdentity.deviceId, isServiceEnabled) {
                        if (effectiveControlUserId.isNotBlank()) {
                            try {
                                val repo = FirebaseSignalRepository(context, explicitIdentifier = effectiveControlUserId)
                                repo.syncDeviceProfile(
                                    deviceId = deviceIdentity.deviceId,
                                    deviceModel = "${deviceIdentity.manufacturer} ${deviceIdentity.deviceModel}".trim(),
                                    isPowerOn = true,
                                    isServiceEnabled = isServiceEnabled,
                                    lastSignalSummary = if (isServiceEnabled) {
                                        "Service active & ready"
                                    } else {
                                        "Logged in (awaiting setting)"
                                    },
                                    userIdOverride = effectiveControlUserId
                                )
                            } catch (_: Exception) {}
                        }
                    }

                    UserCleanSimpleView(
                        viewModel = viewModel,
                        isServiceEnabled = isServiceEnabled,
                        loggedInPhone = effectiveControlUserId,
                        paddingValues = paddingValues,
                        onOpenControlPanel = {
                            viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                            currentMode = DualAppMode.DATABASE_CONTROL_PANEL_MODE
                            PhoneNumberSessionManager.setPreferredMode(context, DualAppMode.DATABASE_CONTROL_PANEL_MODE)
                        }
                    )
                }

                DualAppMode.DATABASE_CONTROL_PANEL_MODE -> {
                    DatabaseControlPanelView(
                        viewModel = viewModel,
                        effectiveUserId = effectiveControlUserId,
                        effectiveUserLabel = effectiveControlLabel,
                        deviceId = deviceIdentity.deviceId,
                        deviceModel = "${deviceIdentity.manufacturer} ${deviceIdentity.deviceModel}".trim(),
                        isServiceEnabled = isServiceEnabled,
                        paddingValues = paddingValues,
                        onBackToUserMode = {
                            viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                            currentMode = DualAppMode.USER_CLEAN_MODE
                            PhoneNumberSessionManager.setPreferredMode(context, DualAppMode.USER_CLEAN_MODE)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UserCleanSimpleView(
    viewModel: MainViewModel,
    isServiceEnabled: Boolean,
    loggedInPhone: String,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    onOpenControlPanel: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFECFDF5),
                        Color(0xFFF8FAFC),
                        Color(0xFFF0FDFA)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==========================================
            // TOP BAR: Connected Phone Pill + Switch Phone Button
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFA7F3D0), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServiceEnabled) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Text(
                        text = if (loggedInPhone.isNotBlank()) "Phone: $loggedInPhone" else "Voice Assist",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF064E3B)
                    )
                }

                OutlinedButton(
                    onClick = {
                        viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                        PhoneNumberSessionManager.clearPhoneSession(context)
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF334155)
                    ),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("btn_logout_phone")
                ) {
                    Text(
                        text = "Logout",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ==========================================
            // CENTER MAIN CONTENT: Logo + Clean Settings Card
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .shadow(8.dp, CircleShape)
                        .testTag("profile_avatar_container"),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_drishti_voice_logo_1791076747778),
                        contentDescription = "Voice Assist Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(width = 3.5.dp, color = Color(0xFF10B981), shape = CircleShape)
                    )

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isServiceEnabled) Color(0xFF10B981) else Color(0xFFF59E0B))
                            .border(width = 2.5.dp, color = Color.White, shape = CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Voice Assist",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF064E3B)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF0D9488)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Verified",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 8.dp, shape = RoundedCornerShape(26.dp))
                        .border(1.5.dp, Color(0xFFA7F3D0), RoundedCornerShape(26.dp))
                        .testTag("card_connect_nusrat"),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 5.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isServiceEnabled) Color(0xFFECFDF5) else Color(0xFFFEF2F2))
                                .border(
                                    width = 1.2.dp,
                                    color = if (isServiceEnabled) Color(0xFF6EE7B7) else Color(0xFFFCA5A5),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    if (!isServiceEnabled) {
                                        viewModel.onUnifiedPermissionOrChatClicked(context)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("status_permission_box")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isServiceEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isServiceEnabled) Color(0xFF059669) else Color(0xFFDC2626),
                                    modifier = Modifier.size(22.dp)
                                )

                                Text(
                                    text = if (isServiceEnabled) {
                                        "Service Active ✓"
                                    } else {
                                        "Setting Off"
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isServiceEnabled) Color(0xFF065F46) else Color(0xFF991B1B)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                viewModel.onUnifiedPermissionOrChatClicked(context)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .testTag("btn_start_chat"),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF059669),
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 5.dp,
                                pressedElevation = 8.dp
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (isServiceEnabled) Icons.Default.CheckCircle else Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = if (isServiceEnabled) {
                                        "whatsapp"
                                    } else {
                                        "অনুমতি সেটিংস অন করুন"
                                    },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SAFE-DISTANCE SMALL DISCREET ICON AT VERY BOTTOM
            // (Only verified users know to tap this small icon for Control Panel)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onOpenControlPanel,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0).copy(alpha = 0.55f))
                        .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                        .testTag("btn_open_control_panel_settings")
                        .semantics {
                            contentDescription = "Control Panel Access"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Control Panel Access",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Mode 2: Database Control Panel (Clean Dark Neon Signal Hub)
 */
@Composable
private fun DatabaseControlPanelView(
    viewModel: MainViewModel,
    effectiveUserId: String,
    effectiveUserLabel: String,
    deviceId: String,
    deviceModel: String,
    isServiceEnabled: Boolean,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    onBackToUserMode: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isUnlocked by PhoneNumberSessionManager.isControlPanelUnlocked.collectAsState()
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    var phoneSwitchInput by remember(effectiveUserId) {
        mutableStateOf(if (effectiveUserId.all { it.isDigit() }) effectiveUserId else "")
    }
    var statusFeedback by remember { mutableStateOf<String?>(null) }

    val modernInputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF10B981),
        unfocusedBorderColor = Color(0xFF334155),
        focusedContainerColor = Color(0xFF0A0F1D),
        unfocusedContainerColor = Color(0xFF0F172A),
        focusedLabelColor = Color(0xFF6EE7B7),
        unfocusedLabelColor = Color(0xFF94A3B8),
        cursorColor = Color(0xFF10B981),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF050811),
                        Color(0xFF0B1120),
                        Color(0xFF070B14)
                    )
                )
            )
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .testTag("database_control_panel_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header Bar with Back to Clean User View + Lock Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBackToUserMode,
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF131B2E))
                        .border(1.dp, Color(0xFF10B981), CircleShape)
                        .testTag("btn_back_to_user_mode")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Home",
                        tint = Color(0xFF6EE7B7)
                    )
                }

                Column {
                    Text(
                        text = if (isUnlocked) "Control Panel ✓" else "🔒 Control Panel Locker",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    if (isUnlocked && effectiveUserId.isNotBlank()) {
                        Text(
                            text = "📱 $effectiveUserId",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isUnlocked) {
                    OutlinedButton(
                        onClick = {
                            viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                            PhoneNumberSessionManager.lockControlPanel(context)
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, Color(0xFFF59E0B)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1F160B),
                            contentColor = Color(0xFFFBBF24)
                        ),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("btn_lock_control_panel")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Control Panel",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LOCK",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }

                OutlinedButton(
                    onClick = onBackToUserMode,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, Color(0xFF10B981)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF062C22),
                        contentColor = Color(0xFF6EE7B7)
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text(
                        text = "HOME",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF6EE7B7)
                    )
                }
            }
        }

        if (!isUnlocked) {
            // =========================================================================
            // CONTROL PANEL PASSWORD LOCKER CARD (Requires `sudoaptinstall`)
            // =========================================================================
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .shadow(12.dp, RoundedCornerShape(26.dp))
                    .border(1.5.dp, Color(0xFF10B981), RoundedCornerShape(26.dp))
                    .testTag("control_panel_password_locker_card"),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF111827)),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF064E3B), Color(0xFF047857))
                                )
                            )
                            .border(2.dp, Color(0xFF10B981), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Control Panel Security Locker",
                            tint = Color(0xFF6EE7B7),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Text(
                        text = "Control Panel Locker",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it.trim()
                            passwordError = null
                        },
                        label = {
                            Text(
                                text = "Enter Password",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        placeholder = {
                            Text(
                                "Enter unlock password",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF64748B)
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF064E3B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Password",
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = modernInputColors,
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("control_panel_unlock_password_field")
                    )

                    if (!passwordError.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF311018))
                                .border(1.dp, Color(0xFFF43F5E), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = passwordError!!,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDA4AF),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("control_panel_unlock_error_text")
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val ok = PhoneNumberSessionManager.unlockControlPanelWithPassword(
                                context = context,
                                passwordInput = passwordInput
                            )
                            if (ok) {
                                passwordError = null
                                passwordInput = ""
                                viewModel.testVibrationEvent(VibrationEvent.PAIRING_SUCCESS)
                            } else {
                                viewModel.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                                passwordError = "❌ Invalid password!"
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 5.dp,
                            pressedElevation = 8.dp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("control_panel_unlock_submit_button")
                            .semantics {
                                contentDescription = "Unlock Control Panel with password"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🔓 UNLOCK PANEL",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }
        } else {
            // Compact Phone Number Switch Bar inside Unlocked Control Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = phoneSwitchInput,
                    onValueChange = { raw ->
                        phoneSwitchInput = raw.filter { it.isDigit() || it in '০'..'৯' || it == '+' || it == '-' || it == ' ' }.take(15)
                        statusFeedback = null
                    },
                    label = {
                        Text(
                            "Target Phone Number",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = modernInputColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("control_panel_phone_input")
                )

                Button(
                    onClick = {
                        val res = PhoneNumberSessionManager.loginWithPhone(
                            context = context,
                            rawPhone = phoneSwitchInput,
                            targetMode = DualAppMode.DATABASE_CONTROL_PANEL_MODE,
                            controlPanelPassword = PhoneNumberSessionManager.CONTROL_PANEL_PASSWORD
                        )
                        res.fold(
                            onSuccess = { cleanPhone ->
                                viewModel.testVibrationEvent(VibrationEvent.PAIRING_SUCCESS)
                                coroutineScope.launch {
                                    val repo = FirebaseSignalRepository(context, explicitIdentifier = cleanPhone)
                                    val profile = repo.getDeviceProfile(cleanPhone).getOrNull()
                                    statusFeedback = when {
                                        profile != null && profile.isServiceEnabled ->
                                            "🟢 $cleanPhone Connected (${profile.deviceModel}) ✓"
                                        profile != null ->
                                            "🟠 $cleanPhone Found (Permission Off)"
                                        else ->
                                            "🟡 $cleanPhone Connected ✓"
                                    }
                                }
                            },
                            onFailure = { err ->
                                statusFeedback = err.localizedMessage ?: "Enter a valid phone number"
                            }
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .testTag("control_panel_phone_connect_button")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CONNECT", fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                }
            }

            if (!statusFeedback.isNullOrBlank()) {
                Text(
                    text = statusFeedback!!,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF34D399),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Clean Firebase Signal Hub Pane + WhatsApp Linked Service Controls inside Control Panel ONLY
            if (effectiveUserId.isNotBlank()) {
                FirebaseSignalSection(
                    currentUserId = effectiveUserId,
                    userEmail = effectiveUserLabel,
                    deviceId = deviceId,
                    deviceModel = deviceModel,
                    isServiceEnabled = isServiceEnabled,
                    mainViewModel = viewModel,
                    isControlPanelMode = true
                )

                WhatsAppLinkedSection(
                    viewModel = viewModel,
                    isBn = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
    }
}

