package com.zcode.remote.core

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteHistoryPatchTest {
    private val sourceFixture: String
        get() = RemoteHistoryPatch.replacements.joinToString("\n") { it.first }

    @Test
    fun `only the audited HTTPS asset is selected`() {
        val path = RemoteHistoryPatch.ASSET_PATH
        assertTrue(RemoteHistoryPatch.matchesUrl("https://zcode.z.ai$path"))
        assertTrue(RemoteHistoryPatch.matchesUrl("https://zcode.z.ai:443$path"))
        for (url in listOf(
            "http://zcode.z.ai$path", "https://zcode.z.ai:8443$path",
            "https://other.z.ai$path", "https://zcode.z.ai.evil.test$path",
            "https://user@zcode.z.ai$path", "https://zcode.z.ai$path?token=hidden",
            "https://zcode.z.ai$path#fragment", "https://zcode.z.ai/remote/v4/latest/assets/index-BGa3KiRe.js",
            "https://zcode.z.ai/remote/v4/3.15.4/assets/index-BGa3KiRe.js", "not a url",
        )) assertFalse(url, RemoteHistoryPatch.matchesUrl(url))
    }

    @Test
    fun `changed bytes fail open even if every source anchor remains`() {
        assertNull(RemoteHistoryPatch.patch(sourceFixture))
        assertNull(RemoteHistoryPatch.patch("/* changed deployment */$sourceFixture"))
    }

    @Test
    fun `any missing or duplicated anchor rejects the entire patch`() {
        for ((anchor, _) in RemoteHistoryPatch.replacements) {
            assertNull(RemoteHistoryPatch.patchAnchors(sourceFixture.replace(anchor, "")))
            assertNull(RemoteHistoryPatch.patchAnchors("$sourceFixture\n$anchor"))
        }
    }

    @Test
    fun `manual loading uses page callback and preserves measured prepend anchor`() {
        val result = RemoteHistoryPatch.patchAnchors(sourceFixture)
        assertNotNull(result)
        result!!
        assertTrue(result.contains("B=!!x||f"))
        assertTrue(result.contains("\"data-zcode-shell-history\":`manual`,disabled:p"))
        assertTrue(result.contains("at.getVirtualItemForOffset(e.scrollTop)"))
        assertTrue(result.contains("offsetTop:t.start-e.scrollTop,start:t.start}:null,m?.()"))
        assertTrue(result.contains("children:p?`加载中…`:`加载更早历史`"))
        for ((_, replacement) in RemoteHistoryPatch.replacements) assertTrue(result.contains(replacement))
    }

    @Test
    fun `row store live stream share hydration and explicit search remain untouched`() {
        val pageBehavior = "async loadOlder(e=W.snapshotTailWindowRows){originalPagination()}" +
            "async loadAllOlder(){shareHydration()}" +
            "Frn({canLoadOlder:f,onLoadOlder:m});handleFrame(frame);"
        val result = RemoteHistoryPatch.patchAnchors(sourceFixture + pageBehavior)!!
        assertEquals(pageBehavior, result.takeLast(pageBehavior.length))
        assertNull(RemoteHistoryPatch.patchAnchors(result))
    }

    @Test
    fun `CI downloaded production bundle matches and patched module parses`() {
        val sourcePath = System.getenv("REMOTE_HISTORY_SOURCE")
        assumeTrue("CI provides the audited public asset", !sourcePath.isNullOrBlank())
        val patched = RemoteHistoryPatch.patch(File(sourcePath!!).readText(Charsets.UTF_8))
        assertNotNull("Audited asset digest or unique anchors changed; adapter must fail open", patched)
        val module = File.createTempFile("zcode-remote-history-", ".mjs")
        val syntaxLog = File.createTempFile("zcode-remote-history-syntax-", ".log")
        try {
            module.writeText(patched!!, Charsets.UTF_8)
            val process = ProcessBuilder("node", "--check", module.absolutePath)
                .redirectErrorStream(true).redirectOutput(syntaxLog).start()
            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            assertTrue("Node syntax check timed out", completed)
            // A syntax error may print the whole minified line. File redirection
            // prevents pipe backpressure; read only the tail containing the error.
            val output = RandomAccessFile(syntaxLog, "r").use { log ->
                val bytes = ByteArray(minOf(log.length(), 8192L).toInt())
                log.seek(log.length() - bytes.size)
                log.readFully(bytes)
                bytes.toString(Charsets.UTF_8)
            }
            assertEquals(output, 0, process.exitValue())
        } finally {
            module.delete()
            syntaxLog.delete()
        }
    }
}
