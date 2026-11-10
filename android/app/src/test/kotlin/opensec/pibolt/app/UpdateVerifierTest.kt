package opensec.pibolt.app

import opensec.pibolt.app.update.PackageFacts
import opensec.pibolt.app.update.PackageInspector
import opensec.pibolt.app.update.UpdateInfo
import opensec.pibolt.app.update.UpdateVerifier
import opensec.pibolt.app.update.VerifyResult
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateVerifierTest {
    private val ours = "f752464c8b6d5906d7fd467346b89316518e3ddfe77843f66b1fa673818a2c90"
    private val other = "0".repeat(64)
    private val pkg = "in.opensec.pibolt"
    private val file: File = File.createTempFile("update", ".apk").apply { writeText("pretend apk") }
    private val sha = UpdateVerifier.sha256(file)
    private val installed = PackageFacts(pkg, 201, setOf(ours))

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private class FakeInspector(val archive: PackageFacts?, val installed: PackageFacts) : PackageInspector {
        override fun archive(file: File) = archive

        override fun installed() = installed
    }

    private fun info(sha256: String = sha, cert: String = ours) = UpdateInfo("0.2.2", "https://example/a.apk", null, file.length(), sha256, cert)

    private fun verify(archive: PackageFacts?, info: UpdateInfo = info(), installed: PackageFacts = this.installed) =
        UpdateVerifier.verify(file, info, FakeInspector(archive, installed))

    @Test
    fun hashesFiles() {
        val empty = File.createTempFile("empty", ".bin")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateVerifier.sha256(empty))
        empty.delete()
    }

    @Test
    fun acceptsANewerBuildSignedByTheSameKey() {
        assertEquals(VerifyResult.Ok, verify(PackageFacts(pkg, 202, setOf(ours))))
    }

    @Test
    fun refusesAWrongChecksum() {
        val result = verify(PackageFacts(pkg, 202, setOf(ours)), info(sha256 = other))
        assertEquals(VerifyResult.Refused("The download does not match the published checksum; not installed."), result)
    }

    @Test
    fun refusesAnotherSigner() {
        val notOpenSec = VerifyResult.Refused("This update is not signed by OpenSec; not installed.")
        // Signed by another key than android.json names.
        assertEquals(notOpenSec, verify(PackageFacts(pkg, 202, setOf(other))))
        // android.json names the other key too (a replaced manifest): the installed app's own key still decides.
        assertEquals(notOpenSec, verify(PackageFacts(pkg, 202, setOf(other)), info(cert = other)))
        // An extra signer, or none.
        assertEquals(notOpenSec, verify(PackageFacts(pkg, 202, setOf(ours, other))))
        assertEquals(notOpenSec, verify(PackageFacts(pkg, 202, emptySet())))
    }

    @Test
    fun aTestBuildIsToldWhyTheReleaseCannotGoOverIt() {
        // The owner's Xiaomi: a debug-signed install, a genuine release archive. The archive is fine; the install is not.
        val debug = PackageFacts(pkg, 1, setOf("d".repeat(64)))
        assertEquals(VerifyResult.Refused(UpdateVerifier.TEST_BUILD), verify(PackageFacts(pkg, 202, setOf(ours)), installed = debug))
        // A tampered archive over the same test build is still refused for its signature.
        assertEquals(VerifyResult.Refused(UpdateVerifier.NOT_OPENSEC), verify(PackageFacts(pkg, 202, setOf(other)), installed = debug))
        // Release over release is unchanged.
        assertEquals(VerifyResult.Ok, verify(PackageFacts(pkg, 202, setOf(ours))))
    }

    @Test
    fun refusesAnotherPackage() {
        val result = verify(PackageFacts("com.example.other", 202, setOf(ours)))
        assertEquals(VerifyResult.Refused("The download is a different app (com.example.other); not installed."), result)
    }

    @Test
    fun refusesSameOrOlderVersionCodes() {
        val notNewer = VerifyResult.Refused("The download is not newer than this app; not installed.")
        assertEquals(notNewer, verify(PackageFacts(pkg, 201, setOf(ours))))
        assertEquals(notNewer, verify(PackageFacts(pkg, 105, setOf(ours))))
    }

    @Test
    fun refusesFilesAndroidCannotRead() {
        assertEquals(VerifyResult.Refused("The download is not a valid Android app; not installed."), verify(null))
    }

    @Test
    fun refusesAMissingFile() {
        file.delete()
        assertEquals(VerifyResult.Refused("The download is missing; try again."), verify(PackageFacts(pkg, 202, setOf(ours))))
    }
}
