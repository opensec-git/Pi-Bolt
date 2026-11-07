package opensec.pibolt.app.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.str
import java.net.URI
import java.util.Locale

/** A release of the Android app, as android.json (or the GitHub release) describes it. */
data class UpdateInfo(
    val version: String,
    val apk: String,
    val page: String?,
    val size: Long,
    /** Lowercase hex, 64 characters. */
    val sha256: String,
    /** The APK signing certificate's SHA-256, lowercase hex. */
    val certSha256: String,
)

/**
 * Where updates come from. [RELEASE] is the only source a release build uses; a debug build may point both at a test
 * server (BuildConfig.UPDATE_MANIFEST_OVERRIDE / UPDATE_APK_PREFIX_OVERRIDE).
 */
data class UpdateSource(val manifestUrl: String, val apkPrefix: String, val githubReleasesUrl: String?) {
    companion object {
        val RELEASE = UpdateSource(
            manifestUrl = "https://pi-bolt.opensec.in/android.json",
            apkPrefix = "https://github.com/opensec-git/Pi-Bolt/releases/download/",
            githubReleasesUrl = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases?per_page=100",
        )
    }
}

/** Numeric dotted versions: "0.2.10" > "0.2.9". A leading "v" or "android-v" is ignored. */
object AppVersion {
    private val PATTERN = Regex("^\\d{1,6}(\\.\\d{1,6}){0,3}$")

    fun normalize(raw: String): String? = raw.trim().removePrefix("android-").removePrefix("v").takeIf { PATTERN.matches(it) }

