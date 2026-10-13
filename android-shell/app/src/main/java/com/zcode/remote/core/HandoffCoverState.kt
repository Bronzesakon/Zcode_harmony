package com.zcode.remote.core

/** Main-thread presentation ownership; a cover never changes the page's protocol. */
class HandoffCoverState {
    var visible = false
        private set
    var returning = false
        private set
    var carrierEpoch = -1L
        private set
    private var sourceDocument = ""
    private var candidateDocument = ""
    private var activeDocument = ""
    private val retiredDocuments = mutableSetOf<String>()
    private var loadStarted = false
    private var revision = 0L
    private var rejectedCarrierEpoch = -1L

    fun carrierMatched(epoch: Long, away: Boolean, document: String): Boolean {
        if (!away || document.isEmpty() || epoch <= rejectedCarrierEpoch) return false
        if (visible && carrierEpoch == epoch) return false
        carrierEpoch = epoch
        sourceDocument = document
        candidateDocument = ""
        activeDocument = ""
        retiredDocuments.clear()
        retiredDocuments.add(document)
        loadStarted = false
        returning = false
        visible = true
        revision += 1
        return true
    }

    fun beginReturn(): Long? {
        if (!visible || returning) return null
        returning = true
        return carrierEpoch
    }

    fun pageStarted() {
        if (!visible || !returning) return
        loadStarted = true
        if (activeDocument.isNotEmpty()) retiredDocuments.add(activeDocument)
        activeDocument = ""
        candidateDocument = ""
        revision += 1
    }

    fun pageReady(document: String) {
        if (!visible || !returning || !loadStarted || document.isEmpty() || document in retiredDocuments) return
        activeDocument = document
    }

    /** A fresh, paired control view still needs WebView's visual commit. */
    fun contentCandidate(document: String): Long? {
        if (!visible || !returning || !loadStarted || document.isEmpty() || document != activeDocument) return null
        candidateDocument = document
        return revision
    }

    fun pagePresented(document: String, ticket: Long): Boolean {
        if (!visible || !returning || ticket != revision || document != candidateDocument) return false
        return clear()
    }

    fun pageFailed(document: String, reportedForeground: Boolean): Boolean {
        if (!visible || document.isEmpty()) return false
        if (document != sourceDocument && document in retiredDocuments) return false
        // The old document can deliver its own background KICK after return.
        if (document == sourceDocument && !reportedForeground) return false
        return clear()
    }

    fun carrierKicked(epoch: Long): Boolean {
        rejectedCarrierEpoch = maxOf(rejectedCarrierEpoch, epoch)
        if (carrierEpoch != epoch) return false
        return clear()
    }

    fun expire(epoch: Long): Boolean {
        if (!visible || !returning || carrierEpoch != epoch) return false
        return clear()
    }

    fun clear(): Boolean {
        if (!visible) return false
        visible = false
        returning = false
        candidateDocument = ""
        activeDocument = ""
        retiredDocuments.clear()
        revision += 1
        return true
    }

    companion object {
        const val RETURN_TIMEOUT_MS = 15_000L
    }
}
