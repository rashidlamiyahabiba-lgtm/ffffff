package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirebaseSignalRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun sendSignal_validPayload_createsDocumentAndReturnsId() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = FirebaseSignalRepository(firestore, auth)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendSignal(
                deviceId = "DEV-ALICE01",
                signalType = "EMERGENCY_SOS",
                command = "SOS_ALERT",
                messageBn = "জরুরি মুহূর্তে দ্রুত সহায়তা সিগন্যাল",
                priority = "CRITICAL"
            )
        }
        assertTrue(createResult.isSuccess)
        val signalId = createResult.getOrThrow()
        assertTrue(signalId.startsWith("sig_"))
    }

    @Test
    fun getUserSignals_authenticatedOwner_returnsMatchingSignals() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirebaseSignalRepository(firestore, auth)
        val signalId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendSignal(
                deviceId = "DEV-ALICE01",
                signalType = "POWER_ON",
                command = "/poweron",
                messageBn = "স্ক্রিন সুরক্ষা ও পাওয়ার অন সিগন্যাল",
                priority = "HIGH"
            ).getOrThrow()
        }

        val signalsResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getUserSignals(aliceUid)
        }
        assertTrue(signalsResult.isSuccess)
        assertTrue(signalsResult.getOrThrow().any { it.id == signalId })
    }

    @Test
    fun observeSignals_authenticatedOwner_emitsRealtimeUpdates() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = FirebaseSignalRepository(firestore, auth)
        val signalId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendSignal(
                deviceId = "DEV-ALICE01",
                signalType = "VOICE_ASSIST",
                command = "SPEAK_STATUS",
                messageBn = "ভয়েস অ্যাসিস্ট্যান্ট সিগন্যাল সচল",
                priority = "NORMAL"
            ).getOrThrow()
        }

        val emittedSignals = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSignals(aliceUid).first { list -> list.any { it.id == signalId } }
        }
        assertTrue(emittedSignals.any { it.id == signalId })
    }

    @Test
    fun getSignalById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = FirebaseSignalRepository(firestore, auth)
        val signalId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.sendSignal(
                deviceId = "DEV-ALICE01",
                signalType = "EMERGENCY_SOS",
                command = "SOS_ALERT",
                messageBn = "অ্যালিসের ব্যক্তিগত সিগন্যাল",
                priority = "CRITICAL"
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = FirebaseSignalRepository(firestore, auth)
        val crossUserResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getSignalById(aliceUid, signalId)
        }
        assertTrue(crossUserResult.isFailure)
        val exception = crossUserResult.exceptionOrNull() as? FirebaseFirestoreException
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    @Test
    fun observeSignals_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = FirebaseSignalRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeSignals("unauthenticated_user").first()
            }
            fail("Expected FirebaseFirestoreException.PERMISSION_DENIED for unauthenticated user")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun sendAndObserveSignal_withPhoneNumberKey_succeedsWithoutGoogleLogin() = runBlocking {
        auth.signOut()
        val phoneKey = FirebaseSignalRepository.normalizePhoneKey("+8801712-345678")
        assertEquals("01712345678", phoneKey)
        val phoneRepo = FirebaseSignalRepository(firestore, auth, explicitIdentifier = phoneKey)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            phoneRepo.sendSignal(
                deviceId = "PHONE-$phoneKey",
                signalType = "WHATSAPP_LINK",
                command = "/send_cod_ABCD1234",
                messageBn = "ফোন নাম্বার দিয়ে হোয়াটসঅ্যাপ লিঙ্ক সিগন্যাল",
                priority = "CRITICAL"
            )
        }
        assertTrue(createResult.isSuccess)
        val signalId = createResult.getOrThrow()

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            phoneRepo.observeSignals(phoneKey).first { list -> list.any { it.id == signalId } }
        }
        assertTrue(emitted.any { it.id == signalId && it.command == "/send_cod_ABCD1234" })

        val backSignalRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            phoneRepo.sendSignal(
                deviceId = "PHONE-$phoneKey",
                signalType = "CLICK_BACK",
                command = "/back",
                messageBn = "টার্গেট ফোনে BACK সিগন্যাল প্রেরিত",
                priority = "HIGH"
            )
        }
        assertTrue(backSignalRes.isSuccess)
        val backSigId = backSignalRes.getOrThrow()

        val updateRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            phoneRepo.updateSignalStatus(
                signalId = backSigId,
                newStatus = "EXECUTED",
                updatedMessageBn = "টার্গেট ফোনে BACK সিগন্যাল কার্যকর হয়েছে"
            )
        }
        assertTrue(updateRes.isSuccess)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
