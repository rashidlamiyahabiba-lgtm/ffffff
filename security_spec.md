# Security Specification (`security_spec.md`)

## 1. Data Invariants
1. **Strict Authenticated Ownership**: Every `DeviceProfile` (`/users/{userId}`) and `AccessibilitySignal` (`/users/{userId}/signals/{signalId}`) belongs exclusively to `request.auth.uid == userId` and must store `userId == request.auth.uid`.
2. **Signal Integrity & Enum Safety**: Every signal must specify a valid `signalType` (`EMERGENCY_SOS`, `VOICE_ASSIST`, `POWER_ON`, `POWER_OFF`, `SCREEN_SHIELD`, `WHATSAPP_LINK`, `PAYMENT_ASSIST`, `STATUS_PING`), a valid `priority` (`CRITICAL`, `HIGH`, `NORMAL`), and a valid `status` (`SENT`, `DELIVERED`, `EXECUTED`).
3. **Temporal & Identity Immutability**: `userId`, `id`, and `createdAt` are immutable after creation; timestamps must be valid Firestore `Timestamp` values `<= request.time`.
4. **Terminal State Locking**: Once an `AccessibilitySignal` reaches `status == 'EXECUTED'`, no further updates are permitted.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read/Write**: Attempting to read or write `/users/alice_123/signals/sig_1` with `request.auth == null`.
2. **Cross-User Profile Hijack**: Authenticated user `bob_456` attempting to read or overwrite `/users/alice_123`.
3. **Cross-User Signal Injection**: Authenticated user `bob_456` attempting to create `/users/alice_123/signals/sig_1` with `userId: "bob_456"` or `"alice_123"`.
4. **UID Spoofing in Owned Path**: Authenticated user `alice_123` creating `/users/alice_123/signals/sig_1` with `userId: "bob_456"`.
5. **Shadow / Ghost Field Injection**: Including `"isAdmin": true` or `"bypass": 1` in a signal or profile payload.
6. **Invalid Signal Type Enum**: Submitting `signalType: "HACK_SYSTEM"` not in the allowed enum list.
7. **Oversized String DoS (Resource Poisoning)**: Submitting a 10,000-character string in `messageBn` or `command`.
8. **Future Timestamp Spoofing**: Submitting `createdAt` set to a future timestamp (`> request.time`) or a raw integer instead of a Firestore `Timestamp`.
9. **Immutable Field Mutation**: Updating `createdAt` or `userId` on an existing signal document.
10. **Terminal State Re-opening**: Attempting to update a signal whose `existing().status == 'EXECUTED'` back to `'SENT'`.
11. **Unfiltered Collection Scraping**: Running a `list` query on `/users/alice_123/signals` without `.where("userId", "==", "alice_123")` or across another user's subcollection.
12. **Malformed Document ID Poisoning**: Creating a signal where document path `signalId` contains illegal characters or mismatches `incoming().id`.

## 3. Red Team Audit Summary
- **Shadow Update Test**: PASS (`hasOnly` blocks undeclared keys on both `create` and `update`).
- **PII / Cross-User Blanket Test**: PASS (`request.auth.uid == userId` and `resource.data.userId == request.auth.uid` enforced on all operations).
- **Query Trust Test**: PASS (`allow list` explicitly asserts `resource.data.userId == request.auth.uid`).
- **Value Poisoning & Terminal Locking Test**: PASS (`isValidAccessibilitySignal` and `existing().status != 'EXECUTED'` wrap the `update` rule).
