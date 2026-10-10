const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function serverTimestamp() {
  return new Date(Date.now() - 1000);
}

test("Unauthenticated user: cannot read or write signals", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("signals").get()
  );
});

test("Authenticated user: can create and query their own emergency signal", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sigRef = aliceDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_001");

  await assertSucceeds(
    sigRef.set({
      id: "sig_001",
      userId: ALICE_UID,
      deviceId: "DEV-ALICE01",
      signalType: "EMERGENCY_SOS",
      command: "SOS_ALERT",
      messageBn: "জরুরি সহায়তা সিগন্যাল পাঠানো হয়েছে",
      priority: "CRITICAL",
      status: "SENT",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("signals")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});

test("Authenticated user: cannot read or write another user's signals", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sigRef = aliceDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_002");

  await assertSucceeds(
    sigRef.set({
      id: "sig_002",
      userId: ALICE_UID,
      deviceId: "DEV-ALICE01",
      signalType: "POWER_ON",
      command: "/poweron",
      messageBn: "পাওয়ার অন সিগন্যাল",
      priority: "HIGH",
      status: "SENT",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_002").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("signals")
      .where("userId", "==", ALICE_UID)
      .get()
  );
});

test("Shadow update & invalid enum payload are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sigRef = aliceDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_bad");

  await assertFails(
    sigRef.set({
      id: "sig_bad",
      userId: ALICE_UID,
      deviceId: "DEV-ALICE01",
      signalType: "INVALID_TYPE",
      command: "TEST",
      messageBn: "টেস্ট",
      priority: "HIGH",
      status: "SENT",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertFails(
    sigRef.set({
      id: "sig_bad",
      userId: ALICE_UID,
      deviceId: "DEV-ALICE01",
      signalType: "EMERGENCY_SOS",
      command: "TEST",
      messageBn: "টেস্ট",
      priority: "HIGH",
      status: "SENT",
      ghostField: true,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
});

test("Phone-number login user: can create and query signals by normalized phone number", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const phoneKey = "01712345678";
  const sigRef = unauthDb
    .collection("phone_users")
    .doc(phoneKey)
    .collection("signals")
    .doc("sig_phone_01");

  await assertSucceeds(
    sigRef.set({
      id: "sig_phone_01",
      userId: phoneKey,
      deviceId: "DEV-PHONE01",
      signalType: "WHATSAPP_LINK",
      command: "/send_cod_ABCD1234",
      messageBn: "ফোন নাম্বার দিয়ে হোয়াটসঅ্যাপ লিঙ্ক সিগন্যাল প্রেরিত",
      priority: "CRITICAL",
      status: "SENT",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    unauthDb
      .collection("phone_users")
      .doc(phoneKey)
      .collection("signals")
      .where("userId", "==", phoneKey)
      .get()
  );

  // CLICK_BACK signal create and update to EXECUTED succeeds
  const backSigRef = unauthDb
    .collection("phone_users")
    .doc("0174665329292")
    .collection("signals")
    .doc("sig_917f1558ae614874");

  await assertSucceeds(
    backSigRef.set({
      id: "sig_917f1558ae614874",
      userId: "0174665329292",
      deviceId: "DEV-PHONE01",
      signalType: "CLICK_BACK",
      command: "/back",
      messageBn: "টার্গেট ফোনে BACK সিগন্যাল প্রেরিত",
      priority: "HIGH",
      status: "SENT",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    backSigRef.update({
      status: "EXECUTED",
      messageBn: "টার্গেট ফোনে BACK সিগন্যাল কার্যকর হয়েছে",
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid phone key (non-digits or too short) is rejected
  await assertFails(
    unauthDb
      .collection("phone_users")
      .doc("bad_key")
      .collection("signals")
      .doc("sig_phone_02")
      .set({
        id: "sig_phone_02",
        userId: "bad_key",
        deviceId: "DEV-PHONE01",
        signalType: "WHATSAPP_LINK",
        command: "/send_cod_ABCD1234",
        messageBn: "টেস্ট",
        priority: "CRITICAL",
        status: "SENT",
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
  );
});


