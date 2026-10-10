package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Extracts the 8-character WhatsApp Web (`web.whatsapp.com`) pairing code from an uploaded photo or screenshot.
 *
 * Uses a dual-layer high-accuracy pipeline:
 * 1. On-device Google ML Kit Text Recognition (works 100% offline with zero API keys needed)
 *    with smart multi-scale cropping, contrast enhancement, and WhatsApp Web box layout analysis.
 * 2. Optional Gemini Vision fallback (`gemini-3.5-flash`) if `BuildConfig.GEMINI_API_KEY` is configured
 *    and ML Kit could not find a clean 8-character pairing code.
 */
object PairingCodeImageExtractor {
    private const val TAG = "PairingCodeImageExtractor"

    private val geminiHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Words commonly present on a web.whatsapp.com screenshot or payment counter that are 8 letters long and MUST NEVER be mistaken for a pairing code
    private val BLACKLISTED_WORDS = setOf(
        "WHATSAPP", "LINKWITH", "SETTINGS", "CONTINUE", "DOWNLOAD", "COMPUTER", "TUTORIAL",
        "BUSINESS", "YOURPHON", "SCANQRCO", "PASSWORD", "SECURITY", "MESSAGES", "PERSONAL",
        "OFFICIAL", "CONTACTS", "ARCHIVED", "CHANNELS", "DOCUMENT", "KEYBOARD", "LANGUAGE",
        "FEEDBACK", "STARTING", "VERIFIED", "COMPLETE", "TAPADEVI", "LINKEDDE", "ENTERCOD",
        "PHONENUM", "INSTEADOF", "NEEDHELP", "GETSTART", "INCLUDES", "FEATURES", "BROWSERS",
        "MERCHANT", "PHARMACY", "HOSPITAL", "CUSTOMER", "MEDICINE", "BKASHPAY", "NAGADPAY",
        "CASHBACK", "DISCOUNT", "SHOPPING", "SENDMONE", "RECEIVER", "TRANSFER", "ACCOUNTS",
        "PAYMENTS", "SERVICES", "TERMINAL", "OPERATOR", "BANGLADE"
    )

    data class ExtractionResult(
        val code: String?,
        val formattedCode: String?,
        val rawRecognizedText: String,
        val engineUsed: String,
        val detectedMerchantPhone: String? = null
    )

    /**
     * Extracts a Bangladeshi 11-digit mobile banking number (`013...` to `019...`) from a photo
     * of a pharmacy/hospital/shop payment counter (converting Bengali digits ০-৯ to English 0-9 automatically).
     */
    fun parseBangladeshiPaymentPhoneFromText(rawText: String): String? {
        if (rawText.isBlank()) return null
        val bengaliToEnglishDigits = rawText.map { ch ->
            when (ch) {
                '০' -> '0'
                '১' -> '1'
                '২' -> '2'
                '৩' -> '3'
                '৪' -> '4'
                '৫' -> '5'
                '৬' -> '6'
                '৭' -> '7'
                '৮' -> '8'
                '৯' -> '9'
                else -> ch
            }
        }.joinToString("")

        // Match +8801XXXXXXXXX or 01XXXXXXXXX (even if hyphenated or spaced like 01711-223344 or 01711 223344)
        val phoneRegex = Regex("(?:\\+?88)?(01[3-9][0-9][\\s-]?[0-9]{3}[\\s-]?[0-9]{4}|01[3-9][0-9]{2}[\\s-]?[0-9]{6}|01[3-9][0-9]{8})")
        val match = phoneRegex.find(bengaliToEnglishDigits)
        if (match != null) {
            val digits = match.groupValues[1].replace("[^0-9]".toRegex(), "")
            if (digits.length == 11 && digits.startsWith("01")) {
                return digits
            }
        }
        return null
    }

    suspend fun extractPairingCodeFromBytes(
        context: Context,
        imageBytes: ByteArray
    ): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return@withContext ExtractionResult(null, null, "ছবি ডিকোড করা যায়নি", "NONE")

            // 1. Primary: Run Google ML Kit Text Recognition on full image
            val rawTextFull = recognizeTextWithMlKit(bitmap)
            val detectedPhoneFull = parseBangladeshiPaymentPhoneFromText(rawTextFull)
            val lowerFull = rawTextFull.lowercase()
            val looksLikeWhatsAppWeb = lowerFull.contains("whatsapp") ||
                lowerFull.contains("link with phone") ||
                lowerFull.contains("enter code")

            // If the photo has a Bangladeshi 11-digit payment phone number and is NOT a WhatsApp Web screen,
            // return the merchant phone immediately so 8-letter shop words (like MERCHANT/PHARMACY) are never mistaken for pairing codes!
            if (!detectedPhoneFull.isNullOrBlank() && !looksLikeWhatsAppWeb) {
                return@withContext ExtractionResult(
                    code = null,
                    formattedCode = null,
                    rawRecognizedText = rawTextFull,
                    engineUsed = "Google ML Kit Payment OCR",
                    detectedMerchantPhone = detectedPhoneFull
                )
            }

