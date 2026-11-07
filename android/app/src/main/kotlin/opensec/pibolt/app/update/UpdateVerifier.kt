package opensec.pibolt.app.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What the verifier needs to know about a package: name, version code and its current signing certificates. */
data class PackageFacts(val packageName: String, val versionCode: Long, val certSha256: Set<String>)

/** The package-info part of verification, so the rules can be tested without Android. */
interface PackageInspector {
    /** The APK file's facts, or null when Android cannot read it as a package. */
    fun archive(file: File): PackageFacts?

    /** This app, as installed. */
    fun installed(): PackageFacts
}

sealed interface VerifyResult {
    data object Ok : VerifyResult

    /** Why the file was refused; shown to the user as is. */
    data class Refused(val message: String) : VerifyResult
}

/**
 * Checks a downloaded APK before it may be installed: its SHA-256 against android.json, then (from the package
 * manager) the package name, a signing certificate equal to android.json's, then equal to the installed app's own (a
 * test build signed with another key cannot take the release), and a higher version code. The first failure wins.
 */
object UpdateVerifier {
    const val NOT_OPENSEC = "This update is not signed by OpenSec; not installed."
    const val TEST_BUILD = "This copy of Pi-Bolt is a test build signed with another key. Install the update from pi-bolt.opensec.in."

    fun verify(file: File, info: UpdateInfo, inspector: PackageInspector): VerifyResult {
        if (!file.isFile) return VerifyResult.Refused("The download is missing; try again.")
        if (sha256(file) != info.sha256) return VerifyResult.Refused("The download does not match the published checksum; not installed.")
        val archive = inspector.archive(file) ?: return VerifyResult.Refused("The download is not a valid Android app; not installed.")
        val installed = inspector.installed()
        if (archive.packageName != installed.packageName) return VerifyResult.Refused("The download is a different app (${archive.packageName}); not installed.")
        if (archive.certSha256.isEmpty() || archive.certSha256 != setOf(info.certSha256)) {
            return VerifyResult.Refused(NOT_OPENSEC)
        }
        // Android installs an update only over a copy signed with the same key. Over a test build the release is refused
        // for that, not for its signature; over a release, a manifest naming another key is still "not signed by OpenSec".
        if (archive.certSha256 != installed.certSha256) {
            return VerifyResult.Refused(if (UpdateRules.isTestBuild(installed.certSha256, info)) TEST_BUILD else NOT_OPENSEC)
        }
        if (archive.versionCode <= installed.versionCode) return VerifyResult.Refused("The download is not newer than this app; not installed.")
        return VerifyResult.Ok
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** [PackageInspector] backed by PackageManager: GET_SIGNING_CERTIFICATES on API 28+, GET_SIGNATURES below. */
class AndroidPackageInspector(private val context: Context) : PackageInspector {
    override fun archive(file: File): PackageFacts? = runCatching {
        context.packageManager.archiveInfo(file.path, signingFlags())?.let(::facts)
    }.getOrNull()

    override fun installed(): PackageFacts = facts(context.packageManager.packageInfo(context.packageName, signingFlags()))

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else signaturesFlag()

    @Suppress("DEPRECATION")
    private fun signaturesFlag(): Int = PackageManager.GET_SIGNATURES

    private fun facts(info: PackageInfo): PackageFacts {
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
        return PackageFacts(info.packageName, versionCode, currentSigners(info).map(::certDigest).toSet())
    }

    /** The certificates the package is signed with now (with key rotation, the newest one, not its history). */
    @SuppressLint("NewApi")
    private fun currentSigners(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            return if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners?.toList().orEmpty()
            } else {
                listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
            }
        }
        return legacySignatures(info)
    }

    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo): List<Signature> = info.signatures?.toList().orEmpty()

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private fun certDigest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun PackageManager.archiveInfo(path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong())) else legacyArchiveInfo(path, flags)

    @Suppress("DEPRECATION")
    private fun PackageManager.legacyArchiveInfo(path: String, flags: Int): PackageInfo? = getPackageArchiveInfo(path, flags)

    private fun PackageManager.packageInfo(name: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong())) else legacyPackageInfo(name, flags)

    @Suppress("DEPRECATION")
    private fun PackageManager.legacyPackageInfo(name: String, flags: Int): PackageInfo = getPackageInfo(name, flags)
}
