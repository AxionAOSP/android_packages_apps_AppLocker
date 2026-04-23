package com.android.applocker

import android.app.Activity
import android.app.AxSandboxManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.android.applocker.security.SecurityType
import com.android.applocker.security.SandboxSecurityManager
import com.android.applocker.ui.LockScreen
import com.android.applocker.ui.PasswordScreen
import com.android.applocker.ui.PatternScreen
import com.android.applocker.ui.theme.AppLockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthenticateActivity : ComponentActivity() {

    private enum class AuthState { IDLE, PROMPT_SHOWING, EXITING, FINISHED }

    private lateinit var securityManager: SandboxSecurityManager
    private var packageName: String? = null
    private var userId: Int = 0
    private var appLabel: String = "App"

    private var authState: AuthState = AuthState.IDLE
    private var biometricCancellationSignal: CancellationSignal? = null

    private val isExiting = mutableStateOf(false)
    private val hasWindowFocus = mutableStateOf(false)
    private val securitySnapshot = mutableStateOf<SecuritySnapshot?>(null)

    private data class SecuritySnapshot(
        val securityType: SecurityType,
        val biometricType: SandboxSecurityManager.BiometricType,
        val isPreferBiometric: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, lifecycleTag("onCreate") + " savedState=" + (savedInstanceState != null)
                + " intentAction=" + intent?.action + " extras=" + intent?.extras?.keySet())

        setupWindowForOverlay()
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startExitAnimation(success = false)
            }
        })

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LOCKED_PACKAGE)

        userId = intent.getIntExtra(EXTRA_USER_ID, 0).takeIf { it != 0 }
            ?: UserHandle.getUserId(intent.getIntExtra(EXTRA_LOCKED_UID, 0))

        appLabel = intent.getStringExtra(EXTRA_APP_LABEL)
            ?: packageName
            ?: "App"

        securityManager = SandboxSecurityManager(this)

        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                if (!securityManager.isSetup()) return@withContext null
                val bioType = if (securityManager.isBiometricEnabled()
                                  && securityManager.isBiometricAvailable()) {
                    securityManager.getBiometricType()
                } else {
                    SandboxSecurityManager.BiometricType.NONE
                }
                SecuritySnapshot(
                    securityType = securityManager.getSecurityType(),
                    biometricType = bioType,
                    isPreferBiometric = securityManager.isPreferBiometric()
                )
            }
            if (snap == null) {
                unlockAndFinish()
                return@launch
            }
            securitySnapshot.value = snap
        }

        setContent {
            AppLockerTheme {
                val snap = securitySnapshot.value
                val focused = hasWindowFocus.value

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (focused && snap != null) {
                        AuthenticateScreen(
                            securityManager = securityManager,
                            securityType = snap.securityType,
                            appLabel = appLabel,
                            onSuccess = { startExitAnimation(success = true) },
                            onCancel = { startExitAnimation(success = false) },
                            biometricType = snap.biometricType,
                            onBiometricClick = { showBiometricPrompt() },
                            isPreferBiometric = snap.isPreferBiometric,
                            isExiting = isExiting.value
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, lifecycleTag("onWindowFocusChanged") + " hasFocus=" + hasFocus)
        if (hasFocus && !hasWindowFocus.value) {
            lifecycleScope.launch {
                delay(UI_DEFER_MS)
                hasWindowFocus.value = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, lifecycleTag("onStart"))
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, lifecycleTag("onResume"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, lifecycleTag("onNewIntent") + " action=" + intent.action
                + " extras=" + intent.extras?.keySet())
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, lifecycleTag("onRestart") + " RESTART - activity instance was reused!")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, lifecycleTag("onStop") + " isFinishing=" + isFinishing)
    }

    private fun lifecycleTag(phase: String): String {
        val inst = Integer.toHexString(System.identityHashCode(this))
        return "$phase pid=${Process.myPid()} inst=$inst task=$taskId state=$authState finishing=$isFinishing"
    }

    private fun startExitAnimation(success: Boolean) {
        if (authState == AuthState.EXITING || authState == AuthState.FINISHED) return
        authState = AuthState.EXITING
        isExiting.value = true

        lifecycleScope.launch {
            delay(EXIT_ANIMATION_MS)
            if (success) unlockAndFinish() else cancelAndFinish()
        }
    }

    private fun showBiometricPrompt() {
        if (authState != AuthState.IDLE || isFinishing) return
        authState = AuthState.PROMPT_SHOWING

        val negativeButtonText = when (securitySnapshot.value?.securityType) {
            SecurityType.PIN -> "Use PIN"
            SecurityType.PASSWORD -> "Use Password"
            SecurityType.PATTERN -> "Use Pattern"
            else -> "Cancel"
        }

        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Unlock $appLabel")
            .setNegativeButton(negativeButtonText, mainExecutor) { _, _ ->
                if (authState == AuthState.PROMPT_SHOWING) authState = AuthState.IDLE
            }
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        biometricCancellationSignal?.cancel()
        val signal = CancellationSignal()
        biometricCancellationSignal = signal

        prompt.authenticate(
            signal,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    biometricCancellationSignal = null
                    if (authState == AuthState.PROMPT_SHOWING) {
                        authState = AuthState.IDLE
                        startExitAnimation(success = true)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    biometricCancellationSignal = null
                    val wasShowing = authState == AuthState.PROMPT_SHOWING
                    if (wasShowing) authState = AuthState.IDLE
                    if (wasShowing
                        && errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                        && errorCode != BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                        && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        cancelAndFinish()
                    }
                }
            }
        )
    }

    private fun setupWindowForOverlay() {
        window?.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            attributes = attributes?.apply {
                privateFlags = privateFlags or
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            }
        }
    }

    private fun buildResultData(): Intent = Intent().apply {
        putExtra(EXTRA_LOCKED_PACKAGE, packageName)
        putExtra(EXTRA_LOCKED_UID, userId)
    }

    private fun unlockAndFinish() {
        if (authState == AuthState.FINISHED || isFinishing) return
        authState = AuthState.FINISHED
        packageName?.let { pkg ->
            val sandboxManager = getSystemService(Context.AX_SANDBOX_SERVICE) as? AxSandboxManager
            sandboxManager?.unlockApp(pkg, userId)
        }
        setResult(Activity.RESULT_OK, buildResultData())
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun cancelAndFinish() {
        if (authState == AuthState.FINISHED || isFinishing) return
        authState = AuthState.FINISHED
        setResult(Activity.RESULT_CANCELED, buildResultData())
        finish()
        Process.killProcess(Process.myPid())
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, lifecycleTag("onPause"))
        if (authState == AuthState.PROMPT_SHOWING) {
            Log.d(TAG, lifecycleTag("onPause") + " skipping kill - bio prompt active")
            return
        }
        biometricCancellationSignal?.cancel()
        biometricCancellationSignal = null
        if (authState != AuthState.FINISHED) {
            authState = AuthState.FINISHED
            setResult(Activity.RESULT_CANCELED, buildResultData())
            finish()
        }
        Log.d(TAG, lifecycleTag("onPause") + " killing process")
        Process.killProcess(Process.myPid())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, lifecycleTag("onUserLeaveHint"))
        if (authState == AuthState.IDLE) {
            cancelAndFinish()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, lifecycleTag("onDestroy"))
        biometricCancellationSignal?.cancel()
        biometricCancellationSignal = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AxAppLocker.Auth"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_USER_ID = "user_id"

        const val EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE"
        const val EXTRA_LOCKED_UID = "LOCKED_UID"

        const val ACTION_AUTHENTICATE = "com.android.applocker.action.AUTHENTICATE"
        const val ACTION_SYSTEM_UNLOCK = "com.android.applocker.action.SYSTEM_UNLOCK"

        private const val EXIT_ANIMATION_MS = 300L
        private const val UI_DEFER_MS = 400L
    }
}

