package opensec.pibolt.app.secrets

import android.content.Context
import java.io.File

/** The app's one [ProjectSecrets], in no-backup storage, sealed with the Android Keystore key. */
object SecretStore {
    @Volatile
    private var instance: ProjectSecrets? = null

    fun get(context: Context): ProjectSecrets = instance ?: synchronized(this) {
        instance ?: run {
            val key by lazy { SecretBox.keystoreKey() }
            ProjectSecrets(File(context.applicationContext.noBackupFilesDir, "secrets"), SecretBox { key })
        }.also { instance = it }
    }
}
