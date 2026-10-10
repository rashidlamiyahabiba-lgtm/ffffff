# 🚀 Accessibility Node.js Database Server (দৃষ্টি সহায়িকা নোড সার্ভার ডাটাবেজ)

দৃষ্টি প্রতিবন্ধী ব্যক্তিদের জন্য অ্যান্ড্রয়েড অ্যাক্সেসিবিলিটি অ্যাপ্লিকেশনের সেন্ট্রাল ডাটাবেজ ব্যাকএন্ড। এটি Node.js এবং Express দিয়ে তৈরি এবং এতে অন্তর্নির্মিত স্বয়ংক্রিয় JSON/SQLite পারসিস্টেন্স ডাটাবেজ এবং ওয়েব ড্যাশবোর্ড রয়েছে।

---

## 📋 প্রধান সুবিধাসমূহ (Key Features)

1. **ইউজার রেজিস্ট্রেশন ও ডিভাইস বাইন্ডিং**: ফোন নম্বর ও অ্যান্ড্রয়েড ডিভাইস আইডির মাধ্যমে স্বয়ংক্রিয় প্রোফাইল তৈরি।
2. **৬-সংখ্যার ওটিপি ভেরিফিকেশন**: অন্ধ বা দৃষ্টিহীন ব্যবহারকারীদের নিরাপত্তা যাচাইয়ের জন্য সার্ভার-সাইড ভেরিফিকেশন কোড সিস্টেম (উন্নয়নের সুবিধার জন্য `123456` মাস্টার বাইপাস অনুমোদিত)।
3. **লাইভ সেটিংস ও স্ক্রিন ট্র্যাকিং সিঙ্ক**: টকবॅक মোড, অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয়তা এবং স্ক্রিনের উপস্থিতির তথ্য রিয়েল-টাইমে ডাটাবেজে সংরক্ষণ।
4. **অ্যাক্সেসিবিলিটি নেভিগেশন লগিং**: স্ক্রিন পরিদর্শনের রিয়েল-টাইম হিস্ট্রি সংরক্ষণ (`/api/logs/navigation`)।
5. **ইন্টারেক্টিভ অ্যাডমিন ড্যাশবোর্ড**: ব্রাউজারে `http://localhost:3000` খুললেই ইউজার লিস্ট, ভেরিফিকেশন কোড ও লাইভ স্ট্যাটাস দেখার জন্য সুন্দর ওয়েব প্যানেল।
6. **✈️ টেলিগ্রাম ক্লাউড স্টোরেজ ও বট ইন্টিগ্রেশন**:
   - দৃষ্টি প্রতিবন্ধী ব্যক্তির রেজিস্ট্রেশন ও ওটিপি কোড টেলিগ্রামে স্বয়ংক্রিয়ভাবে পাঠানো হয় (যাতে TalkBack দিয়ে সহজে শোনা যায়)।
   - ডাটাবেজ ব্যাকআপ ফাইল সরাসরি টেলিগ্রাম চ্যানেলে সুরক্ষিত ক্লাউড স্টোরেজ হিসেবে আপলোড করা যায়।
7. **সহজ ক্লাউড ডিপ্লয়মেন্ট**: Render, Railway, Glitch, Replit অথবা যেকোনো ভিপিএসে ১-ক্লিকে ডেপ্লয় যোগ্য।

---

## 🛠️ সার্ভার চালু করার নিয়ম (How to Run the Server)

### ১. ডিপেন্ডেন্সি ইনস্টল করুন:
```bash
cd server
npm install
```

### ২. সার্ভার রান করুন:
```bash
npm start
```
অথবা ডেভেলপমেন্ট মোডে:
```bash
npm run dev
```

সার্ভার পোর্ট `3000`-এ চালু হবে:
- **লোকাল ব্রাউজার ড্যাশবোর্ড:** `http://localhost:3000`
- **হেলথ চেক এন্ডপয়েন্ট:** `http://localhost:3000/api/health`

---

## 📱 অ্যান্ড্রয়েড অ্যাপের সাথে সংযোগ (Android Connection Guide)

অ্যান্ড্রয়েড অ্যাপ্লিকেশনে গিয়ে **ইউজার ও ভেরিফিকেশন সেটিংস > Node সার্ভার ডাটাবেজ** ট্যাবে সার্ভারের URL সেট করুন:

| রান করার প্ল্যাটফর্ম | অ্যান্ড্রয়েডে দেওয়ার মতো URL |
| :--- | :--- |
| **অ্যান্ড্রয়েড ইমুলেটর (Android Studio / AI Studio)** | `http://10.0.2.2:3000` |
| **আসল মোবাইল ফোন (একই Wi-Fi নেটওয়ার্ক)** | `http://<আপনার_কম্পিউটারের_আইপি>:3000` (যেমন `http://192.168.1.15:3000`) |
| **অনলাইন ক্লাউড সার্ভার (Render / Railway)** | `https://blind-assist-node-server.onrender.com` |

---

## 📡 REST API ডকুমেন্টেশন (API Endpoints)

### ১. হেলথ চেক
- **URL:** `GET /api/health`
- **রেসপন্স:**
  ```json
  {
    "status": "ok",
    "message": "Accessibility Node.js Database Server is running smoothly",
    "timestamp": 1727878800000,
    "stats": {
      "totalUsers": 1,
      "verifiedUsers": 1,
      "activeServices": 1
    }
  }
  ```

### ২. ইউজার রেজিস্ট্রেশন / লগইন
- **URL:** `POST /api/users/register`
- **বডি (JSON):**
  ```json
  {
    "phoneNumber": "01712345678",
    "deviceId": "dev_android_98a7",
    "deviceModel": "Pixel 7 Pro",
    "role": "VISUALLY_IMPAIRED"
  }
  ```
- **রেসপন্স:**
  ```json
  {
    "success": true,
    "serverUserId": "node_01712345678_k8a",
    "phoneNumber": "01712345678",
    "isVerified": false,
    "verificationCode": "492104",
    "message": "User registered in Node database"
  }
  ```

### ৩. কোড দিয়ে ভেরিফিকেশন
- **URL:** `POST /api/users/verify`
- **বডি (JSON):**
  ```json
  {
    "phoneNumber": "01712345678",
    "code": "492104",
    "deviceId": "dev_android_98a7"
  }
  ```

### ৪. সেটিংস সিঙ্ক
- **URL:** `POST /api/users/sync-settings`
- **বডি (JSON):**
  ```json
  {
    "phoneNumber": "01712345678",
    "deviceId": "dev_android_98a7",
    "isServiceEnabled": true,
    "isBackgroundTrackerActive": true,
    "lastActiveScreen": "Accessibility Dashboard"
  }
  ```

### ৫. নেভিগেশন টেলিম্যাট্রি লগ
- **URL:** `POST /api/logs/navigation`
- **URL:** `GET /api/logs/navigation?limit=50`

---

## 🌐 ফ্রিতে ক্লাউডে হোস্ট করার নিয়ম (Free Hosting Guide)

1. **Render.com এ ডিপ্লয়:**
   - GitHub-এ পুশ করে Render-এ **New Web Service** খুলুন।
   - Root Directory দিন `server`।
   - Build Command: `npm install`
   - Start Command: `node server.js`
   - ডেপ্লয় শেষ হলে প্রাপ্ত URL টি অ্যাপে বসিয়ে দিন!