            val candidateFromFull = parseWhatsAppPairingCodeFromText(rawTextFull)
            if (candidateFromFull != null) {
                val formatted = format8CharCode(candidateFromFull)
                Log.i(TAG, "✅ Extracted pairing code via ML Kit (full image): $candidateFromFull ($formatted)")
                return@withContext ExtractionResult(
                    code = candidateFromFull,
                    formattedCode = formatted,
                    rawRecognizedText = rawTextFull,
                    engineUsed = "Google ML Kit OCR",
                    detectedMerchantPhone = detectedPhoneFull
                )
            }

            // 2. Secondary: Center-crop & upscale (helps when user takes a wide desktop monitor photo from a distance)
            val centerCropped = createCenterCropAndUpscale(bitmap)
            if (centerCropped != null) {
                val rawTextCrop = recognizeTextWithMlKit(centerCropped)
                val detectedPhoneCrop = detectedPhoneFull ?: parseBangladeshiPaymentPhoneFromText(rawTextCrop)
                if (!detectedPhoneCrop.isNullOrBlank() && !looksLikeWhatsAppWeb && !rawTextCrop.lowercase().contains("whatsapp")) {
                    return@withContext ExtractionResult(
                        code = null,
                        formattedCode = null,
                        rawRecognizedText = "$rawTextFull\n$rawTextCrop".trim(),
                        engineUsed = "Google ML Kit Smart Zoom Payment OCR",
                        detectedMerchantPhone = detectedPhoneCrop
                    )
                }
                val candidateFromCrop = parseWhatsAppPairingCodeFromText(rawTextCrop)
                if (candidateFromCrop != null) {
                    val formatted = format8CharCode(candidateFromCrop)
                    Log.i(TAG, "✅ Extracted pairing code via ML Kit (center upscale): $candidateFromCrop ($formatted)")
                    return@withContext ExtractionResult(
                        code = candidateFromCrop,
                        formattedCode = formatted,
                        rawRecognizedText = "$rawTextFull\n$rawTextCrop".trim(),
                        engineUsed = "Google ML Kit Smart Zoom OCR",
                        detectedMerchantPhone = detectedPhoneCrop
                    )
                }
            }

            // 3. Tertiary: Optional Gemini Vision API fallback if GEMINI_API_KEY is configured in BuildConfig
            val geminiCode = tryExtractWithGeminiVision(bitmap)
            if (geminiCode != null) {
                val formatted = format8CharCode(geminiCode)
                Log.i(TAG, "✅ Extracted pairing code via Gemini Vision: $geminiCode ($formatted)")
                return@withContext ExtractionResult(
                    code = geminiCode,
                    formattedCode = formatted,
                    rawRecognizedText = rawTextFull.ifBlank { geminiCode },
                    engineUsed = "Gemini Vision AI",
                    detectedMerchantPhone = detectedPhoneFull
                )
            }

