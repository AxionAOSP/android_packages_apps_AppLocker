package com.android.applocker

import android.app.Activity
import android.app.AxSandboxManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.hardware.display.DisplayManager
import android.os.*
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    
    private lateinit var securityManager: SandboxSecurityManager
    private var packageName: String? = null
    private var userId: Int = 0
    private var isSystemUnlock: Boolean = false

    private var resultIntent: Intent? = null
    private var isAuthSuccess = false
    private var failedTime: Long = 0
    
    private var isExiting = mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupWindowForOverlay()
        
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startExitAnimation(false)
            }
        })

        resultIntent = Intent()
        
        
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
            
        userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            .takeIf { it != 0 } ?: intent.getIntExtra(EXTRA_LOCKED_UID, 0).let { 
            UserHandle.getUserId(it) 
        }

        isSystemUnlock = ACTION_SYSTEM_UNLOCK == intent.action
        
        securityManager = SandboxSecurityManager(this)

        setContent {
            AppLockerTheme {
                val isVisible = remember { mutableStateOf(false) }
                
                LaunchedEffect(Unit) {
                    isVisible.value = true
                    
                    
                    withContext(Dispatchers.IO) {
                        if (!securityManager.isSetup()) {
                            withContext(Dispatchers.Main) {
                                unlockAndFinish()
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0f) 
                ) {
                    
                    var appLabel by remember { mutableStateOf<String?>(null) }
                    var biometricType by remember { mutableStateOf(SandboxSecurityManager.BiometricType.NONE) }
                    
                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.IO) {
                            val label = appLabel ?: packageName?.let { pkg ->
                                try {
                                    val pm = applicationContext.packageManager
                                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                                } catch (e: Exception) {
                                    pkg
                                }
                            } ?: "App"
                            
                            val bioType = if (securityManager.isBiometricEnabled() && securityManager.isBiometricAvailable()) {
                                    securityManager.getBiometricType()
                            } else {
                                    SandboxSecurityManager.BiometricType.NONE
                            }
                            
                            withContext(Dispatchers.Main) {
                                appLabel = label
                                biometricType = bioType
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AuthenticateScreen(
                            securityManager = securityManager,
                            appLabel = appLabel ?: "App",
                            onSuccess = { startExitAnimation(true) },
                            onCancel = { startExitAnimation(false) },
                            biometricType = biometricType,
                            onBiometricClick = { showBiometricPrompt(appLabel ?: "App") },
                            isExiting = isExiting.value
                        )
                    }
                }
            }
        }
    }
    
    private fun startExitAnimation(success: Boolean) {
        if (isExiting.value) return
        isExiting.value = true
        
        
        val exitDuration = 300L 
        Handler(Looper.getMainLooper()).postDelayed({
            if (success) {
                unlockAndFinish()
            } else {
                cancelAndFinish()
            }
        }, exitDuration)
    }
    
    

    private fun showBiometricPrompt(label: String) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Unlock $label")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> 
                
            }
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    startExitAnimation(true)
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    
                }
            }
        )
    }
    
    private fun setupWindowForOverlay() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            
            addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS 
            )
            
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            
            attributes = attributes?.apply {
                privateFlags = privateFlags or 
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            }
        }
    }

    private fun unlockAndFinish() {
        packageName?.let { pkg ->
            val sandboxManager = getSystemService(Context.AX_SANDBOX_SERVICE) as? AxSandboxManager
            sandboxManager?.unlockApp(pkg, userId)
        }
        resultIntent?.apply {
            putExtra(EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(EXTRA_LOCKED_UID, userId)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        isAuthSuccess = true
        
        finish()
        overridePendingTransition(0, 0) 
    }
    
    private fun cancelAndFinish() {
        resultIntent?.apply {
            putExtra(EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(EXTRA_LOCKED_UID, userId)
        }
        setResult(Activity.RESULT_CANCELED, resultIntent)
        
        failedTime = SystemClock.elapsedRealtime()
        
        moveTaskToBack(true)
        
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        
        finishAndCleanup()
    }
    
    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations && !isAuthSuccess) {
             finishAndCleanup()
        }
    }
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finishAndCleanup()
    }
    
    private fun finishAndCleanup() {
        finish()
        overridePendingTransition(0, 0)
        
        
        
        Handler(Looper.getMainLooper()).postDelayed({
             Process.killProcess(Process.myPid())
        }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        
        if (hasFocus && isAuthSuccess) {
            finishAndCleanup()
            isAuthSuccess = false
            return
        }
    }
    
    private fun isAppInFreeformDisplay(): Boolean {
        return try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (displayManager != null) {
                val display = this.display ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
                displayManager.isFreeformDisplayId(displayId)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: false
    }
    
    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_USER_ID = "user_id"
        
        const val EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE"
        const val EXTRA_LOCKED_UID = "LOCKED_UID"
        
        const val ACTION_AUTHENTICATE = "com.android.applocker.action.AUTHENTICATE"
        const val ACTION_SYSTEM_UNLOCK = "com.android.applocker.action.SYSTEM_UNLOCK"
    }
}

@Composable
fun AuthenticateScreen(
    securityManager: SandboxSecurityManager,
    appLabel: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    biometricType: SandboxSecurityManager.BiometricType = SandboxSecurityManager.BiometricType.NONE,
    onBiometricClick: () -> Unit = {},
    isExiting: Boolean = false
) {
    val securityType = securityManager.getSecurityType()
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
