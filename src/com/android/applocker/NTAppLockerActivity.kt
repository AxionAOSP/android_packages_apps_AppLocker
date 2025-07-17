/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.applocker

import android.content.*
import android.content.pm.*
import android.content.res.Configuration
import android.hardware.biometrics.BiometricPrompt
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.*
import androidx.core.content.ContextCompat
import com.android.internal.widget.LockPatternUtils
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.Executor

class NTAppLockerActivity : AppCompatActivity() {
    companion object {
        private const val NT_APP_LOCKER_LOCK_STYLE_BOTH = 1
        private const val NT_APP_LOCKER_BLOCKING_UID = "LOCKED_UID"
        private const val NT_APP_LOCKER_COMPONENT = "LOCKED_COMPONENT"
        private const val NT_APP_LOCKER_LOCK_STYLE = "nothing_applocker_use_private_password"
        private const val NT_APP_LOCKER_PACKAGE = "LOCKED_PACKAGE"
        private const val NT_APP_LOCKER_PACKAGE_NAME = "com.android.applocker"
    }

    private lateinit var binding: ActivityNtApplockerBinding

    private lateinit var mBackgroundView: View
    private lateinit var mContentView: TextView
    private lateinit var mControlsView: View
    private lateinit var mPipLockIcon: ImageView

    private var mBiometricPrompt: BiometricPrompt? = null
    private var mCancellationSignal: CancellationSignal? = null
    private var mAppName: String? = null
    private var mComponent: String? = null
    private lateinit var mContext: Context
    private lateinit var mExecutor: Executor
    private lateinit var mLockPatternUtils: LockPatternUtils
    private var mPkg: String? = null
    private lateinit var mResultIntent: Intent
    private var mUID = 0
    private var mUserId = 0
    private lateinit var mUserManager: UserManager
    private var mUserHandle: UserHandle? = null

    private val mHideHandler = Handler(Looper.myLooper()!!)
    private val mHideBars = Runnable {
        mContentView.windowInsetsController?.hide(
            WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
        )
    }
    private val mHideRunnable = Runnable { hide() }

    private var TAG = "NTAppLockerActivity"

    private var mIsAuthing = false
    private var mIsErrorHappened = false
    private var mDelayAuthAfterFocused = false
    private var mDelayAuthAfterPortrait = false
    private var mFailedTime: Long = 0
    private var mIsAuthSuccess = false
    private var mIsAppLocked = false

