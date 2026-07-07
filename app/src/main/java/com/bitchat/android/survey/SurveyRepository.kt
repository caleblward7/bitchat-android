package com.bitchat.android.survey

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Forms fork: single source of truth for surveys and responses.
 *
 * Holds three collections, persisted as JSON in SharedPreferences:
 *  - [mySurveys]        surveys I authored (I collect responses for these)
 *  - [receivedSurveys]  surveys others published that I can fill out
 *  - [responses]        responses I've collected, keyed by surveyId
 *
 * Also orchestrates sending over the BLE mesh via [BluetoothMeshService].
 * The internet (Nostr) path is layered on later; the mesh path works offline now.
 */
class SurveyRepository private constructor(
    private val appContext: Context,
    private var mesh: BluetoothMeshService,
    private var nicknameProvider: () -> String
) {
    private val prefs = appContext.getSharedPreferences("bitchat_forms", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _mySurveys = MutableStateFlow<List<Survey>>(emptyList())
    val mySurveys: StateFlow<List<Survey>> = _mySurveys.asStateFlow()

    private val _receivedSurveys = MutableStateFlow<List<Survey>>(emptyList())
    val receivedSurveys: StateFlow<List<Survey>> = _receivedSurveys.asStateFlow()

    private val _responses = MutableStateFlow<Map<String, List<SurveyResponse>>>(emptyMap())
    val responses: StateFlow<Map<String, List<SurveyResponse>>> = _responses.asStateFlow()

    /** Survey ids this device has already submitted a response for. */
    private val _answered = MutableStateFlow<Set<String>>(emptySet())
    val answered: StateFlow<Set<String>> = _answered.asStateFlow()

    val myPeerID: String get() = mesh.myPeerID

    init { load() }

    // ---------------------------------------------------------------------
    // Authoring (creator side)
    // ---------------------------------------------------------------------

    /** Publish a new survey to the mesh and keep it in my authored list. */
    fun publish(title: String, description: String, questions: List<SurveyQuestion>): Survey {
        val survey = Survey(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            description = description,
            creatorID = mesh.myPeerID,
            creatorNickname = nicknameProvider(),
            creatorNostrPub = currentNpub(),
            questions = questions,
            createdAt = System.currentTimeMillis(),
            closed = false
        )
        _mySurveys.value = _mySurveys.value + survey
        persistMySurveys()
        mesh.sendSurveyBroadcast(survey)
        Log.d(TAG, "Published survey ${survey.id} (${survey.questions.size} questions)")
        return survey
    }

    /** Re-broadcast an existing authored survey (e.g. for peers who just joined). */
    fun rebroadcast(survey: Survey) = mesh.sendSurveyBroadcast(survey)

    fun close(surveyId: String) {
        _mySurveys.value = _mySurveys.value.map { if (it.id == surveyId) it.copy(closed = true) else it }
        persistMySurveys()
        markClosedLocally(surveyId)
        mesh.sendSurveyClose(surveyId)
    }

    fun responsesFor(surveyId: String): List<SurveyResponse> = _responses.value[surveyId] ?: emptyList()

    // ---------------------------------------------------------------------
    // Filling out (respondent side)
    // ---------------------------------------------------------------------

    /** Submit a response: route it to the creator, and store locally if I am the creator. */
    fun submit(survey: Survey, answers: List<SurveyAnswer>) {
        val response = SurveyResponse(
            surveyId = survey.id,
            responseId = java.util.UUID.randomUUID().toString(),
            respondentID = mesh.myPeerID,
            respondentNickname = nicknameProvider(),
            answers = answers,
            submittedAt = System.currentTimeMillis()
        )
        _answered.value = _answered.value + survey.id
        prefs.edit().putStringSet("answered", _answered.value).apply()

        if (survey.creatorID == mesh.myPeerID) {
            // I authored this survey; record my own response directly.
            storeResponse(response)
        } else {
            // Send over BLE mesh (local range) …
            mesh.sendSurveyResponse(survey.creatorID, response)
            // … and, if we know the creator's Nostr identity, also return it over the internet.
            // Encrypted end-to-end by the gift-wrap; reaches the creator when out of BLE range.
            // Duplicates are deduped on the creator side by responseId.
            survey.creatorNostrPub?.let { npub ->
                try {
                    com.bitchat.android.nostr.NostrTransport.getInstance(appContext).sendSurveyResponse(response, npub)
                } catch (e: Exception) {
                    Log.e(TAG, "Nostr response send failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Submitted response ${response.responseId} for survey ${survey.id}")
    }

    private fun currentNpub(): String? = try {
        com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(appContext)?.npub
    } catch (e: Exception) {
        Log.w(TAG, "no Nostr identity for npub capture: ${e.message}"); null
    }

    // ---------------------------------------------------------------------
    // Inbound from mesh (called by ChatViewModel delegate overrides)
    // ---------------------------------------------------------------------

    fun onSurveyReceived(survey: Survey) {
        // Ignore my own broadcasts echoed back, and dedupe by id.
        if (survey.creatorID == mesh.myPeerID) return
        if (_receivedSurveys.value.any { it.id == survey.id }) {
            // Update (e.g. closed status) in place.
            _receivedSurveys.value = _receivedSurveys.value.map { if (it.id == survey.id) survey else it }
        } else {
            _receivedSurveys.value = _receivedSurveys.value + survey
        }
        persistReceivedSurveys()
    }

    fun onResponseReceived(response: SurveyResponse) = storeResponse(response)

    fun onSurveyClosed(surveyId: String) {
        _receivedSurveys.value = _receivedSurveys.value.map { if (it.id == surveyId) it.copy(closed = true) else it }
        persistReceivedSurveys()
        markClosedLocally(surveyId)
    }

    private fun storeResponse(response: SurveyResponse) {
        val current = _responses.value[response.surveyId] ?: emptyList()
        if (current.any { it.responseId == response.responseId }) return // dedupe relayed copies
        val updated = _responses.value.toMutableMap()
        updated[response.surveyId] = current + response
        _responses.value = updated
        persistResponses()
    }

    private fun markClosedLocally(surveyId: String) {
        _mySurveys.value = _mySurveys.value.map { if (it.id == surveyId) it.copy(closed = true) else it }
        persistMySurveys()
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private fun persistMySurveys() = prefs.edit().putString("my_surveys", gson.toJson(_mySurveys.value)).apply()
    private fun persistReceivedSurveys() = prefs.edit().putString("received_surveys", gson.toJson(_receivedSurveys.value)).apply()
    private fun persistResponses() = prefs.edit().putString("responses", gson.toJson(_responses.value)).apply()

    private fun load() {
        try {
            val surveyListType = object : TypeToken<List<Survey>>() {}.type
            val respMapType = object : TypeToken<Map<String, List<SurveyResponse>>>() {}.type
            prefs.getString("my_surveys", null)?.let { _mySurveys.value = gson.fromJson(it, surveyListType) ?: emptyList() }
            prefs.getString("received_surveys", null)?.let { _receivedSurveys.value = gson.fromJson(it, surveyListType) ?: emptyList() }
            prefs.getString("responses", null)?.let { _responses.value = gson.fromJson(it, respMapType) ?: emptyMap() }
            _answered.value = prefs.getStringSet("answered", emptySet())?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load surveys: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SurveyRepository"
        @Volatile private var INSTANCE: SurveyRepository? = null

        /** Get without creating — used by the Nostr DM handler to feed inbound responses. */
        fun tryGet(): SurveyRepository? = INSTANCE

        fun getInstance(
            context: Context,
            mesh: BluetoothMeshService,
            nicknameProvider: () -> String
        ): SurveyRepository {
            val inst = INSTANCE ?: synchronized(this) {
                INSTANCE ?: SurveyRepository(context.applicationContext, mesh, nicknameProvider).also { INSTANCE = it }
            }
            // Keep transport + nickname source current across ViewModel recreation
            inst.mesh = mesh
            inst.nicknameProvider = nicknameProvider
            return inst
        }
    }
}
