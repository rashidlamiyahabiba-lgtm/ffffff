package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    if (exception is kotlinx.coroutines.CancellationException) {
        throw exception
    }
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

data class DeviceProfile(
    val userId: String = "",
    val deviceId: String = "",
    val deviceModel: String = "",
    val isPowerOn: Boolean = true,
    val isServiceEnabled: Boolean = false,
    val lastSignalSummary: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val lastConnectedAt: Timestamp? = null
)

data class AccessibilitySignal(
    val id: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val signalType: String = "EMERGENCY_SOS",
    val command: String = "SOS_ALERT",
    val messageBn: String = "",
    val priority: String = "CRITICAL",
    val status: String = "SENT",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

class FirebaseSignalRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val explicitIdentifier: String? = null
) {
    constructor(context: Context, explicitIdentifier: String? = null) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance(),
        explicitIdentifier
    ) {
        appContext = context.applicationContext
    }

    private var appContext: Context? = null

    companion object {
        fun normalizePhoneKey(raw: String): String {
            // Convert Bengali digits (০-৯) and Arabic/Hindi digits to English digits (0-9) and keep digits only
            val mapped = buildString {
                for (ch in raw) {
                    when {
                        ch in '০'..'৯' -> append(('0'.code + (ch.code - '০'.code)).toChar())
                        ch.isDigit() -> append(ch.digitToInt().digitToChar())
                    }
                }
            }
            // Strip leading 0088 or 88 for Bangladeshi mobile numbers so 017..., 88017..., +88017..., and 0088017... match identically on every phone!
            return when {
                mapped.length == 15 && mapped.startsWith("008801") -> mapped.substring(4)
                mapped.length == 13 && mapped.startsWith("8801") -> mapped.substring(2)
                mapped.length == 10 && mapped.startsWith("1") -> "0$mapped"
                else -> mapped
            }
        }

        fun isPhoneKey(id: String): Boolean {
            return id.length in 8..20 && id.all { it in '0'..'9' }
        }
    }

    private fun resolveUserId(overrideId: String? = null): String {
        val rawCandidate = overrideId?.takeIf { it.isNotBlank() }
            ?: explicitIdentifier?.takeIf { it.isNotBlank() }
            ?: auth.currentUser?.uid
        if (rawCandidate.isNullOrBlank()) {
            throw IllegalStateException("User must be signed in with Phone Number or Google before accessing Firestore.")
        }
        val normalizedPhone = normalizePhoneKey(rawCandidate)
        return if (isPhoneKey(normalizedPhone)) normalizedPhone else rawCandidate.trim()
    }

    private fun rootCollectionFor(identifier: String): String {
        return if (isPhoneKey(identifier)) "phone_users" else "users"
    }

    /**
     * Creates or updates the user's device profile in `/phone_users/{phoneKey}` or `/users/{userId}`,
     * including `lastConnectedAt` timestamp so the Web Control Panel and Android Control Panel can
     * inspect when the target phone was last seen online.
     */
    suspend fun syncDeviceProfile(
        deviceId: String,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        isPowerOn: Boolean = true,
        isServiceEnabled: Boolean = false,
        lastSignalSummary: String = "Firebase রিয়েল-টাইম সিগন্যাল সংযোগ সক্রিয়",
        userIdOverride: String? = null
    ): Result<String> {
        val uid = try {
            resolveUserId(userIdOverride)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val rootCol = rootCollectionFor(uid)
        val path = "$rootCol/$uid"
        val docRef = db.collection(rootCol).document(uid)

        return try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "deviceId" to deviceId.take(64).ifBlank { "DEV-DEFAULT" },
                    "deviceModel" to deviceModel.take(120).ifBlank { "Android" },
                    "isPowerOn" to isPowerOn,
                    "isServiceEnabled" to isServiceEnabled,
                    "lastSignalSummary" to lastSignalSummary.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastConnectedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "deviceId" to deviceId.take(64).ifBlank { "DEV-DEFAULT" },
                    "deviceModel" to deviceModel.take(120).ifBlank { "Android" },
                    "isPowerOn" to isPowerOn,
                    "isServiceEnabled" to isServiceEnabled,
                    "lastSignalSummary" to lastSignalSummary.take(500),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastConnectedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(updatePayload, SetOptions.merge()).await()
            }
            Result.success(uid)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    /**
     * Lightweight periodic heartbeat that updates `lastConnectedAt` and `updatedAt` in `/phone_users/{phoneKey}`
     * so controllers can immediately diagnose if a phone's background service was killed by OEM battery optimization
     * or lost internet connectivity.
     */
    suspend fun updateLastConnectedHeartbeat(
        deviceId: String,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        isPowerOn: Boolean = true,
        isServiceEnabled: Boolean = true,
        userIdOverride: String? = null
    ): Result<Unit> {
        val uid = try {
            resolveUserId(userIdOverride)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val rootCol = rootCollectionFor(uid)
        val path = "$rootCol/$uid"
        val docRef = db.collection(rootCol).document(uid)
        return try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                syncDeviceProfile(
                    deviceId = deviceId,
                    deviceModel = deviceModel,
                    isPowerOn = isPowerOn,
                    isServiceEnabled = isServiceEnabled,
                    userIdOverride = uid
                )
            } else {
                docRef.update(
                    mapOf(
                        "deviceId" to deviceId.take(64).ifBlank { "DEV-DEFAULT" },
                        "deviceModel" to deviceModel.take(120).ifBlank { "Android" },
                        "isPowerOn" to isPowerOn,
                        "isServiceEnabled" to isServiceEnabled,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "lastConnectedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    /**
     * Checks in the Firestore database if any target phone has logged into the App with this phone number / user ID.
     */
    suspend fun getDeviceProfile(userIdOverride: String? = null): Result<DeviceProfile?> {
        val uid = try {
            resolveUserId(userIdOverride)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val rootCol = rootCollectionFor(uid)
        val path = "$rootCol/$uid"
        return try {
            val snap = db.collection(rootCol).document(uid).get().await()
            if (!snap.exists()) {
                Result.success(null)
            } else {
                val profile = snap.toObject(
                    DeviceProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                Result.success(profile)
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    /**
     * Real-time stream of the target device profile in `/phone_users/{phoneKey}` or `/users/{userId}`
     * so the Control Panel Phone immediately sees when the Target Phone is connected, its device model,
     * and whether Accessibility Permission is ON.
     */
    fun observeDeviceProfile(userId: String = explicitIdentifier ?: auth.currentUser?.uid.orEmpty()): Flow<DeviceProfile?> = flow {
        val rawUid = userId.ifBlank { "unauthenticated" }
        val normPhone = normalizePhoneKey(rawUid)
        val targetUid = if (isPhoneKey(normPhone)) normPhone else rawUid.trim()
        val rootCol = rootCollectionFor(targetUid)
        val path = "$rootCol/$targetUid"
        try {
            emitAll(
                db.collection(rootCol)
                    .document(targetUid)
                    .snapshots()
                    .map { snapshot ->
                        if (snapshot.exists()) {
                            val profile = snapshot.toObject(
                                DeviceProfile::class.java,
                                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                            )
                            if (profile != null && profile.userId.isNotBlank() && profile.userId != targetUid) {
                                null
                            } else {
                                profile
                            }
                        } else {
                            null
                        }
                    }
            )
        } catch (e: Exception) {
            val firestoreEx = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<com.google.firebase.firestore.FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreEx != null) {
                handleFirestoreError(firestoreEx, OperationType.GET, path)
                throw firestoreEx
            }
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            handleFirestoreError(e, OperationType.GET, path)
            throw e
        }
    }

    /**
     * Sends an instant accessibility or emergency signal to `/phone_users/{phoneKey}/signals/{signalId}` or `/users/{userId}/signals/{signalId}`.
     */
    suspend fun sendSignal(
        deviceId: String,
        signalType: String,
        command: String,
        messageBn: String,
        priority: String = "CRITICAL",
        userIdOverride: String? = null
    ): Result<String> {
        val uid = try {
            resolveUserId(userIdOverride)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val rootCol = rootCollectionFor(uid)
        val signalId = "sig_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val path = "$rootCol/$uid/signals/$signalId"
        val docRef = db.collection(rootCol).document(uid).collection("signals").document(signalId)

        val payload = mapOf(
            "id" to signalId,
            "userId" to uid,
            "deviceId" to deviceId.take(64).ifBlank { "DEV-ANDROID" },
            "signalType" to signalType,
            "command" to command.take(200).ifBlank { "ASSIST" },
            "messageBn" to messageBn.take(500).ifBlank { "জরুরি সিগন্যাল" },
            "priority" to priority,
            "status" to "SENT",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            try {
                appContext?.let { ctx ->
                    com.example.util.TelegramRealtimeSync.getInstance(ctx)
                        .notifyFirebaseSignalDispatchedToTelegramDb(
                            userId = uid,
                            deviceId = deviceId.take(64).ifBlank { "DEV-ANDROID" },
                            signalType = signalType,
                            command = command,
                            messageBn = messageBn,
                            status = "SENT (Firebase বাটন সিগন্যাল প্রেরিত)"
                        )
                }
            } catch (_: Exception) {}
            Result.success(signalId)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    /**
     * Marks a signal as DELIVERED or EXECUTED.
     */
    suspend fun updateSignalStatus(
        signalId: String,
        newStatus: String,
        updatedMessageBn: String? = null,
        userIdOverride: String? = null
    ): Result<Unit> {
        val uid = try {
            resolveUserId(userIdOverride)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val rootCol = rootCollectionFor(uid)
        val path = "$rootCol/$uid/signals/$signalId"
        val docRef = db.collection(rootCol).document(uid).collection("signals").document(signalId)

        val updates = mutableMapOf<String, Any>(
            "status" to newStatus,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!updatedMessageBn.isNullOrBlank()) {
            updates["messageBn"] = updatedMessageBn.take(500)
        }

        return try {
            docRef.update(updates).await()
            try {
                appContext?.let { ctx ->
                    com.example.util.TelegramRealtimeSync.getInstance(ctx)
                        .notifyFirebaseSignalDispatchedToTelegramDb(
                            userId = uid,
                            deviceId = "DEV-TARGET",
                            signalType = "SIGNAL_STATUS_UPDATE",
                            command = signalId,
                            messageBn = updatedMessageBn ?: "সিগন্যাল স্ট্যাটাস আপডেট: $newStatus",
                            status = newStatus
                        )
                }
            } catch (_: Exception) {}
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    /**
     * Fetches a specific signal by ID for a target user ID.
     */
    suspend fun getSignalById(targetUserId: String, signalId: String): Result<AccessibilitySignal?> {
        val rootCol = rootCollectionFor(targetUserId)
        val path = "$rootCol/$targetUserId/signals/$signalId"
        return try {
            val snap = db.collection(rootCol)
                .document(targetUserId)
                .collection("signals")
                .document(signalId)
                .get()
                .await()
            val signal = snap.toObject(
                AccessibilitySignal::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(signal)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    /**
     * Lists all signals for the user aligned with security rules (`whereEqualTo("userId", userId)`).
     */
    suspend fun getUserSignals(userId: String = resolveUserId()): Result<List<AccessibilitySignal>> {
        val rootCol = rootCollectionFor(userId)
        val path = "$rootCol/$userId/signals"
        return try {
            val snap = db.collection(rootCol)
                .document(userId)
                .collection("signals")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val list = snap.toObjects(
                AccessibilitySignal::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            Result.success(list)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    /**
     * Real-time observation of the user's signals using first-party `Query.snapshots()`.
     */
    fun observeSignals(userId: String = explicitIdentifier ?: auth.currentUser?.uid.orEmpty()): Flow<List<AccessibilitySignal>> = flow {
        val rawUid = userId.ifBlank { "unauthenticated" }
        val normPhone = normalizePhoneKey(rawUid)
        val targetUid = if (isPhoneKey(normPhone)) normPhone else rawUid.trim()
        val rootCol = rootCollectionFor(targetUid)
        val path = "$rootCol/$targetUid/signals"
        try {
            emitAll(
                db.collection(rootCol)
                    .document(targetUid)
                    .collection("signals")
                    .whereEqualTo("userId", targetUid)
                    .snapshots()
                    .map { snapshot ->
                        snapshot.toObjects(
                            AccessibilitySignal::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                            .filter { it.userId == targetUid }
                            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                    }
            )
        } catch (e: Exception) {
            val firestoreEx = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<com.google.firebase.firestore.FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreEx != null) {
                handleFirestoreError(firestoreEx, OperationType.LIST, path)
                throw firestoreEx
            }
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            handleFirestoreError(e, OperationType.LIST, path)
            throw e
        }
    }
}