@Composable
fun AuthenticateScreen(
    securityManager: SandboxSecurityManager,
    securityType: SecurityType,
    appLabel: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    biometricType: SandboxSecurityManager.BiometricType = SandboxSecurityManager.BiometricType.NONE,
    onBiometricClick: () -> Unit = {},
    isPreferBiometric: Boolean = false,
    isExiting: Boolean = false
) {
    var didAutoTriggerBio by remember { mutableStateOf(false) }
    LaunchedEffect(biometricType, isPreferBiometric) {
        if (!didAutoTriggerBio
            && biometricType != SandboxSecurityManager.BiometricType.NONE
            && isPreferBiometric) {
            didAutoTriggerBio = true
            onBiometricClick()
        }
    }

    val promptText = "Enter your Sandbox credential to unlock $appLabel"
    
    when (securityType) {
        SecurityType.PIN -> {
            LockScreen(
                isSetup = false,
                promptText = promptText,
                onUnlock = onSuccess,
                onPinEntered = { pin -> securityManager.verifyCredential(pin) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick,
                isExiting = isExiting
            )
        }
        SecurityType.PASSWORD -> {
            PasswordScreen(
                isSetup = false,
                promptText = promptText,
                onUnlock = onSuccess,
                onPasswordEntered = { password -> securityManager.verifyCredential(password) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick,
                isExiting = isExiting
            )
        }
        SecurityType.PATTERN -> {
            PatternScreen(
                isSetup = false,
                promptText = promptText,
                onUnlock = onSuccess,
                onPatternEntered = { pattern -> securityManager.verifyPattern(pattern) },
                onBack = onCancel,
                biometricType = biometricType,
                onBiometricClick = onBiometricClick,
                isExiting = isExiting
            )
        }
        SecurityType.NONE -> {
            LaunchedEffect(Unit) {
                onSuccess()
            }
        }
    }
}
