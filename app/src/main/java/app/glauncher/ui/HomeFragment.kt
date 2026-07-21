package app.glauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.glauncher.MainViewModel
import app.glauncher.R
import app.glauncher.data.AppModel
import app.glauncher.data.Constants
import app.glauncher.data.Prefs
import app.glauncher.databinding.FragmentHomeBinding
import app.glauncher.helper.deletePinnedShortcut
import app.glauncher.helper.expandNotificationDrawer
import app.glauncher.helper.getChangedAppTheme
import app.glauncher.helper.getUserHandleFromString
import app.glauncher.helper.hideKeyboard
import app.glauncher.helper.isPrivateSpaceProfile
import app.glauncher.helper.isSystemApp
import app.glauncher.helper.openAlarmApp
import app.glauncher.helper.openAppInfo
import app.glauncher.helper.openCameraApp
import app.glauncher.helper.openDialerApp
import app.glauncher.helper.openSearch
import app.glauncher.helper.openUrl
import app.glauncher.helper.setPlainWallpaperByTheme
import app.glauncher.helper.showToast
import app.glauncher.helper.uninstall
import app.glauncher.listener.OnSwipeTouchListener
import app.glauncher.network.EstadoScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var quickLaunchAdapter: AppDrawerAdapter
    private var timeTickReceiver: BroadcastReceiver? = null

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initQuickLaunch()
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen()
        viewModel.isOlauncherDefault()
        viewModel.connectScheduler()
        hideStatusBar()
        resetQuickLaunch()

        timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                populateDateTime()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        requireContext().registerReceiver(timeTickReceiver, filter)
    }

    override fun onPause() {
        viewModel.disconnectScheduler()
        timeTickReceiver?.let {
            try {
                requireContext().unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        timeTickReceiver = null
        super.onPause()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.recents -> {}
            R.id.date -> openClockApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.schedulerAccionConfirmar -> enviarAccionScheduler("confirmar")
            R.id.schedulerAccionCompletar -> enviarAccionScheduler("completar")
            R.id.schedulerAccionExtender15 -> enviarAccionScheduler("extender", minutos = 15)
            R.id.schedulerAccionExtender30 -> enviarAccionScheduler("extender", minutos = 30)
            R.id.schedulerAccionExtender60 -> enviarAccionScheduler("extender", minutos = 60)
        }
    }

    private fun enviarAccionScheduler(accion: String, minutos: Int? = null) {
        val agendaId = viewModel.schedulerEstado.value?.actividad?.id ?: return
        viewModel.enviarAccionScheduler(accion, agendaId, minutos)
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.date -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.showRecentApps.observe(viewLifecycleOwner) {
            binding.recents.performClick()
        }
        viewModel.schedulerEstado.observe(viewLifecycleOwner) {
            renderSchedulerEstado(it)
        }
        viewModel.schedulerConectado.observe(viewLifecycleOwner) {
            renderSchedulerConexion(it)
        }
    }

    private fun renderSchedulerConexion(conectado: Boolean?) {
        binding.schedulerLayout.isVisible = conectado != null
        if (conectado == null) return

        binding.schedulerConexion.text = getString(
            if (conectado) R.string.galatea_connected else R.string.galatea_disconnected
        )
        if (!conectado) {
            binding.schedulerNombre.isVisible = false
            binding.schedulerEstado.isVisible = false
            binding.schedulerAccionConfirmar.isVisible = false
            binding.schedulerAccionCompletar.isVisible = false
            binding.schedulerAccionesExtender.isVisible = false
        }
    }

    private fun renderSchedulerEstado(estado: EstadoScheduler?) {
        val actividad = estado?.actividad
        binding.schedulerNombre.isVisible = actividad != null
        binding.schedulerEstado.isVisible = actividad != null

        val acciones = estado?.acciones.orEmpty()
        binding.schedulerAccionConfirmar.isVisible = "confirmar" in acciones
        binding.schedulerAccionCompletar.isVisible = "completar" in acciones
        binding.schedulerAccionesExtender.isVisible = "extender" in acciones

        if (actividad == null) return

        binding.schedulerNombre.text = actividad.nombre
        binding.schedulerEstado.text =
            "${actividad.estado} · ${actividad.horaInicio}-${actividad.horaFin}"
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.recents.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.schedulerAccionConfirmar.setOnClickListener(this)
        binding.schedulerAccionCompletar.setOnClickListener(this)
        binding.schedulerAccionExtender15.setOnClickListener(this)
        binding.schedulerAccionExtender30.setOnClickListener(this)
        binding.schedulerAccionExtender60.setOnClickListener(this)
    }

    private fun initQuickLaunch() {
        quickLaunchAdapter = AppDrawerAdapter(
            Constants.FLAG_LAUNCH_APP,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, Constants.FLAG_LAUNCH_APP)
                resetQuickLaunch()
            },
            appInfoListener = {
                openAppInfo(requireContext(), it.user, it.appPackage)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (isPrivateSpaceProfile(requireContext(), appModel.user)) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                quickLaunchAdapter.appFilteredList.removeAt(position)
                quickLaunchAdapter.notifyItemRemoved(position)
                quickLaunchAdapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                newSet.add(appModel.appPackage + "|" + appModel.user.toString())
                prefs.hiddenApps = newSet
                viewModel.getAppList()
            },
            appLockListener = { appModel, _ ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast(getString(R.string.locking_pinned_shortcuts_not_supported))
                    return@AppDrawerAdapter
                }
                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.lockedApps)
                newSet.add(appModel.appPackage + "|" + appModel.user.toString())
                prefs.lockedApps = newSet
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.shortcutId
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
        )

        // Reverse layout keeps the first match right next to the search box at the bottom.
        binding.homeSearchResults.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, true)
        binding.homeSearchResults.adapter = quickLaunchAdapter
        binding.homeSearchResults.itemAnimator = null
        capQuickLaunchResultsHeight()

        viewModel.appList.observe(viewLifecycleOwner) {
            it?.let { quickLaunchAdapter.setAppList(it.toMutableList()) }
        }

        binding.homeSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (quickLaunchAdapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    quickLaunchAdapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                quickLaunchAdapter.filter.filter(newText)
                binding.homeSearchResults.isVisible = newText.isNotBlank()
                return true
            }
        })

        // FLAG_LAYOUT_NO_LIMITS on the window defeats the automatic keyboard adjustment,
        // so lift the quick launch box above the IME manually.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainLayout) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            binding.quickLaunchLayout.translationY = -imeHeight.toFloat()
            insets
        }
    }

    private fun resetQuickLaunch() {
        binding.homeSearch.setQuery("", false)
        binding.homeSearch.clearFocus()
        binding.homeSearch.hideKeyboard()
        binding.homeSearchResults.isVisible = false
        binding.quickLaunchLayout.translationY = 0f
    }

    private fun capQuickLaunchResultsHeight() {
        val sampleItem = layoutInflater.inflate(R.layout.adapter_app_drawer, binding.homeSearchResults, false)
        sampleItem.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        binding.homeSearchResults.layoutParams = binding.homeSearchResults.layoutParams.apply {
            height = sampleItem.measuredHeight * MAX_QUICK_LAUNCH_RESULTS
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        binding.quickLaunchLayout.gravity = horizontalGravity or Gravity.BOTTOM
        binding.dateTimeLayout.gravity = horizontalGravity
    }

    // Single line: @date HH:MM_Qn-Snn_DD-MM (quarter and week of the quarter)
    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF

        val now = Calendar.getInstance()
        val quarter = now.get(Calendar.MONTH) / 3 + 1
        val quarterStart = (now.clone() as Calendar).apply {
            set(Calendar.MONTH, (quarter - 1) * 3)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val weekOfQuarter = (now.get(Calendar.DAY_OF_YEAR) - quarterStart.get(Calendar.DAY_OF_YEAR)) / 7 + 1
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time)
        val dayMonth = SimpleDateFormat("dd-MM", Locale.getDefault()).format(now.time)
        binding.date.text =
            String.format(Locale.getDefault(), "@date %s_Q%d-S%02d_%s", time, quarter, weekOfQuarter, dayMonth)
    }

    private fun populateHomeScreen() {
        populateDateTime()
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(Constants.Key.FLAG to flag)
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(Constants.Key.FLAG to flag)
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MAX_QUICK_LAUNCH_RESULTS = 3
    }
}