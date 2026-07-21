package app.glauncher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.CountDownTimer
import android.os.UserHandle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.glauncher.R
import app.glauncher.data.Constants
import app.glauncher.data.Prefs
import app.glauncher.databinding.ActivityAppLockBinding
import app.glauncher.helper.AppLockState
import app.glauncher.helper.PinManager
import app.glauncher.helper.showToast
import kotlin.math.min

class AppLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppLockBinding
    private lateinit var prefs: Prefs
    private lateinit var pinManager: PinManager

    private var targetPackage = ""
    private var targetActivityClass: String? = null
    private lateinit var targetUser: UserHandle
    private var mode = Constants.AppLockMode.REAUTH

    private val enteredPin = StringBuilder()
    private var lockoutTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        pinManager = PinManager(prefs)

        targetPackage = intent.getStringExtra(Constants.Key.APP_LOCK_PACKAGE).orEmpty()
        targetActivityClass = intent.getStringExtra(Constants.Key.APP_LOCK_ACTIVITY_CLASS)
        targetUser = intent.getParcelableExtra(Constants.Key.APP_LOCK_USER) ?: android.os.Process.myUserHandle()
        mode = intent.getIntExtra(Constants.Key.APP_LOCK_MODE, Constants.AppLockMode.REAUTH)

        if (targetPackage.isEmpty() && mode != Constants.AppLockMode.SETTINGS_GATE) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == Constants.AppLockMode.SETTINGS_GATE) {
                    setResult(RESULT_CANCELED)
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        populateTargetAppInfo()
        setupKeypad()
        refreshLockoutState()
    }

    override fun onStart() {
        super.onStart()
        maybeShowBiometricPrompt()
    }

    override fun onDestroy() {
        lockoutTimer?.cancel()
        super.onDestroy()
    }

    private fun populateTargetAppInfo() {
        if (mode == Constants.AppLockMode.SETTINGS_GATE) {
            binding.lockedAppIcon.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
            binding.lockedAppName.text = getString(R.string.app_name)
            return
        }
        try {
            val appInfo = packageManager.getApplicationInfo(targetPackage, 0)
            binding.lockedAppIcon.setImageDrawable(packageManager.getApplicationIcon(appInfo))
            binding.lockedAppName.text = packageManager.getApplicationLabel(appInfo)
        } catch (e: Exception) {
            binding.lockedAppName.text = targetPackage
        }
    }

    private fun setupKeypad() {
        val digitViews = listOf(
            binding.pinKey0 to "0", binding.pinKey1 to "1", binding.pinKey2 to "2",
            binding.pinKey3 to "3", binding.pinKey4 to "4", binding.pinKey5 to "5",
            binding.pinKey6 to "6", binding.pinKey7 to "7", binding.pinKey8 to "8",
            binding.pinKey9 to "9",
        )
        digitViews.forEach { (view, digit) ->
            view.setOnClickListener { onDigitEntered(digit) }
        }
        binding.pinDelete.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin.deleteCharAt(enteredPin.length - 1)
                updatePinDots()
            }
        }
        binding.pinConfirm.setOnClickListener { onConfirmPin() }
        binding.useBiometric.setOnClickListener { showBiometricPrompt() }
    }

    private fun onDigitEntered(digit: String) {
        if (isLockedOut()) return
        if (enteredPin.length >= MAX_PIN_LENGTH) return
        enteredPin.append(digit)
        updatePinDots()
    }

    private fun updatePinDots() {
        binding.pinDots.text = "●".repeat(enteredPin.length)
        binding.pinMessage.text = ""
    }

    private fun onConfirmPin() {
        if (isLockedOut()) return
        if (enteredPin.length < MIN_PIN_LENGTH) {
            binding.pinMessage.text = getString(R.string.pin_too_short)
            return
        }
        if (pinManager.verifyPin(enteredPin.toString())) {
            prefs.appLockFailedAttempts = 0
            prefs.appLockLockedUntil = 0L
            onAuthSuccess()
        } else {
            enteredPin.clear()
            updatePinDots()
            registerFailedAttempt()
        }
    }

    private fun registerFailedAttempt() {
        val attempts = prefs.appLockFailedAttempts + 1
        prefs.appLockFailedAttempts = attempts
        if (attempts >= FREE_ATTEMPTS) {
            val backoff = min(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl min(20, attempts - FREE_ATTEMPTS)))
            prefs.appLockLockedUntil = System.currentTimeMillis() + backoff
            refreshLockoutState()
        } else {
            binding.pinMessage.text = getString(R.string.pin_incorrect)
        }
    }

    private fun isLockedOut(): Boolean = System.currentTimeMillis() < prefs.appLockLockedUntil

    private fun refreshLockoutState() {
        lockoutTimer?.cancel()
        val remaining = prefs.appLockLockedUntil - System.currentTimeMillis()
        if (remaining <= 0) {
            setKeypadEnabled(true)
            return
        }
        setKeypadEnabled(false)
        lockoutTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.pinMessage.text =
                    getString(R.string.pin_too_many_attempts, (millisUntilFinished / 1000L).toInt() + 1)
            }

            override fun onFinish() {
                binding.pinMessage.text = ""
                setKeypadEnabled(true)
            }
        }.start()
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        binding.pinConfirm.isEnabled = enabled
        binding.pinDelete.isEnabled = enabled
        listOf(
            binding.pinKey0, binding.pinKey1, binding.pinKey2, binding.pinKey3, binding.pinKey4,
            binding.pinKey5, binding.pinKey6, binding.pinKey7, binding.pinKey8, binding.pinKey9,
        ).forEach { it.isEnabled = enabled }
    }

    private fun maybeShowBiometricPrompt() {
        if (!biometricAvailable()) {
            binding.useBiometric.isVisible = false
            return
        }
        binding.useBiometric.isVisible = true
        showBiometricPrompt()
    }

    private fun biometricAvailable(): Boolean {
        if (!prefs.appLockBiometricEnabled || !pinManager.isPinConfigured()) return false
        return BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        if (!biometricAvailable() || isLockedOut()) return
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Fall back silently to the PIN keypad; never lock the user out on biometric failure.
            }

            override fun onAuthenticationFailed() {
                showToast(getString(R.string.pin_incorrect))
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_prompt_use_pin))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun onAuthSuccess() {
        if (mode == Constants.AppLockMode.SETTINGS_GATE) {
            setResult(RESULT_OK)
            finish()
            return
        }

        val key = "$targetPackage|$targetUser"
        AppLockState.markUnlocked(key)

        if (mode == Constants.AppLockMode.PRE_LAUNCH && !targetActivityClass.isNullOrEmpty()) {
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val component = ComponentName(targetPackage, targetActivityClass!!)
                launcherApps.startMainActivity(component, targetUser, null, null)
            } catch (e: Exception) {
                try {
                    packageManager.getLaunchIntentForPackage(targetPackage)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                } catch (e: Exception) {
                    showToast(getString(R.string.unable_to_open_app))
                }
            }
        }
        finish()
    }

    companion object {
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8
        private const val FREE_ATTEMPTS = 3
        private const val BASE_DELAY_MS = 15_000L
        private const val MAX_DELAY_MS = 5 * 60_000L
    }
}
