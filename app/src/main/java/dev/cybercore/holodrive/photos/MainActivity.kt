package dev.cybercore.holodrive.photos

import android.os.Bundle
import android.view.WindowManager
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private lateinit var credentialVault: CredentialVault
    private var photoModel: PhotoModel? = null
    private var pendingShare: android.content.Intent? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShare = intent
        credentialVault = CredentialVault(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (!BuildConfig.DEBUG || !BuildConfig.ALLOW_SCREENSHOTS) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            val model: PhotoModel = viewModel()
            androidx.compose.runtime.LaunchedEffect(model) {
                photoModel = model
                pendingShare?.let { consumeShareIntent(model, it) }
                pendingShare = null
            }
            val state by model.state.collectAsStateWithLifecycle()
            PhotoTheme(state) {
                PhotoApp(
                    model = model,
                    state = state,
                    rememberedPassword = credentialVault.hasCredential() && !state.hasDeviceToken,
                    savePassword = ::rememberPassword,
                    clearPassword = credentialVault::clear,
                    unlockPassword = { onPassword -> unlockPassword(onPassword) },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val model = photoModel
        if (model == null) pendingShare = intent else consumeShareIntent(model, intent)
    }

    private fun consumeShareIntent(model: PhotoModel, intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_SEND && intent.action != android.content.Intent.ACTION_SEND_MULTIPLE) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        model.stageShareIntent(intent)
    }

    private fun rememberPassword(password: String) {
        val cipher = try { credentialVault.encryptionCipher() } catch (e: Exception) {
            Toast.makeText(this, "Secure password storage is unavailable", Toast.LENGTH_LONG).show()
            return
        }
        authenticate(cipher, "Remember Holodrive password") { authenticated ->
            try {
                credentialVault.store(authenticated, password)
                Toast.makeText(this, "Password saved securely on this device", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Could not save the password securely", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun unlockPassword(onPassword: (String) -> Unit) {
        val cipher = try { credentialVault.decryptionCipher() } catch (_: Exception) { null }
        if (cipher == null) {
            credentialVault.clear()
            return
        }
        authenticate(cipher, "Unlock Holodrive Photos") { authenticated ->
            try { onPassword(credentialVault.read(authenticated)) }
            catch (_: Exception) {
                credentialVault.clear()
                Toast.makeText(this, "Saved password is no longer available. Sign in again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun authenticate(cipher: javax.crypto.Cipher, title: String, onSuccess: (javax.crypto.Cipher) -> Unit) {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle("Confirm with your device fingerprint")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(
            android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onSuccess)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                        Toast.makeText(this@MainActivity, errString, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}
