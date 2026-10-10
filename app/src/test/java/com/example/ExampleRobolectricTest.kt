package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.WhatsAppLinkRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voice Assist", appName)
    }

    @Test
    fun `android bridge safely copies pairing code to clipboard manager`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = com.example.util.AndroidBridge(context)
        val pairingCode = "729401"

        com.example.util.AndroidBridge.copyToSystemClipboard(context, pairingCode, "otp")

        val clipboardText = bridge.getClipboardText()
        assertEquals(pairingCode, clipboardText)
    }

    @Test
    fun `theme provider toggles and persists light and dark modes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themeProvider = com.example.ui.theme.ThemeProvider.getInstance(context)

        themeProvider.setThemeMode(com.example.ui.theme.ThemeMode.LIGHT)
        assertEquals(com.example.ui.theme.ThemeMode.LIGHT, themeProvider.themeMode.value)

        themeProvider.toggleTheme(isCurrentlyDark = false)
        assertEquals(com.example.ui.theme.ThemeMode.DARK, themeProvider.themeMode.value)

        themeProvider.toggleTheme(isCurrentlyDark = true)
        assertEquals(com.example.ui.theme.ThemeMode.LIGHT, themeProvider.themeMode.value)

        themeProvider.setThemeMode(com.example.ui.theme.ThemeMode.SYSTEM)
        assertEquals(com.example.ui.theme.ThemeMode.SYSTEM, themeProvider.themeMode.value)

        themeProvider.setHighContrast(true)
        assertTrue(themeProvider.highContrast.value)
        themeProvider.setHighContrast(false)
        assertFalse(themeProvider.highContrast.value)
    }

    @Test
    fun `main view model exposes and updates theme mode successfully`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = com.example.ui.MainViewModel(application)

        viewModel.setThemeMode(com.example.ui.theme.ThemeMode.DARK)
        assertEquals(com.example.ui.theme.ThemeMode.DARK, viewModel.themeMode.value)

        viewModel.setThemeMode(com.example.ui.theme.ThemeMode.LIGHT)
        assertEquals(com.example.ui.theme.ThemeMode.LIGHT, viewModel.themeMode.value)

        viewModel.setHighContrast(true)
        assertTrue(viewModel.highContrast.value)
    }

    @Test
    fun `whatsapp link records are persisted to room database`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val dao = db.whatsAppLinkRecordDao()

        dao.clearAll()

        val record = WhatsAppLinkRecordEntity(
            pairingCode = "AGH86HJ2",
            phoneNumber = "+8801700000000",
            status = "STARTED",
            details = "Robolectric test pairing started"
        )
        val id = dao.insertRecord(record)
        assertTrue(id > 0)

        val list = dao.getAllRecordsFlow().first()
        assertTrue(list.isNotEmpty())
        assertEquals("AGH86HJ2", list.first().pairingCode)
        assertEquals("+8801700000000", list.first().phoneNumber)
    }

    @Test
    fun `whatsapp accessibility helper checks and launch fallback work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isInstalled = com.example.util.AccessibilityHelper.isWhatsAppInstalled(context)
        assertFalse(isInstalled)

        val launched = com.example.util.AccessibilityHelper.launchWhatsApp(context, isLinkedDevices = false)
        assertTrue(launched)
    }

    @Test
    fun `whatsapp voice guidance trigger and speech announcement`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val voiceManager = com.example.util.VoiceGuidanceManager.getInstance(application)

        voiceManager.speak("দৃষ্টি প্রতিবন্ধী ভাইদের জন্য হোয়াটসঅ্যাপ লিঙ্কড সহায়তা চালু করা হয়েছে।", isBengali = true, force = true)

        val lastSpoken = voiceManager.lastSpokenText.value
        assertNotNull(lastSpoken)
        assertTrue(lastSpoken!!.contains("সহায়তা") || lastSpoken.contains("হোয়াটসঅ্যাপ"))

        voiceManager.enableAudibleSetupGuidanceMode()
        assertFalse(voiceManager.isSilentModeActive.value)

        val viewModel = com.example.ui.MainViewModel(application)
        viewModel.speakStepInstructionAloud(1)
        assertTrue(voiceManager.lastSpokenText.value.orEmpty().contains("ধাপ ১"))

        voiceManager.enterPostSetupSilentMode()
        assertTrue(voiceManager.isSilentModeActive.value)
    }

    @Test
    fun `whatsapp auto link manager starts workflow and copies code strictly once`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val autoLinkManager = com.example.util.WhatsAppAutoLinkManager.getInstance(application)
        val bridge = com.example.util.AndroidBridge(application)

        com.example.service.QuickAccessibilityService.updatePowerState(true, "/power on")
        autoLinkManager.startWorkflow(
            code = "ag-h86h-j2",
            phoneNumber = "দৃষ্টি প্রতিবন্ধী ভাই",
            autoLaunchWhatsApp = false
        )

        assertTrue(autoLinkManager.isAutomationRunning.value)
        assertEquals("AGH86HJ2", autoLinkManager.getActivePairingCode())
        assertEquals("AGH86HJ2", bridge.getClipboardText())

        autoLinkManager.stopAutomation()
        assertFalse(autoLinkManager.isAutomationRunning.value)
    }

    @Test
    fun `accessibility vibration settings customize and trigger event patterns`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = com.example.ui.MainViewModel(application)
        val vibManager = com.example.util.VibrationFeedbackManager.getInstance(application)

        viewModel.resetVibrationDefaults()
        assertTrue(viewModel.vibrationConfig.value.masterEnabled)

        viewModel.setVibrationEventPreset(
            com.example.util.VibrationEvent.PAIRING_SUCCESS,
            com.example.util.HapticPatternPreset.TRIPLE_BURST
        )
        viewModel.setVibrationEventPreset(
            com.example.util.VibrationEvent.BUTTON_TOGGLE,
            com.example.util.HapticPatternPreset.DOUBLE_TAP
        )
        viewModel.setVibrationIntensity(com.example.util.VibrationIntensity.MEDIUM)

        val cfg = viewModel.vibrationConfig.value
        assertEquals(com.example.util.HapticPatternPreset.TRIPLE_BURST, cfg.pairingSuccessPreset)
        assertEquals(com.example.util.HapticPatternPreset.DOUBLE_TAP, cfg.buttonTogglePreset)
        assertEquals(com.example.util.VibrationIntensity.MEDIUM, cfg.intensity)

        viewModel.testCustomVibrationPattern("80, 40, 160")
        assertEquals("80, 40, 160", viewModel.vibrationConfig.value.customPatternCsv)

        val triggered = vibManager.triggerEvent(com.example.util.VibrationEvent.PAIRING_SUCCESS, force = true)
        assertTrue(triggered)
    }

    @Test
    fun `each distinct device installation sends database notification and registers as separate user`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val installManager = com.example.util.DeviceInstallationSyncManager.getInstance(application)
        val db = AppDatabase.getDatabase(application)
        val deviceDao = db.deviceUserDao()

        val primaryIdentity = installManager.syncDeviceInstallationSuspend(forceNotify = true)
        assertTrue(primaryIdentity.deviceId.startsWith("DEV-"))
        assertTrue(primaryIdentity.serverUserId.startsWith("USR-"))
        assertTrue(primaryIdentity.isInstallNotificationSent)

        val secondDevice = installManager.registerDistinctDeviceInDatabase(
            customDeviceId = "DEV-PIXEL99A",
            customDeviceModel = "Pixel 9 Pro",
            customManufacturer = "Google"
        )
        val thirdDevice = installManager.registerDistinctDeviceInDatabase(
            customDeviceId = "DEV-GALAXYS24",
            customDeviceModel = "Galaxy S24",
            customManufacturer = "Samsung"
        )

        assertEquals("USR-PIXEL99A", secondDevice.serverUserId)
        assertEquals("USR-GALAXYS2", thirdDevice.serverUserId)
        assertFalse(secondDevice.serverUserId == thirdDevice.serverUserId)

        val allDeviceUsers = deviceDao.getAllDeviceUsersFlow().first()
        assertTrue(allDeviceUsers.size >= 3)
        assertTrue(allDeviceUsers.any { it.deviceId == "DEV-PIXEL99A" && it.serverUserId == "USR-PIXEL99A" })
        assertTrue(allDeviceUsers.any { it.deviceId == "DEV-GALAXYS24" && it.serverUserId == "USR-GALAXYS2" })
    }

    @Test
    fun `fresh open database deletes login number hassle and allows all visually impaired users to send signals`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val nodeClient = com.example.data.NodeServerClient.getInstance(application)
        val installManager = com.example.util.DeviceInstallationSyncManager.getInstance(application)

        assertTrue(com.example.data.NodeServerClient.PRE_SAVED_TWO_DIGIT_CHAT_ID_MAPPINGS.isEmpty())
        assertTrue(com.example.data.NodeServerClient.ALLOWED_TWO_DIGIT_LOGIN_NUMBERS.isEmpty())

        assertTrue(nodeClient.isChannelLoggedIn())
        assertTrue(nodeClient.getLoggedInChannelId().isNotBlank())

        val currentIdentity = installManager.syncDeviceInstallationSuspend(forceNotify = true)
        assertTrue(currentIdentity.isChannelLoggedIn)
        assertTrue(currentIdentity.deviceId.startsWith("DEV-"))
        assertTrue(currentIdentity.serverUserId.startsWith("USR-"))
    }

    @Test
    fun `dual mode phone number login connects both user view and database control panel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.ui.PhoneNumberSessionManager.clearPhoneSession(context)
        assertEquals("", com.example.ui.PhoneNumberSessionManager.getActivePhone(context))

        val loginRes = com.example.ui.PhoneNumberSessionManager.loginWithPhone(context, "০১৩১১৯৯৮৫২৭")
        assertTrue(loginRes.isSuccess)
        assertEquals("01311998527", loginRes.getOrNull())
        assertEquals("01311998527", com.example.ui.PhoneNumberSessionManager.loggedInPhone.value)

        // Switching or re-logging in from the Settings Database Control Panel updates the shared session immediately
        val secondRes = com.example.ui.PhoneNumberSessionManager.loginWithPhone(context, "01712345678")
        assertTrue(secondRes.isSuccess)
        assertEquals("01712345678", com.example.ui.PhoneNumberSessionManager.getActivePhone(context))
    }
}
