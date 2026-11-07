package opensec.pibolt.app

import opensec.pibolt.app.update.AppVersion
import opensec.pibolt.app.update.UpdateFile
import opensec.pibolt.app.update.UpdateFileCodec
import opensec.pibolt.app.update.UpdateInfo
import opensec.pibolt.app.update.UpdateRules
import opensec.pibolt.app.update.UpdateRules.Decision
import opensec.pibolt.app.update.UpdateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateRulesTest {
    private val source = UpdateSource.RELEASE
    private val sha = "1b63214818d9e910241e51f954e7c076e1a9d90b61609c2a2c71ef2b80a17b25"
    private val cert = "f752464c8b6d5906d7fd467346b89316518e3ddfe77843f66b1fa673818a2c90"
    private val apk = "https://github.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/pi-bolt-android-0.2.2.apk"

    private fun manifest(apkUrl: String = apk, version: String = "0.2.2", shaValue: String = sha, certValue: String = cert, size: String = "114423412") =
        """{"version":"$version","apk":"$apkUrl","page":"https://github.com/opensec-git/Pi-Bolt/releases/tag/android-v$version","size":$size,"sha256":"$shaValue","certSha256":"$certValue"}"""

    private fun info(version: String) = UpdateInfo(version, apk, null, 1, sha, cert)

    @Test
    fun comparesVersionsNumerically() {
        assertEquals(0, AppVersion.compare("0.2.1", "0.2.1"))
        assertTrue(AppVersion.compare("0.2.10", "0.2.9")!! > 0)
        assertTrue(AppVersion.compare("0.2.2", "0.2.10")!! < 0)
        assertTrue(AppVersion.compare("1.0.0", "0.99.99")!! > 0)
        assertEquals(0, AppVersion.compare("0.2", "0.2.0"))
        assertTrue(AppVersion.compare("0.2.0.1", "0.2.0")!! > 0)
        assertEquals(0, AppVersion.compare("v0.2.1", "android-v0.2.1"))
    }

    @Test
    fun refusesNonVersions() {
        assertNull(AppVersion.compare("0.2.1-beta", "0.2.1"))
        assertNull(AppVersion.compare("", "0.2.1"))
        assertNull(AppVersion.compare("0..1", "0.2.1"))
        assertNull(AppVersion.normalize("1.2.3.4.5"))
        assertNull(AppVersion.normalize("latest"))
    }

    @Test
    fun parsesAndroidJson() {
        val parsed = UpdateRules.parseManifest(manifest(), source).getOrThrow()
        assertEquals(UpdateInfo("0.2.2", apk, "https://github.com/opensec-git/Pi-Bolt/releases/tag/android-v0.2.2", 114423412, sha, cert), parsed)
    }

    @Test
    fun normalizesDigestCase() {
        val parsed = UpdateRules.parseManifest(manifest(shaValue = sha.uppercase(), certValue = cert.uppercase()), source).getOrThrow()
        assertEquals(sha, parsed.sha256)
        assertEquals(cert, parsed.certSha256)
    }

    @Test
    fun refusesIncompleteOrDamagedJson() {
        assertTrue(UpdateRules.parseManifest("not json", source).isFailure)
        assertTrue(UpdateRules.parseManifest("[]", source).isFailure)
        assertTrue(UpdateRules.parseManifest(manifest(version = "next"), source).isFailure)
        assertTrue(UpdateRules.parseManifest(manifest(shaValue = "abc"), source).isFailure)
        assertTrue(UpdateRules.parseManifest(manifest(certValue = ""), source).isFailure)
        assertTrue(UpdateRules.parseManifest(manifest(size = "0"), source).isFailure)
        assertTrue(UpdateRules.parseManifest(manifest(size = "5000000000"), source).isFailure)
        assertTrue(UpdateRules.parseManifest("""{"version":"0.2.2","apk":"$apk","size":1,"sha256":"$sha"}""", source).isFailure)
    }

    @Test
    fun acceptsOnlyPiBoltReleaseDownloads() {
        val prefix = source.apkPrefix
        assertTrue(UpdateRules.isAllowedApkUrl(apk, prefix))
        assertTrue(UpdateRules.isAllowedApkUrl("https://GitHub.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/x.apk", prefix))
        val refused = listOf(
            // Other hosts and look-alikes.
            "https://evil.example/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/pi-bolt-android-0.2.2.apk",
            "https://github.com.evil.example/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/a.apk",
            "https://objects.githubusercontent.com/opensec-git/Pi-Bolt/releases/download/a.apk",
            "https://github.com@evil.example/opensec-git/Pi-Bolt/releases/download/a.apk",
            "https://user@github.com/opensec-git/Pi-Bolt/releases/download/a.apk",
            "https://github.com:8443/opensec-git/Pi-Bolt/releases/download/a.apk",
            // Other paths: another repository, another fork, outside releases/download, traversal, encoding.
            "https://github.com/someone/Pi-Bolt/releases/download/android-v0.2.2/pi-bolt-android-0.2.2.apk",
            "https://github.com/opensec-git/Pi-Bolt-fork/releases/download/a.apk",
            "https://github.com/opensec-git/Pi-Bolt/raw/main/a.apk",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/../../../someone/x/releases/download/a.apk",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/%2e%2e/a.apk",
            "https://github.com/opensec-git/Pi-Bolt/releases/download//a.apk",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/notes.txt",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/a.apk?redirect=https://evil.example/a.apk",
            "https://github.com/opensec-git/Pi-Bolt/releases/download/a.apk#x",
            // Plain HTTP and other schemes.
            "http://github.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/pi-bolt-android-0.2.2.apk",
            "file:///sdcard/pi-bolt.apk",
            "content://downloads/a.apk",
            "/opensec-git/Pi-Bolt/releases/download/a.apk",
            "not a url",
        )
        for (url in refused) {
            assertFalse(UpdateRules.isAllowedApkUrl(url, prefix), url)
            assertTrue(UpdateRules.parseManifest(manifest(apkUrl = url), source).isFailure, url)
        }
    }

    @Test
    fun debugSourceAllowsOnlyItsOwnPrefix() {
        val test = UpdateSource("http://10.0.2.2:8765/android.json", "http://10.0.2.2:8765/apk/", null)
        assertTrue(UpdateRules.isAllowedApkUrl("http://10.0.2.2:8765/apk/new.apk", test.apkPrefix))
        assertFalse(UpdateRules.isAllowedApkUrl("http://10.0.2.2:8766/apk/new.apk", test.apkPrefix))
        assertFalse(UpdateRules.isAllowedApkUrl("http://10.0.2.2:8765/other/new.apk", test.apkPrefix))
        assertFalse(UpdateRules.isAllowedApkUrl("http://10.0.2.2:8765/apk/new.apk", source.apkPrefix))
    }

    @Test
    fun readsTheNewestAndroidReleaseFromGithub() {
        val releases = """[
          {"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"","assets":[]},
          {"tag_name":"android-v0.2.10","draft":true,"prerelease":false,"body":"","assets":[]},
          {"tag_name":"android-v0.2.3","draft":false,"prerelease":true,"body":"","assets":[]},
          {"tag_name":"android-v0.2.1","draft":false,"prerelease":false,"html_url":"https://github.com/opensec-git/Pi-Bolt/releases/tag/android-v0.2.1","body":"",
           "assets":[{"name":"pi-bolt-android-0.2.1.apk","size":1,"digest":"sha256:$sha","browser_download_url":"https://github.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.1/pi-bolt-android-0.2.1.apk"}]},
          {"tag_name":"android-v0.2.2","draft":false,"prerelease":false,"html_url":"https://github.com/opensec-git/Pi-Bolt/releases/tag/android-v0.2.2",
           "body":"Signing certificate:\n```\nV3.0 Signer: certificate SHA-256 digest: $cert\n```\n",
           "assets":[
             {"name":"pi-bolt-android-0.2.2.apk.sha256","size":92,"digest":"sha256:$cert","browser_download_url":"https://github.com/opensec-git/Pi-Bolt/releases/download/android-v0.2.2/pi-bolt-android-0.2.2.apk.sha256"},
             {"name":"pi-bolt-android-0.2.2.apk","size":114423412,"digest":"sha256:$sha","browser_download_url":"$apk"}
           ]}
        ]"""
        val parsed = UpdateRules.parseGithubReleases(releases, source).getOrThrow()
        assertEquals(UpdateInfo("0.2.2", apk, "https://github.com/opensec-git/Pi-Bolt/releases/tag/android-v0.2.2", 114423412, sha, cert), parsed)
    }

    @Test
    fun githubReleaseNeedsTheCertificateAndAnAllowedUrl() {
        fun release(body: String, url: String) = """[{"tag_name":"android-v0.2.2","draft":false,"prerelease":false,"body":"$body",
            "assets":[{"name":"pi-bolt-android-0.2.2.apk","size":10,"digest":"sha256:$sha","browser_download_url":"$url"}]}]"""
        assertTrue(UpdateRules.parseGithubReleases(release("no certificate here", apk), source).isFailure)
        assertTrue(UpdateRules.parseGithubReleases(release("certificate SHA-256 digest: $cert", "https://evil.example/a.apk"), source).isFailure)
        assertTrue(UpdateRules.parseGithubReleases(release("certificate SHA-256 digest: $cert", apk), source).isSuccess)
        assertTrue(UpdateRules.parseGithubReleases("[]", source).isFailure)
    }

    @Test
    fun offersOnlyNewerVersions() {
        assertEquals(Decision.Available(info("0.2.2"), snoozed = false), UpdateRules.decide("0.2.1", info("0.2.2"), null))
        assertEquals(Decision.Available(info("0.2.10"), snoozed = false), UpdateRules.decide("0.2.9", info("0.2.10"), null))
        assertEquals(Decision.UpToDate, UpdateRules.decide("0.2.1", info("0.2.1"), null))
        assertEquals(Decision.UpToDate, UpdateRules.decide("0.2.2", info("0.2.1"), null))
        assertEquals(Decision.UpToDate, UpdateRules.decide("0.2.1", null, null))
        assertEquals(Decision.UpToDate, UpdateRules.decide("dev", info("0.2.2"), null))
    }

    @Test
    fun aTestBuildIsPointedAtTheWebsiteInsteadOfOffered() {
        val debug = setOf("d".repeat(64))
        assertEquals(Decision.TestBuild(info("0.5.3")), UpdateRules.decide("0.1.0", info("0.5.3"), null, debug))
        assertEquals(Decision.UpToDate, UpdateRules.decide("0.5.3", info("0.5.3"), null, debug), "nothing newer: nothing to say")
        // A release install is offered the release, as before; so is an install whose certificates were not read yet.
        assertEquals(Decision.Available(info("0.5.3"), snoozed = false), UpdateRules.decide("0.5.2", info("0.5.3"), null, setOf(cert)))
        assertEquals(Decision.Available(info("0.5.3"), snoozed = false), UpdateRules.decide("0.5.2", info("0.5.3"), null, emptySet()))
        // A test server (debug builds) that publishes builds signed like the install: offered.
        assertEquals(Decision.Available(info("0.5.3").copy(certSha256 = "d".repeat(64)), snoozed = false), UpdateRules.decide("0.5.2", info("0.5.3").copy(certSha256 = "d".repeat(64)), null, debug))
    }

    @Test
    fun laterSnoozesOnlyThatVersion() {
        assertEquals(Decision.Available(info("0.2.2"), snoozed = true), UpdateRules.decide("0.2.1", info("0.2.2"), "0.2.2"))
        assertEquals(Decision.Available(info("0.2.3"), snoozed = false), UpdateRules.decide("0.2.1", info("0.2.3"), "0.2.2"))
        assertEquals(Decision.UpToDate, UpdateRules.decide("0.2.2", info("0.2.2"), "0.2.2"))
    }

    @Test
    fun checksAtMostOnceADay() {
        val day = UpdateRules.CHECK_INTERVAL_MS
        assertTrue(UpdateRules.autoCheckDue(enabled = true, lastCheckAt = 0, now = 1_000))
        assertFalse(UpdateRules.autoCheckDue(enabled = true, lastCheckAt = 1_000, now = 1_000 + day - 1))
        assertTrue(UpdateRules.autoCheckDue(enabled = true, lastCheckAt = 1_000, now = 1_000 + day))
        assertTrue(UpdateRules.autoCheckDue(enabled = true, lastCheckAt = 5 * day, now = day), "clock moved back")
        assertFalse(UpdateRules.autoCheckDue(enabled = false, lastCheckAt = 0, now = 1_000))
    }

    @Test
    fun formatsSizes() {
        assertEquals("109 MB", UpdateRules.formatSize(114423412))
        assertEquals("4.4 MB", UpdateRules.formatSize(4562683))
        assertEquals("1 KB", UpdateRules.formatSize(10))
    }

    @Test
    fun updateFileRoundTripsAndRevalidates() {
        val f = UpdateFile(lastCheckAt = 42, latest = UpdateRules.parseManifest(manifest(), source).getOrThrow(), snoozedVersion = "0.2.2", lastRunVersion = "0.2.1")
        assertEquals(f, UpdateFileCodec.decode(UpdateFileCodec.encode(f), source))
        assertEquals(UpdateFile(), UpdateFileCodec.decode("{broken", source))
        // A stored release whose APK is not on the allowed path is dropped on load.
        val tampered = UpdateFileCodec.encode(f).replace("https://github.com/opensec-git/", "https://evil.example/opensec-git/")
        assertNull(UpdateFileCodec.decode(tampered, source).latest)
    }
}
