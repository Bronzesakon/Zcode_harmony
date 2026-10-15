package com.zcode.remote.core

import java.net.URI
import java.security.MessageDigest

/**
 * A build-pinned presentation patch for the remote page's eager history loading.
 * The page's projection store, row pagination RPC and live updates remain intact.
 * Unknown deployments must be served unchanged by the caller.
 */
object RemoteHistoryPatch {
    const val ASSET_PATH = "/remote/v4/3.15.3/assets/index-BGa3KiRe.js"
    const val SOURCE_SHA256 = "ce2e2fc490d6364d21630e9f510b99f48748958e392724c0c9faf8fb5f9b88fd"

    fun matchesUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("zcode.z.ai", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == ASSET_PATH
    } catch (_: Exception) {
        false
    }

    fun patch(source: String): String? {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (digest != SOURCE_SHA256) return null
        return patchAnchors(source)
    }

    // Kept separate so CI can exercise fail-open/unique-anchor behavior with small fixtures.
    internal fun patchAnchors(source: String): String? {
        if (replacements.any { (before, _) ->
                val first = source.indexOf(before)
                first < 0 || source.indexOf(before, first + before.length) >= 0
            }) return null
        var result = source
        for ((before, after) in replacements) result = result.replace(before, after)
        return result
    }

    internal val replacements: List<Pair<String, String>> = listOf(
        // A cold tail is allowed to start halfway through a long turn. Do not fetch
        // hundreds of earlier tool rows merely to find that turn's header.
        "if(!t||!Ne?.store||!H1e(Ie,Fe.loadingOlder))return;" to
            "if(true||!t||!Ne?.store||!H1e(Ie,Fe.loadingOlder))return;",
        // Layout/virtualizer-generated scroll events also entered this branch.
        // Searches and explicit turn navigation use different callbacks and stay live.
        "if(prn({scrollTop:e.scrollTop,canLoadOlder:r.canLoadOlder,loadingOlder:r.loadingOlder,triggerPx:i}))" to
            "if(false&&prn({scrollTop:e.scrollTop,canLoadOlder:r.canLoadOlder,loadingOlder:r.loadingOlder,triggerPx:i}))",
        // The wide layout otherwise hydrates every historical query on mount.
        // Explicit share hydration and search-driven pagination are separate calls.
        "if(!qtn({canLoadOlder:f,containerWidthPx:He,hasLoadHandler:!!h,loadingOlder:p}))return;" to
            "if(true||!qtn({canLoadOlder:f,containerWidthPx:He,hasLoadHandler:!!h,loadingOlder:p}))return;",
        // Reuse the page's measured header slot, so the manual control contributes
        // to scrollMargin and cannot introduce an unmeasured virtualizer offset.
        "R=(0,Q.useRef)(null),z=(0,Q.useRef)(null),B=!!x,[ee,V]=" to
            "R=(0,Q.useRef)(null),z=(0,Q.useRef)(null),B=!!x||f,[ee,V]=",
        "x?(0,${'$'}.jsx)(`div`,{ref:z,\"data-v4-timeline-header-slot\":`true`,\"data-v4-timeline-content-column\":`true`,className:J(`relative mx-auto w-full shrink-0`,Qe,Ze),children:x}):null" to
            "(x||f)?(0,${'$'}.jsx)(`div`,{ref:z,\"data-v4-timeline-header-slot\":`true`,\"data-v4-timeline-content-column\":`true`,className:J(`relative mx-auto w-full shrink-0`,Qe,Ze),children:[f?(0,${'$'}.jsx)(`button`,{type:`button`,\"data-zcode-shell-history\":`manual`,disabled:p,className:`mx-auto my-2 block rounded-md border border-border px-3 py-1.5 text-xs text-foreground-subtle disabled:opacity-50`,onClick:()=>{let e=R.current,t=e?at.getVirtualItemForOffset(e.scrollTop):void 0,n=t?pe.current[t.index]:void 0;Ie.current=e&&t&&n?.key===t.key?{key:n.key,offsetTop:t.start-e.scrollTop,start:t.start}:null,m?.()},children:p?`加载中…`:`加载更早历史`}):null,x]}):null",
    )
}