    /** Negative, zero or positive as [a] is older than, the same as or newer than [b]; null when either is not a version. */
    fun compare(a: String, b: String): Int? {
        val x = normalize(a)?.split('.')?.map(String::toInt) ?: return null
        val y = normalize(b)?.split('.')?.map(String::toInt) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

object UpdateRules {
    /** An APK is never larger than this; anything bigger is refused before it is downloaded. */
    const val MAX_APK_BYTES = 1L shl 30
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * True when [url] is under [prefix]: same scheme, host and port, a path that starts with the prefix's path, no user
     * info, query, fragment, `..` or encoded separators, and a file name ending in `.apk`.
     */
    fun isAllowedApkUrl(url: String, prefix: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val p = runCatching { URI(prefix) }.getOrNull() ?: return false
        if (!u.isAbsolute || u.isOpaque) return false
        if (!u.scheme.equals(p.scheme, ignoreCase = true)) return false
        if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return false
        if (u.host == null || !u.host.equals(p.host, ignoreCase = true) || u.port != p.port) return false
        val path = u.rawPath ?: return false
        if (path.contains('%') || path.contains('\\') || path.contains("//")) return false
        if (path.split('/').any { it == ".." || it == "." }) return false
        return path.startsWith(p.rawPath) && path.length > p.rawPath.length && path.endsWith(".apk")
    }

    private fun hex(value: String?): String? = value?.trim()?.lowercase()?.replace(":", "")?.takeIf { HEX64.matches(it) }

    /** android.json. Fails with a short reason when a field is missing or the APK is not on the allowed path. */
    fun parseManifest(text: String, source: UpdateSource): Result<UpdateInfo> = runCatching {
        val json = ProtocolJson.parseToJsonElement(text) as? JsonObject ?: error("not a JSON object")
        val version = json.str("version")?.let(AppVersion::normalize) ?: error("no version")
        val apk = json.str("apk") ?: error("no apk")
        require(isAllowedApkUrl(apk, source.apkPrefix)) { "the APK is not on Pi-Bolt's GitHub releases" }
        val size = json.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: error("no size")
        val sha = hex(json.str("sha256")) ?: error("no sha256")
        val cert = hex(json.str("certSha256")) ?: error("no certSha256")
        UpdateInfo(version, apk, json.str("page"), size, sha, cert)
    }

    /**
     * The newest `android-v*` release from GitHub's release list: the APK asset, its digest as GitHub computed it and
     * the signing certificate digest from the release notes ("certificate SHA-256 digest: <hex>").
     */
    fun parseGithubReleases(text: String, source: UpdateSource): Result<UpdateInfo> = runCatching {
        val releases = ProtocolJson.parseToJsonElement(text) as? JsonArray ?: error("not a release list")
        val candidates = releases.mapNotNull { element ->
            val r = element as? JsonObject ?: return@mapNotNull null
            if (r.bool("draft") == true || r.bool("prerelease") == true) return@mapNotNull null
            val tag = r.str("tag_name") ?: return@mapNotNull null
            if (!tag.startsWith("android-v")) return@mapNotNull null
            val version = AppVersion.normalize(tag) ?: return@mapNotNull null
            version to r
        }
        val (version, release) = candidates.maxWithOrNull { a, b -> AppVersion.compare(a.first, b.first) ?: 0 } ?: error("no Android release")
        val asset = release.arr("assets")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name") == "pi-bolt-android-$version.apk" }
            ?: error("the release has no APK")
        val apk = asset.str("browser_download_url") ?: error("no download URL")
        require(isAllowedApkUrl(apk, source.apkPrefix)) { "the APK is not on Pi-Bolt's GitHub releases" }
        val size = asset.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: error("no size")
        val sha = hex(asset.str("digest")?.removePrefix("sha256:")) ?: error("no digest")
        val cert = hex(Regex("SHA-256 digest:\\s*([0-9a-fA-F:]{64,95})").find(release.str("body").orEmpty())?.groupValues?.get(1))
            ?: error("no signing certificate in the release notes")
        UpdateInfo(version, apk, release.str("html_url"), size, sha, cert)
    }

    /** What the app shows for the newest release it knows of. */
    sealed interface Decision {
        data object UpToDate : Decision

        /** [snoozed]: the user chose Later for this version; Home stays quiet, Settings still offers it. */
        data class Available(val info: UpdateInfo, val snoozed: Boolean) : Decision

        /**
         * A newer release exists, but this copy is signed with another key than the release (a test build): Android
         * would refuse the release over it, so it is not offered; Settings points to the website instead.
         */
        data class TestBuild(val info: UpdateInfo) : Decision
    }

    /** Where a test build gets the release from. */
    const val WEBSITE = "https://pi-bolt.opensec.in/android"

    /** SHA-256 of the certificate OpenSec signs release APKs with. */
    const val RELEASE_CERT = "f752464c8b6d5906d7fd467346b89316518e3ddfe77843f66b1fa673818a2c90"

    /** A copy signed with neither the release key nor the key [latest] names: a test build. Empty [certs]: not read yet. */
    fun isTestBuild(certs: Set<String>, latest: UpdateInfo): Boolean = certs.isNotEmpty() && RELEASE_CERT !in certs && latest.certSha256 !in certs

    /** [installedCerts]: this app's signing certificates (SHA-256, lowercase hex); empty while not read yet. */
    fun decide(installed: String, latest: UpdateInfo?, snoozedVersion: String?, installedCerts: Set<String> = emptySet()): Decision {
        latest ?: return Decision.UpToDate
        val newer = (AppVersion.compare(latest.version, installed) ?: return Decision.UpToDate) > 0
        if (!newer) return Decision.UpToDate
        if (isTestBuild(installedCerts, latest)) return Decision.TestBuild(latest)
        val snoozed = snoozedVersion != null && AppVersion.compare(snoozedVersion, latest.version) == 0
        return Decision.Available(latest, snoozed)
    }

    /** The automatic check runs on launch at most once a day (and never when turned off). */
    fun autoCheckDue(enabled: Boolean, lastCheckAt: Long, now: Long): Boolean =
        enabled && (lastCheckAt <= 0 || now - lastCheckAt >= CHECK_INTERVAL_MS || now < lastCheckAt)

    /** "109 MB": mebibytes, as Android's own size labels and the download page show them. */
    fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
            mb < 10 -> String.format(Locale.US, "%.1f MB", mb)
            else -> "${Math.round(mb)} MB"
        }
    }
}