    private val mAuthenticationCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            mIsErrorHappened = true
            Log.d(TAG, "onAuthenticationError $errorCode errString $errString")
            updateAuthStatus(false, "onAuthenticationError")
            mFailedTime = SystemClock.elapsedRealtime()
            setResult(0, mResultIntent)
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            Log.i(TAG, "onAuthenticationSucceeded")
            updateAuthStatus(false, "onAuthenticationSucceeded")
            setResult(-1, mResultIntent)
            mIsAuthSuccess = true
        }

        override fun onAuthenticationFailed() {
            Log.i(TAG, "onAuthenticationFailed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        mResultIntent = Intent()
        mContext = this
        mLockPatternUtils = LockPatternUtils(this)

        var applicationInfo: ApplicationInfo? = null
        intent?.let {
            mUID = it.getIntExtra(NT_APP_LOCKER_BLOCKING_UID, 0)
            mUserId = mUID

            try {
                val method: Method = Class.forName("android.os.UserHandle")
                    .getDeclaredMethod("getUserId", Int::class.javaPrimitiveType)
                method.isAccessible = true
                mUserId = method.invoke(null, mUID) as Int
            } catch (e: Exception) {
                Log.w(TAG, "getUserId: failed ", e)
            }

            mResultIntent.putExtra(NT_APP_LOCKER_BLOCKING_UID, mUID)
            mPkg = it.getStringExtra(NT_APP_LOCKER_PACKAGE)
            mResultIntent.putExtra(NT_APP_LOCKER_PACKAGE, mPkg)
            mComponent = it.getStringExtra(NT_APP_LOCKER_COMPONENT)
            mResultIntent.putExtra(NT_APP_LOCKER_COMPONENT, mComponent)
            mIsAppLocked = true
        }

        binding = ActivityNtApplockerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        with(binding) {
            mControlsView = fullscreenContentControls
            mContentView = fullscreenContent
            mPipLockIcon = pipLockIcon
            mBackgroundView = backgroundView
        }

        mContentView.setOnClickListener {
            if (!mIsAuthing) {
                startAuthenticate("mContentView onClick")
            }
        }

        mExecutor = ContextCompat.getMainExecutor(this)
        mUserManager = getSystemService(Context.USER_SERVICE) as UserManager

        val packageManager = packageManager
        try {
            val method = PackageManager::class.java.getMethod(
                "getApplicationInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            applicationInfo = method.invoke(
                packageManager,
                mPkg,
                0,
                mUserId
            ) as ApplicationInfo
        } catch (e: Exception) {
        }

        mAppName = if (applicationInfo != null) {
            packageManager.getApplicationLabel(applicationInfo).toString()
        } else {
            mPkg
        }

        Log.d(TAG, "appName $mAppName mUserId $mUserId mPkg $mPkg")

        updateBackground()
    }

    private fun startAuthenticate(reason: String) {
        val hasWindowFocus = hasWindowFocus()
        Log.d(TAG, "startAuthenticate hasFocus $hasWindowFocus reason $reason")

        if (!hasWindowFocus) {
            mDelayAuthAfterFocused = true
            return
        }

        val lockStyle = Settings.Secure.getIntForUser(
            contentResolver,
            NT_APP_LOCKER_LOCK_STYLE,
            0,
            mContext.userId
        )
        Log.d(TAG, "lockStyle:$lockStyle")

        var targetUserId = mUserId

        val strongAuthFlag = mLockPatternUtils.getStrongAuthForUser(targetUserId)
        Log.d(TAG, "curUserId:$targetUserId strongAuthFlag:$strongAuthFlag")

        var title = getString(R.string.verify_dialog_title)
        var description = getString(R.string.verify_dialog_summary, mAppName)
        var authenticators = 32783

        if (strongAuthFlag != 0 && lockStyle != NT_APP_LOCKER_LOCK_STYLE_BOTH) {
            val passwordType = getPasswordType(targetUserId)
            title = passwordType[0]
            description = passwordType[1]
            authenticators = 32768
        }

        mBiometricPrompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .setDescription(description)
            .build()

        mIsErrorHappened = false
        mCancellationSignal = CancellationSignal()
        updateAuthStatus(true, "startAuthenticate")
        mBiometricPrompt!!.authenticateUser(
            mCancellationSignal!!,
            mExecutor,
            mAuthenticationCallback,
            targetUserId
        )
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        delayedHide(100)
    }

    override fun onResume() {
        super.onResume()
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        if (mIsAppLocked) {
            startAuthenticate("onResume")
        } else {
            finishActivity("App not locked")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        mCancellationSignal?.cancel()
        mBiometricPrompt = null
        mCancellationSignal = null
        mIsErrorHappened = false
        finishActivity("onPause")
    }
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "onUserLeaveHint")
        mCancellationSignal?.cancel()
        mBiometricPrompt = null
        mCancellationSignal = null
        mIsErrorHappened = false
        finishActivity("onUserLeaveHint")
    }

    private fun hide() {
        supportActionBar?.hide()
        mControlsView.visibility = View.GONE
        mHideBars.run()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && mIsAuthSuccess) {
            finishActivity("onAuthenticationSucceeded")
            mIsAuthSuccess = false
            return
        }

        val timeSinceFailed = SystemClock.elapsedRealtime() - mFailedTime
        val isMultiWindow = WindowModeUtil.isAppInMultiWindowMode()
        val isWindowedMode = WindowModeUtil.isAppInWindowformWindowMode()

        Log.d(
            TAG, 
            "onWindowFocusChanged $hasFocus mIsAuthing $mIsAuthing " +
            "isWindowformWindowMode $isWindowedMode mIsErrorHappened $mIsErrorHappened " +
            "mDelayAuthAfterFocused $mDelayAuthAfterFocused time $timeSinceFailed"
        )

        updateLockIcon(isMultiWindow, isWindowedMode)

        if (hasFocus && !mIsAuthing) {
            when {
                mDelayAuthAfterFocused -> {
                    mDelayAuthAfterFocused = false
                    startAuthenticate("onWindowFocusChanged-DelayAuth")
                }
                timeSinceFailed > 280 && isMultiWindow -> {
                    startAuthenticate("onWindowFocusChanged-inMultiWindowMode")
                }
                mIsErrorHappened -> {
                    mIsErrorHappened = false
                    if (!isMultiWindow && !isWindowedMode && isScreenOn()) {
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            putExtra("android.intent.extra.PACKAGE_NAME", NT_APP_LOCKER_PACKAGE_NAME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(this)
                        }
                        finishActivity("onWindowFocusChanged-ErrorHappened")
                    }
                }
            }
        }
    }

    private fun updateLockIcon(
        isMultiWindow: Boolean,
        isWindowedMode: Boolean
    ) {
        mPipLockIcon?.let { icon ->
            when {
                isWindowedMode -> {
                    icon.scaleX = 1.36f
                    icon.scaleY = 1.36f
                }
                else -> {
                    icon.scaleX = 1.0f
                    icon.scaleY = 1.0f
                }
            }
            icon.visibility = View.VISIBLE
        }
    }

    private fun delayedHide(delayMillis: Int) {
        mHideHandler.removeCallbacks(mHideRunnable)
        mHideHandler.postDelayed(mHideRunnable, delayMillis.toLong())
    }

    private fun finishActivity(reason: String) {
        Log.d(TAG, "finishActivity $reason")
        mCancellationSignal?.cancel()
        mAppName = null
        mComponent = null
        mBiometricPrompt = null
        mCancellationSignal = null
        mHideHandler.removeCallbacksAndMessages(null)
        mPkg = null
        mUserHandle = null
        finish()
        Process.killProcess(Process.myPid())
    }

    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed")
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            putExtra("android.intent.extra.PACKAGE_NAME", NT_APP_LOCKER_PACKAGE_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(this)
        }
        super.onBackPressed()
    }

    private fun updateAuthStatus(isAuthenticating: Boolean, reason: String) {
        Log.d(TAG, "updateAuthStatus $isAuthenticating - $reason")
        mIsAuthing = isAuthenticating
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager?
        return powerManager?.isInteractive ?: false
    }

    private fun getPasswordType(userId: Int): ArrayList<String> {
        val quality = mLockPatternUtils.getActivePasswordQuality(userId)
        Log.d(TAG, "security type:$quality userId:$userId")

        return when (quality) {
            65536 -> arrayListOf(
                getString(R.string.lockpassword_confirm_your_pattern_header),
                getString(R.string.nt_lockpassword_strong_auth_required_device_pattern)
            )
            131072, 196608 -> arrayListOf(
                getString(R.string.lockpassword_confirm_your_pin_header),
                getString(R.string.nt_lockpassword_strong_auth_required_device_pin)
            )
            262144, 327680, 393216, 524288 -> arrayListOf(
                getString(R.string.lockpassword_confirm_your_password_header),
                getString(R.string.nt_lockpassword_strong_auth_required_device_password)
            )
            else -> arrayListOf("Type error", "This is no type!")
        }
    }

    private fun isDarkMode(): Boolean {
        return try {
            val config = applicationContext.resources?.configuration
            config != null && (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } catch (e: Exception) {
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBackground()
    }
    
    fun updateBackground() {
        if (isDarkMode()) {
            mBackgroundView.setBackgroundColor(getColor(com.android.internal.R.color.system_neutral1_900))
        } else {
            mBackgroundView.setBackgroundColor(getColor(com.android.internal.R.color.system_neutral1_50))
        }
    }
}
