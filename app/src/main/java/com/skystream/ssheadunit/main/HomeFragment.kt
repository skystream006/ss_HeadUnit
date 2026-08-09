package com.skystream.ssheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.skystream.ssheadunit.App
import com.skystream.ssheadunit.R
import com.skystream.ssheadunit.aap.AapProjectionActivity
import com.skystream.ssheadunit.aap.AapService
import com.skystream.ssheadunit.connection.UsbAccessoryMode
import com.skystream.ssheadunit.connection.UsbDeviceCompat
import com.skystream.ssheadunit.connection.UsbReceiver
import com.skystream.ssheadunit.utils.AppLog
import com.skystream.ssheadunit.utils.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private lateinit var usbButton: Button
    private lateinit var settingsButton: Button
    private lateinit var exitButton: Button
    private lateinit var usbText: TextView
    private lateinit var settingsText: TextView
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        usbButton = view.findViewById(R.id.usb_button)
        settingsButton = view.findViewById(R.id.settings_button)
        exitButton = view.findViewById(R.id.exit_button)
        usbText = view.findViewById(R.id.usb_text)
        settingsText = view.findViewById(R.id.settings_text)

        setupListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect {
                    updateUsbButtonText()
                }
            }
        }

        val appSettings = App.provide(requireContext()).settings
        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }

        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        if (attemptAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect last session",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        if (attemptSingleUsbAutoConnect()) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-connect single USB",
                                MainActivity.ConnectionUiMode.PILL
                            )
                        }
                    }
                }
            }
        }
    }

    private fun attemptAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectLastSession || !appSettings.hasAcceptedDisclaimer || commManager.isConnected) {
            return false
        }

        if (appSettings.lastConnectionType != Settings.CONNECTION_TYPE_USB) {
            AppLog.i("Auto-connect: ignoring non-USB last session ${appSettings.lastConnectionType}")
            return false
        }

        val lastUsbDevice = appSettings.lastConnectionUsbDevice
        if (lastUsbDevice.isEmpty()) return false

        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        val matchingDevice = usbManager.deviceList.values.find { device ->
            UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
        }
        return if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
            AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
            Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
            true
        } else {
            AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
            false
        }
    }

    private fun attemptSingleUsbAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice || !appSettings.hasAcceptedDisclaimer || commManager.isConnected) {
            return false
        }

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
            action = AapService.ACTION_CHECK_USB
        })
        return true
    }

    private val originalBackgrounds = mapOf(
        R.id.usb_button to R.drawable.gradient_orange,
        R.id.settings_button to R.drawable.gradient_darkblue
    )

    private fun applyMonochromeStyle() {
        val monochromeBackground = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_monochrome)
        val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
        listOf(usbButton, settingsButton).forEach { button ->
            button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = grayTint
        }
    }

    private fun restoreOriginalStyle() {
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        listOf(usbButton, settingsButton).forEach { button ->
            originalBackgrounds[button.id]?.let { drawableRes ->
                button.background = ContextCompat.getDrawable(requireContext(), drawableRes)
            }
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = whiteTint
        }
    }

    private fun updateButtonStyle() {
        val appSettings = App.provide(requireContext()).settings
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = appSettings.appTheme == Settings.AppTheme.DARK ||
            appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            isNightActive
        if (isDarkTheme && appSettings.monochromeIcons) {
            applyMonochromeStyle()
        } else {
            restoreOriginalStyle()
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            val action = if (keepServiceAlive) AapService.ACTION_DISCONNECT else AapService.ACTION_STOP_SERVICE
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                this.action = action
            })
            requireActivity().finishAffinity()
        }

        usbButton.setOnClickListener {
            if (commManager.isConnected) {
                startActivity(Intent(requireContext(), AapProjectionActivity::class.java).apply {
                    putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                })
                return@setOnClickListener
            }

            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            val androidDevices = usbManager.deviceList.values.filter { UsbDeviceCompat.isAndroidDevice(it) }
            if (androidDevices.size == 1) {
                val device = UsbDeviceCompat(androidDevices[0])
                AppLog.i("USB button: Single device found - ${device.uniqueName}, auto-connecting")
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "USB button auto-connect",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                if (device.isInAccessoryMode) {
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
                } else if (usbManager.hasPermission(device.wrappedDevice)) {
                    val usbMode = UsbAccessoryMode(usbManager)
                    val useLibusb = App.provide(requireContext()).settings.useLibusb
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val success = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                        withContext(Dispatchers.Main) {
                            context?.let { ctx ->
                                Toast.makeText(
                                    ctx,
                                    if (success) R.string.switching_to_android_auto else R.string.switch_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                    usbManager.requestPermission(device.wrappedDevice, UsbReceiver.createPermissionPendingIntent(requireContext()))
                }
            } else {
                findNavController().currentDestination?.takeIf { it.id == R.id.homeFragment }?.let {
                    findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
                }
            }
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    private fun updateUsbButtonText() {
        usbText.text = if (commManager.isConnected) getString(R.string.to_android_auto) else getString(R.string.usb)
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateUsbButtonText()
        updateButtonStyle()
        updateTextColors()
        activity?.takeIf { !it.isFinishing && !it.isDestroyed }?.let {
            RenameNotice.maybeShow(it, App.provide(requireContext()).settings)
        }
    }

    override fun onPause() {
        super.onPause()
        RenameNotice.dismiss()
    }

    private fun updateTextColors() {
        val appSettings = App.provide(requireContext()).settings
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES
        val labelViews = listOf(usbText, settingsText)

        if (appSettings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        exitButton.setTextColor(Color.WHITE)
    }

    companion object {
        fun resetAutoStart() = Unit
    }
}