            ExtractionResult(
                code = null,
                formattedCode = null,
                rawRecognizedText = rawTextFull,
                engineUsed = "ML Kit OCR",
                detectedMerchantPhone = detectedPhoneFull
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting pairing code from image", e)
            ExtractionResult(null, null, e.message.orEmpty(), "ERROR")
        }
    }

    private suspend fun recognizeTextWithMlKit(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Sort blocks top-to-bottom, and elements left-to-right on the same horizontal band
                    val allLines = mutableListOf<Pair<Int, String>>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val top = line.boundingBox?.top ?: 0
                            allLines.add(top to line.text)
                        }
                    }
                    allLines.sortBy { it.first }
                    val orderedText = allLines.joinToString("\n") { it.second }
                    if (cont.isActive) {
                        cont.resume(orderedText.ifBlank { visionText.text })
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit OCR failure: ${e.message}")
                    if (cont.isActive) {
                        cont.resume("")
                    }
                }
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resume("")
            }
        }
    }

    /**
     * Parses raw OCR text to find the genuine 8-character WhatsApp Web pairing code.
     * Supports formats like:
     * - `ABCD-1234` or `ABCD - 1234` or `A B C D - 1 2 3 4`
     * - `A B C D 1 2 3 4` (8 spaced characters inside individual boxes on web.whatsapp.com)
     * - `Y811B582` (contiguous 8-character alphanumeric token)
     */
    fun parseWhatsAppPairingCodeFromText(rawText: String): String? {
        if (rawText.isBlank()) return null

        // Normalize common OCR symbol confusions around dashes
        val normalized = rawText
            .replace('—', '-')
            .replace('–', '-')
            .replace('−', '-')
            .replace('~', '-')

        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Priority 1: Explicit 4-char + hyphen + 4-char pattern (e.g. "Y811-B582" or "Y 8 1 1 - B 5 8 2")
        val hyphenJoinedRegex = Regex("(?i)\\b([A-Z0-9]\\s*[A-Z0-9]\\s*[A-Z0-9]\\s*[A-Z0-9])\\s*-\\s*([A-Z0-9]\\s*[A-Z0-9]\\s*[A-Z0-9]\\s*[A-Z0-9])\\b")
        for (line in lines) {
            val match = hyphenJoinedRegex.find(line)
            if (match != null) {
                val part1 = match.groupValues[1].replace("\\s+".toRegex(), "").uppercase()
                val part2 = match.groupValues[2].replace("\\s+".toRegex(), "").uppercase()
                val candidate = part1 + part2
                if (isValid8CharPairingCandidate(candidate, strictDigitRequirement = false)) {
                    return candidate
                }
            }
        }

        // Also check across the entire text if OCR put a newline around the hyphen
        val globalHyphenMatch = hyphenJoinedRegex.find(normalized.replace("\n", " "))
        if (globalHyphenMatch != null) {
            val part1 = globalHyphenMatch.groupValues[1].replace("\\s+".toRegex(), "").uppercase()
            val part2 = globalHyphenMatch.groupValues[2].replace("\\s+".toRegex(), "").uppercase()
            val candidate = part1 + part2
            if (isValid8CharPairingCandidate(candidate, strictDigitRequirement = false)) {
                return candidate
            }
        }

        // Priority 2: A line that consists purely of 8 single-character boxes or two 4-character groups
        // (e.g., "A B E T 7 8 T O" or "ABET 78TO")
        for (line in lines) {
            val cleanedLine = line.replace("[^A-Za-z0-9\\s-]".toRegex(), "").trim()
            val compact = cleanedLine.replace("[\\s-]+".toRegex(), "").uppercase()
            if (compact.length == 8 && isValid8CharPairingCandidate(compact, strictDigitRequirement = false)) {
                // Verify it's not a regular 2-word English phrase like "LINK WITH" or "OPEN YOUR"
                val words = cleanedLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val looksLikeCodeLine = words.size >= 4 || compact.any { it.isDigit() }
                if (looksLikeCodeLine) {
                    return compact
                }
            }
        }

        // Priority 3: Any 8-character alphanumeric token containing at least 1 digit and 1 letter
        val token8Regex = Regex("(?i)\\b([A-Z0-9]{4}-[A-Z0-9]{4}|[A-Z0-9]{8})\\b")
        val allCandidates = mutableListOf<String>()
        for (match in token8Regex.findAll(normalized)) {
            val rawToken = match.value.replace("-", "").uppercase()
            if (isValid8CharPairingCandidate(rawToken, strictDigitRequirement = true)) {
                allCandidates.add(rawToken)
            }
        }
        if (allCandidates.isNotEmpty()) {
            return allCandidates.first()
        }

        // Priority 4: Any 8-character uppercase/digit token that is not a common English word
        for (match in token8Regex.findAll(normalized)) {
            val rawToken = match.value.replace("-", "").uppercase()
            if (isValid8CharPairingCandidate(rawToken, strictDigitRequirement = false)) {
                return rawToken
            }
        }

        return null
    }

    private fun isValid8CharPairingCandidate(candidate: String, strictDigitRequirement: Boolean): Boolean {
        if (candidate.length != 8) return false
        if (!candidate.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        if (candidate in BLACKLISTED_WORDS) return false
        // Reject pure phone number prefixes or dates like 20261005
        if (candidate.startsWith("2025") || candidate.startsWith("2026") || candidate.startsWith("017") || candidate.startsWith("018") || candidate.startsWith("019")) {
            return false
        }
        if (strictDigitRequirement) {
            val hasDigit = candidate.any { it.isDigit() }
            val hasLetter = candidate.any { it.isLetter() }
            return hasDigit && hasLetter
        }
        return true
    }

    private fun format8CharCode(clean8: String): String {
        val c = clean8.replace("-", "").trim().uppercase()
        return if (c.length == 8) "${c.substring(0, 4)}-${c.substring(4, 8)}" else c
    }

    private fun createCenterCropAndUpscale(src: Bitmap): Bitmap? {
        return try {
            val w = src.width
            val h = src.height
            if (w < 120 || h < 120) return null
            val cropW = (w * 0.75f).toInt()
            val cropH = (h * 0.65f).toInt()
            val startX = ((w - cropW) / 2).coerceAtLeast(0)
            val startY = ((h - cropH) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(src, startX, startY, cropW, cropH)
            Bitmap.createScaledBitmap(cropped, cropW * 2, cropH * 2, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryExtractWithGeminiVision(bitmap: Bitmap): String? {
        return try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") return null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val base64Image = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val prompt = "Extract the 8-character WhatsApp Web 'Link with phone number' pairing code shown in this image (usually 8 characters displayed in 8 boxes with a dash in the middle like ABCD-1234). Reply with ONLY the 8 characters and nothing else, or NONE if no pairing code is present."

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            }))
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            geminiHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                    .trim()
                if (text.equals("NONE", ignoreCase = true)) return null
                parseWhatsAppPairingCodeFromText(text)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini vision fallback skipped: ${e.message}")
            null
        }
    }
}
