package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage

/**
 * Shared mesh delegate interface for transport-agnostic callbacks.
 */
interface MeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean

    // Forms fork: survey lifecycle callbacks (default no-ops so non-forms delegates are unaffected)
    fun didReceiveSurvey(survey: com.bitchat.android.survey.Survey, fromPeer: String) {}
    fun didReceiveSurveyResponse(response: com.bitchat.android.survey.SurveyResponse, fromPeer: String) {}
    fun didReceiveSurveyClose(surveyId: String, fromPeer: String) {}
}
