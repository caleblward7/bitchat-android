package com.bitchat.android.survey

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/**
 * Survey data model for the "bitchat Forms" fork.
 *
 * A [Survey] is authored on one device and broadcast over the BLE mesh
 * (MessageType.SURVEY_PUBLISH). Any peer running this build can open it and
 * submit a [SurveyResponse], which is routed privately back to the creator
 * (MessageType.SURVEY_RESPONSE) either over the mesh or, when online, over the
 * Nostr internet relay path that bitchat already provides.
 *
 * Payloads are JSON-encoded with gson (already a project dependency) and carried
 * inside a normal BitchatPacket payload, so transport-level fragmentation and
 * store-and-forward apply unchanged.
 */

enum class QuestionType {
    @SerializedName("short_text") SHORT_TEXT,
    @SerializedName("long_text") LONG_TEXT,
    @SerializedName("single_choice") SINGLE_CHOICE,
    @SerializedName("multi_choice") MULTI_CHOICE,
    @SerializedName("scale") SCALE
}

data class SurveyQuestion(
    val id: String,
    val text: String,
    val type: QuestionType,
    /** Choices for SINGLE_CHOICE / MULTI_CHOICE. Ignored for text/scale. */
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    /** Bounds for SCALE questions. */
    val scaleMin: Int = 1,
    val scaleMax: Int = 5
)

data class Survey(
    /** Stable UUID string; identifies the survey across all peers. */
    val id: String,
    val title: String,
    val description: String = "",
    /**
     * Return address for responses: the creator's stable identity so responses
     * can be routed back. Mesh peerID for local delivery; a Nostr pubkey may be
     * carried alongside for internet delivery (see [creatorNostrPub]).
     */
    val creatorID: String,
    val creatorNickname: String,
    val creatorNostrPub: String? = null,
    val questions: List<SurveyQuestion>,
    val createdAt: Long,
    val closed: Boolean = false
) {
    fun encode(): ByteArray = GSON.toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): Survey? = try {
            GSON.fromJson(String(bytes, Charsets.UTF_8), Survey::class.java)
        } catch (e: Exception) {
            android.util.Log.e("Survey", "decode failed: ${e.message}")
            null
        }
    }
}

data class SurveyAnswer(
    val questionId: String,
    /** One value for text/scale/single-choice; multiple for multi-choice. */
    val values: List<String>
)

data class SurveyResponse(
    val surveyId: String,
    /** UUID for this submission, used to de-duplicate relayed copies. */
    val responseId: String,
    val respondentID: String,
    val respondentNickname: String,
    val answers: List<SurveyAnswer>,
    val submittedAt: Long
) {
    fun encode(): ByteArray = GSON.toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): SurveyResponse? = try {
            GSON.fromJson(String(bytes, Charsets.UTF_8), SurveyResponse::class.java)
        } catch (e: Exception) {
            android.util.Log.e("SurveyResponse", "decode failed: ${e.message}")
            null
        }
    }
}

/** Minimal payload used to mark a published survey as closed to new responses. */
data class SurveyClose(val surveyId: String) {
    fun encode(): ByteArray = GSON.toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): SurveyClose? = try {
            GSON.fromJson(String(bytes, Charsets.UTF_8), SurveyClose::class.java)
        } catch (e: Exception) { null }
    }
}

private val GSON: Gson = GsonBuilder().create()
