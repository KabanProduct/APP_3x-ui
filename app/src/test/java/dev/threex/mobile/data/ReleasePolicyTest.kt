package dev.threex.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private fun release() = JSONObject().put("tag_name", "v0.10.0").put("body", "Changes")
        .put("assets", JSONArray().put(JSONObject().put("name", "3x-mobile-arm64-v8a.apk")
            .put("browser_download_url", "https://github.com/KabanProduct/APP_3x-ui/releases/download/v0.10.0/3x-mobile-arm64-v8a.apk")
            .put("size", 1234).put("digest", "sha256:" + "ab".repeat(32))))
    private fun asset(json: JSONObject) = json.getJSONArray("assets").getJSONObject(0)
    @Test fun numericVersionOrderingAndEqualVersions() {
        assertTrue(ReleasePolicy.newer("v0.10.0", "0.9.9"))
        assertFalse(ReleasePolicy.newer("0.2.0", "0.2.0"))
        assertFalse(ReleasePolicy.newer("0.9.9", "1.0.0"))
        assertEquals("0.10.0", ReleasePolicy.parse(release(), "arm64-v8a", "0.2.0")!!.version)
    }
    @Test fun draftAndPrereleaseAreNotUpdates() {
        assertNull(ReleasePolicy.parse(release().put("draft", true), "arm64-v8a", "0.2.0"))
        assertNull(ReleasePolicy.parse(release().put("prerelease", true), "arm64-v8a", "0.2.0"))
        assertNull(ReleasePolicy.parse(release(), "arm64-v8a", "1.0.0"))
    }
    @Test fun rejectsOtherRepositoryAndInsecureDownload() {
        for (url in listOf("https://evil.example/app.apk", "http://github.com/KabanProduct/APP_3x-ui/releases/download/v0.10.0/3x-mobile-arm64-v8a.apk", "https://github.com/other/app/releases/download/v0.10.0/3x-mobile-arm64-v8a.apk")) {
            val json = release(); asset(json).put("browser_download_url", url)
            assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.parse(json, "arm64-v8a", "0.2.0") }
        }
    }
    @Test fun checksumIsRequiredAndValidated() {
        val json = release(); asset(json).put("digest", "md5:1234")
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.parse(json, "arm64-v8a", "0.2.0") }
    }
    @Test fun oversizedAndEmptyArtifactsAreRejected() {
        for (size in listOf(0L, -1L, ReleasePolicy.MAX_APK_BYTES + 1)) {
            val json = release(); asset(json).put("size", size)
            assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.parse(json, "arm64-v8a", "0.2.0") }
        }
    }
    @Test fun incompatibleArchitectureIsNotSilentlySelected() {
        assertThrows(IllegalStateException::class.java) { ReleasePolicy.parse(release(), "x86_64", "0.2.0") }
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.parse(release(), "armeabi-v7a", "0.2.0") }
    }
    @Test fun malformedTagsAreRejected() {
        for (tag in listOf("v1.0", "v1.0.0/evil", "v1.0.0-rc1", "v99999999999.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.version(tag) }
        }
    }
}
