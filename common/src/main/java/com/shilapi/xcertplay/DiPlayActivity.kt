// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.carhop.CarBluetoothHop
import com.shilapi.xcertplay.carhop.CarBluetoothHops
import com.shilapi.xcertplay.carhop.CarBluetoothMaintenanceStatus
import com.shilapi.xcertplay.carhop.CarBluetoothMaintenance
import com.shilapi.xcertplay.carhop.CarBluetoothPhone
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.NavigationAudioStream
import com.shilapi.xcertplay.network.CarHotspotController
import com.shilapi.xcertplay.network.sameWifiNetworkName
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.WirelessGap
import com.shilapi.xcertplay.transport.WirelessReadiness
import com.shilapi.xcertplay.transport.WirelessReadinessCheck
import com.shilapi.xcertplay.transport.WirelessReadinessInput
import com.shilapi.xcertplay.transport.adapter.AdapterPage
import com.shilapi.xcertplay.transport.adapter.AdapterRoutes
import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus
import com.shilapi.xcertplay.ui.component.ConfirmSheetView
import com.shilapi.xcertplay.ui.component.HeroStageCard
import com.shilapi.xcertplay.ui.component.QuickWidgetsView
import com.shilapi.xcertplay.ui.component.SettingsScreenView
import com.shilapi.xcertplay.ui.component.SettingsTab
import com.shilapi.xcertplay.ui.component.SlimDockView
import com.shilapi.xcertplay.ui.theme.AppSkin
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import com.shilapi.xcertplay.ui.theme.ThemePalette
import com.shilapi.xcertplay.ui.theme.ThemeSchedule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    /** The pages this build still has. Everything else is the home page — see [render]. */
    private val KNOWN_PAGES = setOf("home", "display", "system", "settings", "about", PAGE_FIRMWARE)

    private var page = "home"
    /**
     * The cockpit's own views, held so the once-a-second tick repaints them in place rather than
     * rebuilding the page — see [refreshStatus]. Null on every page but home.
     */
    private var hero: HeroStageCard? = null
    private var widgets: QuickWidgetsView? = null
    private var cockpitHost: FrameLayout? = null
    private var activeSettingsTab: SettingsTab = SettingsTab.CONNECTION
    /** The exit question, while it is up: one instance, so a second press cannot stack a second sheet. */
    private var exitSheet: ConfirmSheetView? = null
    /** The palette this redraw resolved to; the cockpit's views are built from it. */
    private var palette: ThemePalette = CockpitThemeManager.ZEEKR_WHITE_PALETTE
    /**
     * What the last redraw resolved the palette from, so a clock-driven schedule can be re-checked
     * without reading storage on every tick. The palette itself is one of the manager's constants,
     * so the comparison is an identity test on the answer rather than on its inputs — the two can
     * never disagree, and a check that compared inputs would loop on a skin that is dark anyway.
     */
    private var themeScheduleAtRender: ThemeSchedule = ThemeSchedule.AUTO_CAR
    private var skinAtRender: AppSkin = AppSkin.ZEEKR_WHITE
    private var pendingCarHotspotSetup = false

    /**
     * Where a rebuilt page comes back to.
     *
     * Most controls repaint by rebuilding the page, and a fresh ScrollView starts at the top: turning
     * on a switch in a card near the bottom threw the page back to the header. The offset is kept per
     * page, so moving between pages still starts at the top.
     */
    private var pageScroll: ScrollView? = null
    private var pageScrollY = 0
    private var scrollYPage: String? = null

    /**
     * The manual-hotspot pair, read once per redraw for the same reason [cachedReport] is: laying the
     * page out asks for it several times over, and each read is a call into the Wi-Fi service.
     */
    private var cachedHotspot: Pair<String, String>? = null
    private var setupError: String? = null

    /** The exception behind [setupError]; the header has to name the reason, not just the symptom. */
    private var setupErrorDetail: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var adapterPage: AdapterPage? = null
    private var adapterSeen: WirelessAdapterStatus? = null

    /** The line the adapter's step is written into, kept so a step change repaints it instead of the page. */
    private var adapterStatus: TextView? = null

    /** The adapter's presence as of the last look, so plugging it in redraws the page exactly once. */
    private var adapterPluggedSeen: Boolean? = null

    /**
     * Whether the last wireless connect attempt was refused, so the page leads with why. A flag rather than
     * the readiness it was refused for: held as a snapshot, the card outlived the reason it was made for —
     * the driver turned the hotspot on in the car settings and came back to a page that still said it was off,
     * while the connect step below said nothing was missing.
     */
    private var wirelessBlocked = false

    /** The head unit probe, held for the length of one redraw so laying the page out reads it once. */
    private var cachedReport: ConnectionSupportReport? = null

    /** The session and the gaps, held for the length of one redraw. See [pageState]. */
    private var cachedPageState: WirelessPageState? = null
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var uploadInProgress = false
    private var navigationStreamType = NavigationAudioStream.deviceDefault
    private var testToneTrack: AudioTrack? = null
    /** The 4.4s test tone, read from assets once. It is 384 KB; re-reading it per tap is disk I/O on the UI thread. */
    private var cachedTestTonePcm: ByteArray? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var uploadButton: Button? = null

    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM

    /**
     * Settings that had no control anywhere once the CarPlay settings screen stopped being opened.
     * Location reporting needs its own permission.
     */
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var debugLogsEnabled = false

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            locationPermissionAvailable =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!locationPermissionAvailable) {
                locationReportingEnabled = false
                AirPlayPersistence.saveLocationReportingEnabled(this, false)
            } else if (grants.isNotEmpty()) {
                AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
            }
            render()
        }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() {
            // Eight milliseconds is half a frame at 60 Hz: a tick that takes longer than this one
            // second in three is a page that stutters while nothing is being touched.
            PerfTrace.span("ui/tick", warnAboveMs = 8) { refreshStatus() }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Plugging or unplugging a USB device is the only thing that can change whether the adapter is on the
     * bus, so the bus is read then and not once a second. Probing it on every tick was detection running
     * whether or not the user asked for anything.
     */
    private val usbTopology = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUsbTopologyChanged()
    }

    /**
     * The mount paths the system has announced. A stick plugged in while the app is open does not
     * appear in getExternalFilesDirs until something asks again, and asking means creating directories
     * and writing a file to see — disk I/O the drawing thread is not allowed to do. So the broadcast
     * is recorded here and read once, on the export's own thread.
     *
     * Registered in [onResume] beside [usbTopology], not for the activity's whole life: a path kept
     * from a page that is no longer up can name a stick that has since been pulled out.
     */
    private val mountedUsbPaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val mediaMountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val path = intent.data?.path ?: return
            when (intent.action) {
                Intent.ACTION_MEDIA_MOUNTED -> mountedUsbPaths.add(path)
                Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL ->
                    mountedUsbPaths.remove(path)
            }
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) render() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagCrashHandler.install(this)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching {
            // Loading the CarPlay identity is crypto and file I/O on the drawing thread, so its cost
            // belongs in the report next to the rest of the cold start rather than in a guess.
            PerfTrace.span("startup/bootstrap", warnAboveMs = 200) { DiPlayBootstrap.ensure(this) }
        }.exceptionOrNull()?.let {
            setupErrorDetail = "${it.javaClass.simpleName}: ${it.message}"
            DiagLog.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        val openDrawerExtra = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS_DRAWER, false)
        page = savedInstanceState?.getString("page")
            ?: if (openDrawerExtra) "settings" else (intent.getStringExtra("page") ?: "home")
        savedInstanceState?.getString(KEY_SETTINGS_TAB)?.let {
            activeSettingsTab = SettingsTab.fromKey(it)
        }
        intent.getStringExtra(EXTRA_SETTINGS_TAB)?.let {
            activeSettingsTab = SettingsTab.fromKey(it)
        }
        // The selector must open on what is actually stored, or the user cannot tell what is in effect.
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        loadConnectionSettings()
        render()
        openOntoRunningFirmwareOperation()
        startVendorHopInBackground()
        wakeCarHotspotInBackground()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The exit question is drawn over everything else, so it is answered or dismissed
                // before back is allowed to mean anything about pages.
                exitSheet?.let { it.dismiss(); return }
                // The firmware page is left by its own control and by nothing else while a write is in
                // flight: back would take the driver off the one screen that says what is happening.
                if (page == PAGE_FIRMWARE && firmwareRunning()) return
                goBack()
            }
        })
        PerfTrace.mark("startup", "startup/create-done")
        reportFirstDraw()
    }

    /**
     * The moment the page is actually on screen — not the moment its view tree exists.
     *
     * The end of `onCreate` says the views were built; the first pre-draw says the driver can see
     * them, and the gap between the two is layout, which is where a heavy page shows up. The process
     * figure is recorded only where the platform can answer it: `getStartElapsedRealtime` is API 24,
     * and a vendor ROM that answers 0 would otherwise turn it into a plausible-looking uptime.
     */
    private fun reportFirstDraw() {
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                PerfTrace.mark("startup", "startup/first-draw")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val processStart = android.os.Process.getStartElapsedRealtime()
                    if (processStart > 0L) {
                        PerfTrace.record(
                            "startup/process-to-frame",
                            SystemClock.elapsedRealtime() - processStart,
                            warnAboveMs = 2_000,
                        )
                    }
                }
                return true
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        // A second tap on the app icon arrives here carrying the launcher's intent, which names no
        // page — so the default would take the driver off the firmware page while a script is writing
        // to `/system`, and the back key would stop being swallowed with it. The watch would put them
        // back within a beat, which is a page that flickers away and back under their hands; not
        // moving is better, and it is what the page is for.
        if (!firmwareRunning()) {
            val requestedPage = intent.getStringExtra("page")
            val openDrawerExtra = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS_DRAWER, false)
            page = when {
                openDrawerExtra -> "settings"
                requestedPage != null -> requestedPage
                else -> "home"
            }
            val tabExtra = intent.getStringExtra(EXTRA_SETTINGS_TAB)
            if (tabExtra != null) {
                activeSettingsTab = SettingsTab.fromKey(tabExtra)
            } else if (openDrawerExtra) {
                activeSettingsTab = SettingsTab.CONNECTION
            }
            render()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString(KEY_SETTINGS_TAB, activeSettingsTab.key)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from whatever took the screen away. Re-armed from what it was watching rather than
        // from a fresh look: if the operation ended while the app was away, the first tick is what
        // finds that out, and it stops itself there.
        maintenanceWatched?.let { startWatchingMaintenance(it) }
        registerUsbTopology()
        registerMediaMount()
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        // Opening the app never starts a connection. Wireless or USB is chosen on the connect button.
        if (initialLaunch) initialLaunch = false
        else render()
    }
    override fun onPause() {
        unregisterUsbTopology()
        unregisterMediaMount()
        handler.removeCallbacks(tick)
        // Taken off the queue as well, and put back in onResume. What it does is a root round trip on
        // the same Binder the write is using, every 700 ms, and nothing on the screen is being drawn
        // while this is paused — the head unit has between 26 and 33 MB free and the install is
        // running in it. [maintenanceWatched] is deliberately left set: it is what onResume re-arms
        // from, and what tells the next tick whether there is still anything to watch.
        handler.removeCallbacks(maintenanceTick)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(maintenanceTick)
        // The tone outlives the page it was started from otherwise: a 4.5s release callback holds a
        // native AudioTrack, and an Activity destroyed inside those 4.5 seconds used to leave it
        // playing against a dead window — and the callback queued on a Handler that never ran.
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        releaseAdapterPage()
        super.onDestroy()
    }

    /**
     * A whole page, built again.
     *
     * Timing it is the point: this throws away every view and rebuilds the tree, which is right for a
     * page change and expensive for anything that happens on a timer. The report says which of the two
     * it is costing, per sample, instead of leaving it to a reviewer to count widgets.
     */
    private fun render() = PerfTrace.span("ui/render", warnAboveMs = 32) { renderPage() }

    private fun renderPage() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; adapterStatus = null
        // One probe per redraw: laying the page out asks what the head unit supports several times over.
        cachedReport = null
        cachedHotspot = null
        cachedPageState = null
        // A route that no longer exists — "wireless" and "wired" were pages once — lands on the home
        // page rather than on a header with nothing under it.
        if (page !in KNOWN_PAGES) page = "home"
        hero = null; widgets = null; cockpitHost = null; exitSheet = null
        // Read here rather than per view: the car's own day/night signal arrives as a configuration
        // change, which calls render(), so one read per redraw is exactly one read per signal.
        themeScheduleAtRender = AirPlayPersistence.loadThemeSchedule(this)
        skinAtRender = AirPlayPersistence.loadAppSkin(this)
        palette = CockpitThemeManager.resolvePalette(
            skinAtRender,
            themeScheduleAtRender,
            resources.configuration.uiMode,
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        )
        pageScroll?.let { if (scrollYPage == page) pageScrollY = it.scrollY }
        if (scrollYPage != page) { scrollYPage = page; pageScrollY = 0 }
        // The home page is the cockpit, which is not a scrolling document: building the page below
        // and then replacing it with the cockpit cost a header, a column and a dozen views per
        // redraw on a head unit that runs with 26 MB free.
        if (page == "home") {
            setContentView(cockpitRoot())
            refreshStatus()
            return
        }
        if (page == "settings") {
            val screen = settingsScreen()
            setContentView(screen)
            val scroll = (screen as? SettingsScreenView)?.activeScrollView()
            pageScroll = scroll
            if (scroll != null) restoreScroll(scroll, pageScrollY)
            refreshStatus()
            return
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.carplay) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(R.string.diplay), 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        // The firmware page is the one page with no way off it from the header. It is drawn while a
        // script is writing to /system, and both of those controls would take the driver somewhere
        // that cannot show them what is happening — the second of them out of the app entirely. What
        // leaves it is its own control, which appears once there is nothing left to wait for.
        if (page != PAGE_FIRMWARE) {
            // "Back" from every page but the home page, which is the cockpit and never gets here.
            header.addView(button(getString(R.string.back), false) { goBack() }, LinearLayout.LayoutParams(dp(130), dp(56)))
            // The same question the cockpit's exit control asks. This button sits a thumb's width
            // from "Back" and used to end the process on the first touch, which is the one thing the
            // cockpit's own exit is careful never to do.
            header.addView(button(getString(R.string.close_app), false) { confirmExit() }, LinearLayout.LayoutParams(dp(130), dp(56)).apply { marginStart = dp(10) })
        }
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "display" -> displaySettings(CardColumns(content))
            "system" -> systemSettings(CardColumns(content))
            "settings" -> settings(content)
            "about" -> about(content)
            PAGE_FIRMWARE -> firmwareProgress(content)
            else -> Unit
        }
        setContentView(scroll)
        pageScroll = scroll
        restoreScroll(scroll, pageScrollY)
        refreshStatus()
    }

    /**
     * Back means one thing: the home page, and out of the app from there.
     *
     * There used to be a table of parents, one entry per page, and the pages the connect flow could
     * land on were not all in it — so back from the connection page went to the settings page, and the
     * settings page was reachable both from the gear and from a connect that had nothing to connect
     * with. Two ways in and a table to say which way out is what made "which settings page am I on"
     * a question at all. There is one page now, and back is the same key everywhere.
     */
    private fun goBack() {
        if (page == "home") finish() else { page = "home"; render() }
    }

    /**
     * A rebuilt page has nowhere to scroll to until it has been laid out: [ScrollView.scrollTo] clamps
     * against the child's height, which is still zero when the rebuild returns.
     */
    private fun restoreScroll(scroll: ScrollView, offset: Int) {
        if (offset <= 0) return
        val observer = scroll.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                observer.removeOnGlobalLayoutListener(this)
                if (pageScroll === scroll) scroll.scrollTo(0, offset)
            }
        })
    }

    /**
     * The home page as three zones on a 1920-wide head unit: the rail, the connection stage, and the
     * quick column. The page used to be one vertical stack, which left two thirds of the screen empty
     * on this hardware.
     *
     * Everything the old stack carried is still here rather than dropped for the wider shape: the
     * stage holds the readiness card's notes and the gap lines, and the drawer holds every setting.
     */
    private fun cockpitRoot(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.screenBg) }
        val horizontal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val rail = SlimDockView(
            this,
            palette,
            onHomeClick = { if (page != "home") { page = "home"; render() } },
            onAudioClick = { openSettings(SettingsTab.AUDIO) },
            onSettingsClick = { openSettings(SettingsTab.CONNECTION) },
            onLauncherClick = { leaveToLauncher() },
            onExitClick = { confirmExit() },
        )

        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val stageCard = HeroStageCard(
            this,
            palette,
            sessionPhase = pageState().session,
            pairedPhoneName = DiPlayPreferences.phoneName(this),
            hasUsbHost = UsableConnection.USB in connectionReport().usable,
            setupError = setupError,
            supportedConnectionView = supportedConnectionLabel(),
            adapterStatusView = adapterStatusView(),
            hotspotOffNote = if (deviceSupportsWireless() && carHotspotOff()) {
                getString(R.string.msg_car_hotspot_off, hotspotSsid())
            } else {
                null
            },
            onConnectWireless = { connect(true) },
            onConnectUsb = { connect(false) },
            onOpenProjection = { openProjection() },
            onDisconnect = {
                CarPlayBackgroundSession.stop {
                    runOnUiThread {
                        // The attempt can outlive the page: the driver leaves the app while the
                        // session is tearing down, and refreshStatus() on a destroyed Activity is a
                        // window that is already gone.
                        if (!isFinishing && !isDestroyed) refreshStatus()
                    }
                }
            },
            onOpenSettings = { openSettings(SettingsTab.CONNECTION) },
            initialConnectionMode = AirPlayPersistence.loadConnectionMode(this),
            // Remember the choice, and nothing else: the card repaints itself in place, and the tap
            // that made this choice is already starting a connection — rebuilding the page here
            // would throw away the tree the driver is looking at and then paint the same state again.
            onModeSelected = { mode -> AirPlayPersistence.saveWirelessEnabled(this, mode) },
        )
        hero = stageCard

        val quick = QuickWidgetsView(
            this,
            palette,
            currentHotspotMode(),
            hotspotSsid(),
            joinedWifiName(),
            audioChannelLabel(),
            // Switching the segment writes the mode and nothing else. Whether the car-hotspot form
            // has anything to ask for is decided by [applyWirelessLink] from the credentials, so this
            // page — which has no form at all — does not have to say it.
            onModeSwitch = { applyWirelessLink(it) },
            onOpenHotspotSettings = { openCarWifiSettings() },
            onEditHotspotPass = { editHotspotCredentials() },
            onOpenWifiSettings = { openCarClientWifiSettings() },
            onEditWifiPass = {
                askExistingWifiCredentials { ssid, password ->
                    AirPlayPersistence.saveExistingWifiNetwork(this, ssid, password)
                    render()
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            },
            onAudioSelect = { openSettings(SettingsTab.AUDIO) },
            onOpenDrawer = { openSettings(SettingsTab.CONNECTION) },
            sameWifiOffered = ExperimentalFeatures.enabled,
        )
        widgets = quick

        stage.addView(stageCard, LinearLayout.LayoutParams(0, -1, 58f))
        stage.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        stage.addView(quick, LinearLayout.LayoutParams(0, -1, 42f))

        // Right-hand drive mirrors the rail to the far edge by putting it on the other side of the
        // column, which is the whole of what that setting means for this layout.
        val stageParams = LinearLayout.LayoutParams(0, -1, 1f)
        val railParams = LinearLayout.LayoutParams(dp(82), -1)
        if (AirPlayPersistence.loadRightHandDrive(this)) {
            horizontal.addView(stage, stageParams)
            horizontal.addView(rail, railParams)
        } else {
            horizontal.addView(rail, railParams)
            horizontal.addView(stage, stageParams)
        }
        root.addView(horizontal, FrameLayout.LayoutParams(-1, -1))
        cockpitHost = root
        return root
    }

    /** The mode the page draws, which is the mode it would connect with — see [displayedHotspotMode]. */
    private fun currentHotspotMode(): WirelessHotspotMode = displayedHotspotMode(
        pendingCarHotspotSetup,
        AirPlayPersistence.loadWirelessHotspotMode(this),
        connectionReport().wirelessModes,
    )

    /**
     * What the audio row says: the routing this build actually uses, and the stream it is on. The
     * stream number is the stored one — the same value the routing card writes — rather than a
     * channel name invented for the card.
     */
    private fun audioChannelLabel(): String =
        if (AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
            getString(R.string.advanced_audio_channel_mapping)
        } else {
            "${getString(R.string.navigation_stream_type)} · $navigationStreamType"
        }

    private fun openSettings(tab: SettingsTab = SettingsTab.CONNECTION) {
        page = "settings"
        activeSettingsTab = tab
        render()
    }

    private fun settingsScreen(): View {
        val screen = SettingsScreenView(
            this,
            palette,
            initialTab = activeSettingsTab,
            hasExperimental = ExperimentalFeatures.enabled,
            onBack = { page = "home"; render() },
            onCloseApp = { confirmExit() },
            buildAudioSection = { col ->
                val columns = CardColumns(col)
                audioRoutingCard(columns)
            },
            buildConnectionSection = { col ->
                val columns = CardColumns(col)
                val readiness = pageState().readiness
                if (!hasWirelessRadio()) {
                    columns.line(
                        label(getString(R.string.this_head_unit_is_not_supported_for_wireless_carplay), 16, WARNING)
                            .apply { setPadding(0, 0, 0, dp(8)) },
                    )
                }
                if (wirelessBlocked && readiness.blocking.isNotEmpty()) gapsCard(columns.raw(), readiness)
                bucket(columns, R.string.wireless_settings, R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha)
                wirelessCards(columns, readiness)
                bucket(columns, R.string.wired_settings, R.string.wired_settings_hint)
                wiredCards(columns)
            },
            buildDisplaySection = { col ->
                val columns = CardColumns(col)
                bucket(columns, R.string.display_and_audio, R.string.display_and_audio_hint, R.string.apply_reconnects_carplay_for_size_resolution_music_buffer)
                displayPerformanceCard(columns)
            },
            buildSystemSection = { col ->
                val columns = CardColumns(col)
                bucket(columns, R.string.system_settings, R.string.system_settings_hint)
                buildThemeSection(col)
                systemCards(columns)
            },
            buildExperimentalSection = if (ExperimentalFeatures.enabled) {
                { col ->
                    val columns = CardColumns(col)
                    bucket(columns, R.string.experimental_features, R.string.experimental_features_hint)
                    experimentalCards(columns)
                }
            } else null,
        )
        screen.onTabChanged = { tab ->
            activeSettingsTab = tab
            pageScroll = screen.activeScrollView()
        }
        return screen
    }

    /**
     * Skin and day/night, which belong to this page's chrome rather than to a connection. Written
     * straight to storage and redrawn: no session is rebuilt for a colour.
     */
    private fun buildThemeSection(column: LinearLayout) {
        column.addView(
            label(getString(R.string.appearance), 20, palette.textMain, true).apply { setPadding(0, dp(10), 0, 0) },
        )
        val skins = listOf(AppSkin.ZEEKR_WHITE, AppSkin.FLYME_WHITE, AppSkin.NIGHT_DARK)
        themeChooser(
            column,
            getString(R.string.appearance),
            listOf(
                getString(R.string.skin_zeekr_white),
                getString(R.string.skin_flyme_blue),
                getString(R.string.skin_night),
            ),
            skins.indexOf(AirPlayPersistence.loadAppSkin(this)),
        ) { index ->
            AirPlayPersistence.saveAppSkin(this, skins[index])
            render()
        }
        val schedules = listOf(ThemeSchedule.AUTO_CAR, ThemeSchedule.AUTO_TIME, ThemeSchedule.MANUAL)
        themeChooser(
            column,
            getString(R.string.day_night_switching),
            listOf(
                getString(R.string.day_night_follow_car),
                getString(R.string.day_night_follow_time),
                getString(R.string.day_night_manual),
            ),
            schedules.indexOf(AirPlayPersistence.loadThemeSchedule(this)),
        ) { index ->
            AirPlayPersistence.saveThemeSchedule(this, schedules[index])
            render()
        }
    }

    /** One row of equally wide picks, drawn in the palette rather than the old page's fixed colours. */
    private fun themeChooser(column: LinearLayout, title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        column.addView(
            label(title, 15, palette.textMuted).apply { setPadding(0, dp(16), 0, dp(6)) },
        )
        val picks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, text ->
            val chosen = index == current
            picks.addView(
                Button(this).apply {
                    this.text = text
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(if (chosen) palette.btnMainFg else palette.textMain)
                    background = GradientDrawable().apply {
                        setColor(if (chosen) palette.btnMainBg else palette.cardInner)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), palette.cardBorder)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
                    setOnClickListener { onPick(index) }
                },
                LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (index > 0) marginStart = dp(8) },
            )
        }
        column.addView(picks)
    }

    /**
     * Leaving the app is a press, a question and a press — never one press.
     *
     * The question is a card on the page rather than a platform dialog: it comes from the palette, so
     * it matches whichever skin is running instead of arriving as a grey system rectangle, and the
     * stage it is asking about stays visible behind it. See [ConfirmSheetView].
     *
     * It is asked on every page, not only on the cockpit. The header's close control is next to
     * "Back" on the settings, display, system and about pages, and a thumb that lands one button to
     * the right of the one it meant used to end the session and the process with it.
     */
    private fun confirmExit() {
        if (exitSheet != null) return
        val host = cockpitHost ?: findViewById<ViewGroup>(android.R.id.content) ?: return
        val sheet = ConfirmSheetView(
            this,
            palette,
            title = getString(R.string.close_app),
            body = getString(R.string.exit_confirm_body),
            confirmLabel = getString(R.string.exit_application),
            cancelLabel = getString(R.string.cancel),
            onConfirm = {
                exitSheet = null
                closeTheApp()
            },
            onDismiss = { exitSheet = null },
        )
        exitSheet = sheet
        host.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * Out to the car's own launcher with DiPlay still running.
     *
     * This is the other half of what the rail's last two controls mean: the session, the mDNS socket
     * and the reconnect all keep going here, and DiPlay's icon on the launcher brings this page back.
     * [closeTheApp] is the one that ends them.
     */
    private fun leaveToLauncher() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    private fun loadConnectionSettings() {
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
    }

    /**
     * Ask for location when the driver turns reporting on. Off until the permission is actually
     * granted, so the switch can never show a state the app cannot honour.
     */
    private fun setLocationReporting(enabled: Boolean) {
        if (enabled) {
            locationReportingEnabled = true
            if (locationPermissionAvailable) {
                AirPlayPersistence.saveLocationReportingEnabled(this, true)
            } else {
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                return
            }
        } else {
            locationReportingEnabled = false
            AirPlayPersistence.saveLocationReportingEnabled(this, false)
        }
        render()
    }

    /**
     * Every setting there is, on the page the driver reaches by tapping the gear.
     *
     * It used to be a hub of four entry cards, each opening a page of its own, on the argument that the
     * two connection methods ask different things of the driver and the rest of the settings have
     * nothing to do with either. What that cost is the reason it is one page again: a setting the driver
     * came here for is now behind a guess about which bucket it was put in, and the head unit is wide
     * enough to show the cards together -- see [CardColumns]. The wireless and wired buckets are only
     * buckets: they were pages of their own as well, which is what a connect that found something
     * missing used to move the driver onto, and what made "which settings page am I on" a question.
     *
     * The four buckets keep their own headings, so the order below is the order the pages had: the two
     * connection steps first, then everything else.
     */
    private fun settings(content: LinearLayout) {
        val columns = CardColumns(content)
        columns.line(label(getString(R.string.your_drive_your_way), 34, TEXT, true))
        columns.line(label(getString(R.string.settings_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        columns.line(supportedConnectionLabel().apply { setPadding(0, 0, 0, dp(8)) })
        if (!hasWirelessRadio()) {
            columns.line(
                label(getString(R.string.this_head_unit_is_not_supported_for_wireless_carplay), 16, WARNING)
                    .apply { setPadding(0, 0, 0, dp(8)) },
            )
        }
        // One readiness for the whole page, read once so the cards and the connect step cannot disagree.
        val readiness = pageState().readiness
        // A notice is not "not ready": this card exists for the attempt that was refused, so it
        // appears only while something actually blocks. The lines a notice carries are on the
        // connection card, with the controls they belong to.
        if (wirelessBlocked && readiness.blocking.isNotEmpty()) gapsCard(columns.raw(), readiness)

        bucket(columns, R.string.wireless_settings, R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha)
        wirelessCards(columns, readiness)

        // The heading and its hint are drawn wherever the bucket is, exactly as on the bucket's own
        // route: what is conditional is the card, and [wiredCards] is where that is decided.
        bucket(columns, R.string.wired_settings, R.string.wired_settings_hint)
        wiredCards(columns)

        bucket(columns, R.string.display_and_audio, R.string.display_and_audio_hint, R.string.apply_reconnects_carplay_for_size_resolution_music_buffer)
        displayCards(columns)

        bucket(columns, R.string.system_settings, R.string.system_settings_hint)
        systemCards(columns)

        // Last, and only in a package built with them. An official package has no bucket here rather
        // than an empty one: whether the features are in the package is a fact about the package, and
        // a heading over nothing would read as a fault.
        if (ExperimentalFeatures.enabled) {
            bucket(columns, R.string.experimental_features, R.string.experimental_features_hint)
            experimentalCards(columns)
        }
    }

    /** A bucket's heading and hints, one size below the page's own title, since the page holds four. */
    private fun bucket(columns: CardColumns, title: Int, vararg hints: Int) {
        columns.line(label(getString(title), 26, TEXT, true).apply { setPadding(0, dp(24), 0, dp(6)) })
        hints.forEach { columns.line(label(getString(it), 16, MUTED).apply { setPadding(0, 0, 0, dp(14)) }) }
    }

    private fun systemSettings(columns: CardColumns) {
        columns.line(label(getString(R.string.system_settings), 34, TEXT, true))
        columns.line(label(getString(R.string.system_settings_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        systemCards(columns)
    }

    private fun systemCards(columns: CardColumns) {
        // The certificate source and the identity the iPhone is shown are deliberately not on this
        // page. Both decide whether the car authenticates as an Apple-licensed accessory, a wrong
        // value costs CarPlay outright, and no driver can diagnose that from the screen. They stay
        // at their built-in values.
        columns.card(R.string.diagnostics, R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                exportDiagnostics()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(label(getString(R.string.reports_save_to_usb_then_downloads) + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        columns.card(R.string.automatic_connection, R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            toggle(card, getString(R.string.report_location_to_iphone), getString(R.string.report_android_location_to_the_iphone), locationReportingEnabled) { setLocationReporting(it) }
            toggle(card, getString(R.string.debug_logs), getString(R.string.show_on_screen_debug_logs), AirPlayPersistence.loadDebugLogsEnabled(this)) {
                AirPlayPersistence.saveDebugLogsEnabled(this, it)
            }
        }
        columns.card(R.string.permissions_and_connection_help, R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            if (deviceSupportsWireless()) card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        hopMaintenanceCards(columns)
        columns.card(R.string.about, R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        languageCards(columns)
    }

    /**
     * The bucket only a local package has, drawn by [settings] when [ExperimentalFeatures.enabled].
     *
     * Its first tenant is the report upload. That button used to sit in the diagnostics card and appear
     * whenever the package happened to carry an upload key, which made "is this an official package?" a
     * question about a build input rather than about what the page shows. It is here now, under the same
     * switch that puts the key in the package, so what the page shows and what the package carries are
     * one answer.
     *
     * The key is still checked as well: `-Pexperimental=on` with no local.properties is a local package
     * with nothing to upload to, and a button that could only fail is worse than no button.
     */
    private fun experimentalCards(columns: CardColumns) {
        columns.card(R.string.experimental_report_upload, R.drawable.ic_dp_diagnostics) { card ->
            card.addView(label(getString(R.string.experimental_report_upload_hint), 16, MUTED))
            if (SupabaseLogUpload.enabled(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) {
                uploadButton = button(
                    if (uploadInProgress) getString(R.string.uploading_report) else getString(R.string.generate_and_upload_report),
                    false,
                ) { uploadGeneratedReport() }.apply { isEnabled = !uploadInProgress }
                card.addView(uploadButton, matchButton(16, 60))
            }
        }
    }

    /**
     * Ends the app on purpose. The control sits next to the home control in the header, so it is on
     * every page rather than only at the bottom of the system settings.
     *
     * What outlives a session goes back first — the car's own Bluetooth, and the mDNS socket the app
     * holds for its whole life so that no attempt can lose 5353 — and the process ends after, which is
     * what frees the socket when no session is running at all.
     */
    private fun closeTheApp() {
        releaseProcessWirelessResources(applicationContext)
        CarPlayBackgroundSession.exit { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    private fun displaySettings(columns: CardColumns) {
        columns.line(label(getString(R.string.display_and_audio), 34, TEXT, true))
        columns.line(label(getString(R.string.display_and_audio_hint), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        columns.line(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, 0, 0, dp(24)) })
        displayCards(columns)
    }

    private fun displayCards(columns: CardColumns) {
        displayPerformanceCard(columns)
        audioRoutingCard(columns)
    }

    private fun displayPerformanceCard(columns: CardColumns) {
        columns.card(R.string.display_and_performance, R.drawable.ic_dp_display) { card ->
            carPlaySizeControl(card)
            choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms), getString(R.string.s_500_ms_default), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            // A codec is a choice between two named things, not an on/off. "Efficient video" left the
            // driver to work out which way round it was; both options now say their own name, and the
            // default is stated on the option that is the default.
            choice(card, getString(R.string.video_codec),
                listOf(getString(R.string.video_codec_h264), getString(R.string.video_codec_h265)),
                if (AirPlayPersistence.loadHevcEnabled(this)) 1 else 0) {
                AirPlayPersistence.saveHevcEnabled(this, it == 1)
            }
            toggle(card, getString(R.string.direct_video_surface), getString(R.string.direct_video_surface_description), AirPlayPersistence.loadDirectSurfaceView(this)) { AirPlayPersistence.saveDirectSurfaceView(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
            // What the iPhone is told the screen measures. These decide the physical size CarPlay
            // lays out against, which is why they sit here rather than with the identity.
            val bases = AirPlayPhysicalSizeBasis.entries
            choice(card, getString(R.string.physical_size_basis),
                listOf(getString(R.string.widest_width), getString(R.string.longest_height)),
                bases.indexOf(physicalSizeBasis).coerceAtLeast(0)) {
                physicalSizeBasis = bases[it]
                AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
            }
            val lengths = (AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP).toList()
            choice(card, getString(R.string.physical_length),
                lengths.map { "$it mm" },
                lengths.indexOf(widthPhysicalMm).coerceAtLeast(0)) {
                widthPhysicalMm = lengths[it]
                AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
            }
        }
        // The cluster map keeps a gate of its own: it draws AirPlay's alt-screen stream on a public
        // presentation display, so what it needs is that display, not the car's HUD services. It used
        // to sit inside the BYD section and was hidden wherever those services were absent.
        val clusterDisplay = ClusterMapPresentation.findDisplay(this)
        columns.card(R.string.instrument_cluster_map, R.drawable.ic_dp_navigation) { card ->
            // The card is here on every head unit, including one that has no dashboard display at
            // the moment: a setting that silently disappears reads as one that was taken away, and
            // the note says which part of the car it is waiting for.
            if (clusterDisplay == null) {
                card.addView(label(getString(R.string.cluster_display_not_detected), 14, MUTED))
                return@card
            }
            toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                AirPlayPersistence.loadClusterMapEnabled(this)) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                reconnectForClusterMap()
            }
            if (DiLink51ClusterLayout.supported()) {
                val automatic = DiLink51ClusterLayout.automatic(this)
                toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                    getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                    DiLink51ClusterLayout.saveAutomatic(this, it)
                    render()
                    reconnectForClusterMap()
                }
                val allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    DiLink51ClusterMonitor.hasAccess(this)
                card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                    else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                if (!automatic) {
                    val themes = DiLink51ClusterLayout.Theme.entries
                    choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                        DiLink51ClusterLayout.saveTheme(this, themes[it])
                        reconnectForClusterMap()
                    }
                    card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                }
                val contrasts = DiLink51ClusterLayout.Contrast.entries
                choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                    DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                    reconnectForClusterMap()
                }
            } else {
                val sizes = CarPlayClusterDisplay.scalePresets
                val contents = CarPlayClusterDisplay.Content.entries
                val content = AirPlayPersistence.loadClusterContent(this)
                choice(card, getString(R.string.dashboard_shows), listOf(
                    getString(R.string.dashboard_content_map),
                    getString(R.string.dashboard_content_turn_card),
                    getString(R.string.dashboard_content_map_with_turn_card),
                ), contents.indexOf(content)) {
                    AirPlayPersistence.saveClusterContent(this, contents[it])
                    render()
                }
                // Both contents share the same safe area and position controls.
                val turnCard = content == CarPlayClusterDisplay.Content.TURN_CARD
                choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                    listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                    sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                    AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                }
                val across = CarPlayClusterDisplay.horizontalSteps.toList()
                choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                    across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                    AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                }
                val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                    upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                    AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                }
                card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                    AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                    AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                    render()
                    reconnectForClusterMap()
                }, matchButton(10, 56))
            }
        }
    }

    private fun audioRoutingCard(columns: CardColumns) {
        // The stream types sit in a row of equal shares now, not in cells measured against the
        // screen, so this card no longer has to claim the whole width to keep them inside itself.
        columns.card(R.string.audio_routing) { card ->
            val channelTitle = label(getString(R.string.navigation_stream_type), 18, TEXT, true)
            val channelHint = label(getString(R.string.tap_a_number_to_test_14_driver_speaker_on_byd), 14, MUTED)
            val grid = channelSelector()
            val saveButton = button(getString(R.string.save), true) {
                AirPlayPersistence.saveNavigationStreamType(this, navigationStreamType)
                toast(getString(R.string.audio_saved_value, navigationStreamType))
            }
            fun applyChannelEnabled(enabled: Boolean) {
                val alpha = if (enabled) 1f else 0.4f
                listOf(channelTitle, channelHint, saveButton).forEach {
                    it.isEnabled = enabled
                    it.alpha = alpha
                }
                // Through the buttons themselves, not the container: the cells are now grandchildren
                // of a column of rows, and walking `grid.childCount` would have dimmed three rows
                // and left all 21 buttons live inside them.
                channelButtons.forEach {
                    it.isEnabled = enabled
                    it.alpha = alpha
                }
            }
            val aaosSupported = resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
            if (aaosSupported) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    applyChannelEnabled(!it)
                }
            }
            card.addView(channelTitle)
            card.addView(channelHint)
            card.addView(grid)
            card.addView(saveButton, matchButton(12, 56))
            if (aaosSupported) applyChannelEnabled(!AirPlayPersistence.loadAdvancedAudioChannelMapping(this))
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on. Connecting now tries to switch it on itself, so
    // this only decides whether to warn first.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    // The AOSP tether action is not on every head unit; where nothing answers it, Wi-Fi settings is
    // the screen that does have the hotspot switch.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(hotspot, 0) == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        openSystem(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    /**
     * The two connection steps and the connect control, as cards.
     *
     * The numbering stays: these are the steps of setting the car's own hotspot and Bluetooth up, and the
     * order is the whole of their meaning. They have one home now, the settings page's wireless bucket,
     * rather than a page of their own that the connect flow could also land on.
     */
    private fun wirelessCards(columns: CardColumns, readiness: WirelessReadiness) {
        columns.card(R.string.s_1_car_hotspot) { card -> hotspotControls(card) }
        columns.card(R.string.s_2_bluetooth_and_phone) { card -> bluetoothControls(card) }
        columns.card(R.string.s_3_connect) { card ->
            readinessLines(card, readiness)
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(
                button(getString(R.string.connect_phone), true) { connect(true) }
                    .apply { isEnabled = sessionIsIdle() },
                matchButton(12, 60),
            )
            liveSessionNote(card)
        }
    }

    private fun wiredCards(columns: CardColumns) {
        if (UsableConnection.USB !in connectionReport().usable) return
        columns.card(R.string.connect_with_usb) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    /**
     * The Bluetooth radio, the adapter's startup step, and the iPhone — all on the card. Choosing any of
     * them used to open a dialog that had to be dismissed before the next step was readable, which is why
     * the step is a line here that keeps changing instead.
     *
     * The radios offered are the ones the probe says can carry the leg, not a fixed pair: a head unit whose
     * own stack cannot open the data socket is offered the adapter alone rather than a choice that cannot
     * work.
     */
    private fun bluetoothControls(card: LinearLayout) {
        val radios = connectionReport().bluetoothRadioOptions
        val hop = DiPlayPreferences.bluetoothHop(this)
        // The radio in use is always on the list: unplugging the chosen adapter must not take away the only
        // way to choose a different one.
        val offered = if (hop != null && hop !in radios) radios + hop else radios
        // A lone car radio that is already in use is the unmarked case -- "Bluetooth" on its own means the
        // car's -- so it is left unsaid. Everything else is drawn, a lone radio with nothing chosen included,
        // since otherwise the step would ask the driver to choose a radio it gives them no way to tap.
        val unmarked = hop == WirelessBluetoothHop.CAR && offered.singleOrNull() == WirelessBluetoothHop.CAR
        if (!unmarked) {
            if (offered.size > 1) {
                card.addView(label(getString(R.string.choose_bluetooth_before_phone), 16, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
            }
            legChoices(card, offered.map { WirelessLegOption(getString(bluetoothRadioName(it)), null, it == hop) }) { index ->
                chooseBluetoothRadio(offered[index])
            }
        }
        blockerLines(card, bluetoothLeg = true)
        adapterStatusView()?.let { card.addView(it) }
        iphoneChoice(card)
        liveSessionNote(card)
    }

    private fun bluetoothRadioName(hop: WirelessBluetoothHop): Int = when (hop) {
        WirelessBluetoothHop.USB_ADAPTER -> R.string.bluetooth_external
        WirelessBluetoothHop.CAR -> R.string.bluetooth_builtin
    }

    private fun chooseBluetoothRadio(hop: WirelessBluetoothHop) {
        if (hop == DiPlayPreferences.bluetoothHop(this)) return
        applyConnectionSetting {
            DiPlayPreferences.saveBluetoothHop(this, hop)
            // The adapter stack may not keep running once the car's own radio is the choice.
            if (hop == WirelessBluetoothHop.CAR) releaseAdapterPage()
        }
    }

    private fun iphoneChoice(card: LinearLayout) {
        when (DiPlayPreferences.bluetoothHop(this)) {
            WirelessBluetoothHop.USB_ADAPTER -> externalPhoneChoice(card)
            WirelessBluetoothHop.CAR -> builtinPhoneChoice(card)
            null -> card.addView(label(getString(R.string.gap_radio), 15, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        }
    }

    /** The phone the adapter itself paired. It only exists once the adapter's flow has got that far. */
    private fun externalPhoneChoice(card: LinearLayout) {
        if (AdapterRoutes.active?.present(this) != true) {
            // The one thing here that only the driver can fix, said before anything the phone could show:
            // without the adapter there is no radio to pair to, so a "pair the iPhone first" line would be
            // asking for something the driver cannot do yet.
            card.addView(label(getString(R.string.adapter_step_not_plugged), 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.adapter_check_again), false) { render() }, matchButton(8, 60))
            return
        }
        val target = adapterPage?.host?.pairedTarget()
        if (target == null) {
            card.addView(label(getString(R.string.pair_the_iphone_to_the_external_radio_first), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val chosen = DiPlayPreferences.phoneAddress(this)?.equals(target.address, ignoreCase = true) == true
        card.addView(button("${if (chosen) "✓  " else ""}${target.name ?: target.address}", chosen) {
            saveChosenPhone(target.address, target.name ?: "iPhone")
        }, matchButton(12, 60))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun builtinPhoneChoice(card: LinearLayout) {
        vendorHop(this)?.let { hop ->
            vendorHopPhoneChoice(card, hop)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            card.addView(button(getString(R.string.choose_iphone), false) { ensureBluetoothPermission() }, matchButton(12, 60))
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            card.addView(label(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first), 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.open_bluetooth), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(8, 60))
            return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            card.addView(label(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            card.addView(button(getString(R.string.open_bluetooth), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(8, 60))
            return
        }
        val chosenAddress = DiPlayPreferences.phoneAddress(this)
        devices.forEach { device ->
            val chosen = chosenAddress != null && chosenAddress.equals(device.address, ignoreCase = true)
            card.addView(button("${if (chosen) "✓  " else ""}${device.name ?: getString(R.string.paired_device)}", chosen) {
                saveChosenPhone(device.address, device.name ?: "iPhone")
            }, matchButton(8, 60))
        }
    }

    /**
     * The car hop on a head unit whose Bluetooth is not ours to read: the pairings belong to a service
     * this app does not own, so the list comes from the hop and so does the line describing it.
     */
    private fun vendorHopPhoneChoice(card: LinearLayout, hop: CarBluetoothHop) {
        hop.note(this)?.let { line ->
            card.addView(label(line, 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }
        hop.status(this)?.let { line ->
            card.addView(label(line, 15, MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        refreshHopPhones(hop)
        // Nothing to list until the services behind the hop answer; the note above already says what it does.
        val devices = hopPhones ?: return
        if (devices.isEmpty()) {
            // No jump to the system Bluetooth page here: it lists nothing on this head unit, because
            // the car's pairings never reach the AOSP adapter.
            card.addView(label(getString(R.string.no_phone_on_car_bluetooth), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val chosenAddress = DiPlayPreferences.phoneAddress(this)
        devices.forEach { device ->
            val chosen = chosenAddress != null && chosenAddress.equals(device.address, ignoreCase = true)
            card.addView(
                button("${if (chosen) "✓  " else ""}${device.name.ifBlank { getString(R.string.paired_device) }}", chosen) {
                    saveChosenPhone(device.address, device.name.ifBlank { "iPhone" })
                },
                matchButton(8, 60),
            )
        }
    }

    private var hopPhones: List<CarBluetoothPhone>? = null
    private var hopPhonesAt = 0L
    private var hopPhonesInFlight = false

    private var hopMaintenance: CarBluetoothMaintenance? = null
    private var hopMaintenanceAt = 0L
    private var hopMaintenanceInFlight = false

    /** The action id being carried out, or null. Only one runs at a time. */
    private var hopMaintenanceRunning: String? = null

    /** What the last action said when it finished, for the page to show under the buttons. */
    private var hopMaintenanceResult: String? = null

    /** True while the hop is being put in place, at app start or on the way into a session. */
    private var hopStartInFlight = false

    /**
     * Asks the hop which phones are connected, off the main thread.
     *
     * The services behind it post their `ServiceConnection` callbacks to the main looper, so waiting for
     * them from the main thread can never succeed: the page would freeze for the whole timeout and then
     * report no iPhone at all, with one connected. The answer is cached and reused for a few seconds
     * because the page redraws on every tap.
     */
    private fun refreshHopPhones(hop: CarBluetoothHop) {
        if (hopPhonesInFlight) return
        if (hopPhones != null && SystemClock.elapsedRealtime() - hopPhonesAt < HOP_DEVICES_TTL_MILLIS) return
        hopPhonesInFlight = true
        Thread({
            val devices = runCatching { hop.connectedPhones(this) }.getOrDefault(emptyList())
            runOnUiThread {
                hopPhonesInFlight = false
                if (isFinishing) return@runOnUiThread
                hopPhones = devices
                hopPhonesAt = SystemClock.elapsedRealtime()
                if (page == "home") render()
            }
        }, "car-hop-phones").apply { isDaemon = true }.start()
    }

    /**
     * Asks the hop what it can do to the head unit, away from the main thread.
     *
     * The answer costs a round trip through the head unit's own root service, so it is kept for a few
     * seconds rather than asked for while drawing. A hop that offers nothing is remembered as such
     * too, or the page would run the trip again on every redraw.
     */
    private fun refreshHopMaintenance(hop: CarBluetoothHop) {
        if (hopMaintenanceInFlight) return
        if (hopMaintenanceAt != 0L && SystemClock.elapsedRealtime() - hopMaintenanceAt < HOP_MAINTENANCE_TTL_MILLIS) return
        hopMaintenanceInFlight = true
        Thread({
            val maintenance = runCatching { hop.maintenance(this) }.getOrNull()
            runOnUiThread {
                hopMaintenanceInFlight = false
                if (isFinishing) return@runOnUiThread
                hopMaintenance = maintenance
                hopMaintenanceAt = SystemClock.elapsedRealtime()
                // Where the page belongs, given what the hop now says. Onto the waiting page while a
                // write runs — including one this process never started, because the script is the
                // head unit's and runs on while this app is gone — and off it when there is neither a
                // write nor an ending to report, which is a page a restored intent can still name and
                // not a place to leave anyone standing.
                when (maintenance?.status) {
                    CarBluetoothMaintenanceStatus.RUNNING -> {
                        startWatchingMaintenance(hop)
                        if (page != PAGE_FIRMWARE) page = PAGE_FIRMWARE
                    }
                    CarBluetoothMaintenanceStatus.NONE ->
                        if (page == PAGE_FIRMWARE && hopMaintenanceRunning == null) page = "system"
                    else -> Unit
                }
                if (page == "system" || page == PAGE_FIRMWARE) render()
            }
        }, "car-hop-maintenance").apply { isDaemon = true }.start()
    }

    /**
     * Whether a firmware write is in flight right now.
     *
     * Three answers, cheapest first, and each covers a gap the one before it has. The card knows, but
     * is a round trip behind; the launch flag knows about the moment just after a tap, before the card
     * has been asked again; and the hop's own file read covers an operation that began in an earlier
     * life of this process. None of the three touches the head unit.
     */
    private fun firmwareRunning(): Boolean {
        if (hopMaintenance?.status == CarBluetoothMaintenanceStatus.RUNNING) return true
        if (hopMaintenanceRunning != null) return true
        val hop = vendorHop(this) ?: return false
        return runCatching { hop.operationRunning(this) }.getOrDefault(false)
    }

    /**
     * Starts one action and follows it wherever it runs.
     *
     * The work goes to a thread because the launch itself costs a root round trip. What comes back is
     * not the outcome: the firmware script goes out into the background, so the call answers as soon
     * as it is running, and everything after that is read from the hop's own progress file. That is
     * what lets the page show an operation this process did not live through — and what makes a write
     * that outlasts the app something the driver can still be told about.
     */
    private fun runHopMaintenance(hop: CarBluetoothHop, action: CarBluetoothMaintenance.Action) {
        if (hopMaintenanceRunning != null) return
        // The confirmation can sit open while a session starts, and these replace the head unit's own
        // firmware: doing that under a running session is the one thing the head unit cannot survive
        // quietly. The buttons are withheld while a session is live; this is the second look.
        if (CarPlayBackgroundSession.hasSession()) {
            hopMaintenanceResult = getString(R.string.maintenance_session_active)
            render()
            return
        }
        hopMaintenanceRunning = action.id
        hopMaintenanceResult = null
        startWatchingMaintenance(hop)
        render()
        Thread({
            val message = runCatching { hop.performMaintenance(this, action.id) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            runOnUiThread {
                hopMaintenanceRunning = null
                hopMaintenanceAt = 0L
                if (isFinishing) return@runOnUiThread
                // A launch that went out is followed on the page that says so. A refusal has nothing to
                // follow, and its reason is the card's to report where the buttons are.
                if (firmwareRunning()) page = PAGE_FIRMWARE else hopMaintenanceResult = message
                refreshHopMaintenance(hop)
                if (page == "system" || page == PAGE_FIRMWARE) render()
            }
        }, "car-hop-maintenance-run").apply { isDaemon = true }.start()
    }

    /**
     * Keeps the card and the firmware page current while an operation runs.
     *
     * Driven by the operation and not by who asked for it, which is the whole point: the script is the
     * head unit's and goes on writing whether or not this process is alive, so the page has to be able
     * to pick up one it never started — after the app was killed, or after the driver went home.
     *
     * The tick keeps re-arming only while there is something to watch. It stops on its own when the
     * operation ends, and starts again when the page finds another one.
     */
    private var maintenanceWatched: CarBluetoothHop? = null

    private val maintenanceTick = object : Runnable {
        override fun run() {
            val hop = maintenanceWatched ?: return
            // isDestroyed as well as isFinishing: recreate() destroys an instance that is not
            // finishing, and a tick left over from one would go on drawing a page onto it.
            if (isFinishing || isDestroyed) return
            hopMaintenanceAt = 0L
            refreshHopMaintenance(hop)
            if (firmwareRunning()) handler.postDelayed(this, HOP_MAINTENANCE_WATCH_MILLIS)
        }
    }

    /**
     * Arms the tick, or re-arms it.
     *
     * Not a one-shot: the tick stops itself the moment there is nothing running, and the next launch —
     * or the next operation found in a state file — has to be able to start it again. Removing first
     * keeps two chains from running at once if this is reached while one is already armed.
     */
    private fun startWatchingMaintenance(hop: CarBluetoothHop) {
        maintenanceWatched = hop
        handler.removeCallbacks(maintenanceTick)
        handler.postDelayed(maintenanceTick, HOP_MAINTENANCE_WATCH_MILLIS)
    }

    /** These change the head unit, so what the hop says it does is shown before it does it. */
    private fun confirmHopMaintenance(hop: CarBluetoothHop, action: CarBluetoothMaintenance.Action) {
        AlertDialog.Builder(this)
            .setTitle(action.label)
            .setMessage(action.confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.maintenance_continue) { _, _ -> runHopMaintenance(hop, action) }
            .show()
    }

    /**
     * Whatever this build's hop can do to the head unit itself, when it can do anything.
     *
     * Absent everywhere else — the published tree, CI, any other head unit — because [vendorHop]
     * answers null there and this draws nothing.
     */
    private fun hopMaintenanceCards(columns: CardColumns) {
        val hop = vendorHop(this) ?: return
        val maintenance = hopMaintenance
        if (maintenance == null) {
            refreshHopMaintenance(hop)
            return
        }
        columns.card(maintenance.title, R.drawable.ic_dp_diagnostics) { card ->
            card.addView(label(maintenance.state, 18, TEXT))
            maintenance.detail?.let {
                card.addView(label(it, 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
            }
            maintenance.steps.forEachIndexed { index, step ->
                card.addView(stepLine(step, index, maintenance))
            }
            if (maintenance.status == CarBluetoothMaintenanceStatus.INTERRUPTED) {
                card.addView(
                    label(getString(R.string.firmware_interrupted), 15, WARNING)
                        .apply { setPadding(0, dp(12), 0, 0) },
                )
            }
            val busy = hopMaintenanceRunning != null ||
                maintenance.status == CarBluetoothMaintenanceStatus.RUNNING
            // Nothing is offered while a session is live, which is the reference's own rule and the
            // one the hop cannot check for itself: the session flag lives in this module.
            val sessionLive = CarPlayBackgroundSession.hasSession()
            if (sessionLive) {
                card.addView(
                    label(getString(R.string.maintenance_session_active), 15, WARNING)
                        .apply { setPadding(0, dp(12), 0, 0) },
                )
            }
            // The write that survives a reboot is a local-package feature. What is withheld here is
            // the offer, not the hop's ability to do it — the same split `PERMANENT_INSTALL_OFFERED`
            // makes inside the hop, and the same one the experimental bucket makes with the report
            // upload: the code is in both packages, the control is in one. The other two actions stay
            // in both, because the payload swap changes nothing on `/system` and the restore is the
            // way back out of an install a local package made.
            val offered = if (ExperimentalFeatures.enabled) {
                maintenance.actions
            } else {
                maintenance.actions.filterNot { it.permanent }
            }
            offered.forEach { action ->
                if (sessionLive) return@forEach
                val running = hopMaintenanceRunning == action.id
                card.addView(
                    button(
                        if (running) getString(R.string.maintenance_working) else action.label,
                        action.primary && !busy,
                    ) { confirmHopMaintenance(hop, action) }.apply { isEnabled = !busy },
                    matchButton(if (action.primary) 16 else 10, 60),
                )
            }
            hopMaintenanceResult?.let {
                card.addView(label(it, 15, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            }
        }
    }

    /**
     * One line of an operation's steps, drawn in whichever of three states it is in.
     *
     * Three, not two, and the third is what was missing. A step the operation has finished is not the
     * one it is on, and an operation that finished is not one still sitting on its last step — drawn
     * with two, an install that had completed looked exactly like one that had not started: one line
     * lit, every line before it grey, and no way to tell the two apart.
     */
    private fun stepLine(step: String, index: Int, maintenance: CarBluetoothMaintenance): View {
        val status = maintenance.status
        val onIt = index == maintenance.currentStep &&
            status != CarBluetoothMaintenanceStatus.NONE &&
            status != CarBluetoothMaintenanceStatus.DONE
        val done = index < maintenance.currentStep ||
            (status == CarBluetoothMaintenanceStatus.DONE && index <= maintenance.currentStep)
        val color = when {
            onIt && status != CarBluetoothMaintenanceStatus.RUNNING -> WARNING
            onIt || done -> ACCENT
            else -> MUTED
        }
        return label(if (done) "✓  $step" else step, 15, color, onIt || done)
            .apply { setPadding(dp(4), dp(8), 0, 0) }
    }

    /**
     * The page a write to `/system` is followed on, and the only page with no way off it from the
     * header.
     *
     * It exists because the alternative is what it replaces: the card sitting on the settings page
     * while the driver goes looking at other pages, and an operation that finishes somewhere they are
     * not. Here there is one thing to read, it is the thing that is happening, and the control that
     * leaves appears once there is nothing left to wait for.
     */
    private fun firmwareProgress(content: LinearLayout) {
        val columns = CardColumns(content)
        columns.line(label(getString(R.string.firmware_in_progress), 34, TEXT, true))
        columns.line(
            label(getString(R.string.firmware_in_progress_hint), 17, MUTED)
                .apply { setPadding(0, dp(8), 0, dp(24)) },
        )
        val maintenance = hopMaintenance
        if (maintenance == null) {
            columns.line(label(getString(R.string.maintenance_working), 18, MUTED))
            // Asked for from here and not only from the card. A restored intent can name this page
            // with nothing behind it, and a page that only ever drew would sit on "Working…" for ever
            // with no way off it.
            vendorHop(this)?.let { refreshHopMaintenance(it) }
            return
        }
        columns.card(maintenance.title, R.drawable.ic_dp_diagnostics, fullWidth = true) { card ->
            card.addView(label(maintenance.state, 18, TEXT))
            maintenance.steps.forEachIndexed { index, step ->
                card.addView(stepLine(step, index, maintenance))
            }
            firmwareOutcome(card, maintenance)
        }
    }

    /**
     * What there is to say once the steps are drawn, and the control that leaves.
     *
     * [CarBluetoothMaintenanceStatus.INTERRUPTED] is the one that carries a second line, because it is
     * the only ending the driver has to do something about: nothing is running, nothing reported an
     * outcome, and the head unit is in a state this app cannot watch to the end. That is also why the
     * control is offered on every ending but [CarBluetoothMaintenanceStatus.RUNNING]: the page is a
     * place to wait, not a place to be.
     */
    private fun firmwareOutcome(card: LinearLayout, maintenance: CarBluetoothMaintenance) {
        when (maintenance.status) {
            CarBluetoothMaintenanceStatus.RUNNING -> card.addView(
                label(getString(R.string.maintenance_working), 18, ACCENT)
                    .apply { setPadding(0, dp(12), 0, 0) },
            )
            CarBluetoothMaintenanceStatus.DONE -> card.addView(
                label(getString(R.string.firmware_finished), 18, ACCENT)
                    .apply { setPadding(0, dp(12), 0, 0) },
            )
            CarBluetoothMaintenanceStatus.FAILED -> card.addView(
                label(maintenance.detail ?: getString(R.string.firmware_not_done), 18, WARNING)
                    .apply { setPadding(0, dp(12), 0, 0) },
            )
            CarBluetoothMaintenanceStatus.INTERRUPTED -> {
                card.addView(
                    label(getString(R.string.firmware_interrupted), 18, WARNING)
                        .apply { setPadding(0, dp(12), 0, 0) },
                )
                card.addView(
                    label(getString(R.string.firmware_interrupted_hint), 15, MUTED)
                        .apply { setPadding(0, dp(8), 0, 0) },
                )
            }
            CarBluetoothMaintenanceStatus.NONE -> Unit
        }
        if (maintenance.status == CarBluetoothMaintenanceStatus.RUNNING) return
        card.addView(
            button(getString(R.string.back), true) {
                page = "system"
                hopMaintenanceAt = 0L
                render()
            },
            matchButton(16, 60),
        )
    }

    /**
     * Opens onto a write already under way, when there is one.
     *
     * Asked of the hop's own file read rather than of the card, which costs a round trip that the
     * first frame cannot wait for. A hop with nothing long-running answers false, and every other head
     * unit answers false because there is no hop at all.
     */
    private fun openOntoRunningFirmwareOperation() {
        val hop = vendorHop(this) ?: return
        if (!runCatching { hop.operationRunning(this) }.getOrDefault(false)) return
        page = PAGE_FIRMWARE
        startWatchingMaintenance(hop)
        render()
    }

    /** The hop this build ships, when it is for this head unit. */
    private fun vendorHop(context: Context): CarBluetoothHop? = CarBluetoothHops.active?.takeIf { hop ->
        runCatching { hop.applies(context) }.getOrDefault(false)
    }

    /**
     * Brings the car's own hotspot up as the app opens, when the driver has asked for that.
     *
     * Off the main thread: the firmware is polled until it reports the hotspot up, which is seconds on
     * this head unit, and the page is redrawn once it is done rather than while it is happening.
     *
     * The driver's Wi-Fi is never spent on this — `spendDriverWifi = false` in
     * [CarHotspotController.ensureEnabled] — because nobody asked for a session here, and on this head
     * unit their ADB goes over that same Wi-Fi.
     */
    private fun wakeCarHotspotInBackground() {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        if (!shouldWakeCarHotspotOnStartup(AirPlayPersistence.loadAutoCarHotspot(this), mode)) return
        Thread({
            DiagLog.i(TAG_CAR_HOTSPOT, "app-start hotspot begin mode=$mode")
            val result = CarHotspotController(applicationContext)
                .ensureEnabled(CAR_HOTSPOT_AUTO_ENABLE_TIMEOUT_MILLIS, spendDriverWifi = false)
            DiagLog.i(TAG_CAR_HOTSPOT, "app-start hotspot ${result.name.lowercase()} detail=${result.reason}")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (page == "home") render()
            }
        }, "car-hotspot-start").apply { isDaemon = true }.start()
    }

    /**
     * Asks the hop to put itself in place, off the main thread, and redraws when it is done.
     *
     * Cheap when there is nothing to do: the hop answers from the socket, without a round trip, when
     * its service is already up. Only one attempt runs at a time, so a page that redraws often cannot
     * turn this into a stream of root calls.
     *
     * The radio is put right after the hop is in place rather than before: on this head unit the switch
     * goes through the vendor's own process, and the hijack is what decides which process that is.
     * Nothing gates it — the car's Bluetooth is what the phone connects to, and a driver whose radio is
     * off would otherwise find out by failing to connect.
     */
    private fun startVendorHopInBackground() {
        val hop = vendorHop(this) ?: return
        if (hopStartInFlight) return
        hopStartInFlight = true
        Thread({
            runCatching { hop.onAppStart(this) }
            runCatching { hop.ensureRadioOn(this) }
            runOnUiThread {
                hopStartInFlight = false
                if (isFinishing) return@runOnUiThread
                if (page == "home") render()
            }
        }, "car-hop-start").apply { isDaemon = true }.start()
    }

    private fun gapsCard(content: LinearLayout, readiness: WirelessReadiness) {
        section(content, getString(R.string.wireless_not_ready)) { card -> readinessLines(card, readiness) }
    }

    /**
     * What is still missing before this route can connect, in one place. The connect step shows these lines
     * themselves, and a connect that was blocked shows the same lines above the steps so the reason is the
     * first thing on the page rather than the last.
     */
    private fun readinessLines(card: LinearLayout, readiness: WirelessReadiness = currentWirelessReadiness()) {
        val lines = readiness.gaps.map { gapText(it, readiness.adapter, it in readiness.blocking) }.toMutableList()
        val adapter = readiness.adapter
        if (adapter != null && WirelessGap.DONGLE_NOT_READY !in readiness.gaps && !adapter.flowEffective) {
            adapter.sentence?.let { lines += it }
        }
        lines.forEach { card.addView(label(it, 15, WARNING).apply { setPadding(0, dp(4), 0, dp(4)) }) }
    }

    /** One option of a leg of the wireless route: what it is called, what it means, whether it is the one in use. */
    private data class WirelessLegOption(val title: String, val description: String?, val chosen: Boolean)

    /**
     * One leg of the wireless route. One option that is already in use is a statement, because a picker that
     * has picked for you is a heading with a button under it — and on this project's head unit the hotspot
     * leg is usually the one-option case, the car hotspot being the route that has always been offered.
     * One option that is *not* in use stays a button: a leg with nothing chosen and nothing to tap would
     * be asking for a choice it gives no way to make.
     */
    private fun legChoices(parent: LinearLayout, options: List<WirelessLegOption>, onChoose: (Int) -> Unit) {
        if (options.isEmpty()) return
        val lone = options.singleOrNull()
        if (lone != null && lone.chosen) {
            parent.addView(label(lone.title, 22, TEXT, true))
            lone.description?.let { parent.addView(label(it, 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) }) }
            return
        }
        val wide = resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        options.forEachIndexed { index, option ->
            val cell = column()
            choices.addView(
                cell,
                if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) }
                else LinearLayout.LayoutParams(-1, -2),
            )
            cell.addView(button("${if (option.chosen) "✓  " else ""}${option.title}", option.chosen) { onChoose(index) }, matchButton(12, 60))
            option.description?.let { cell.addView(label(it, 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) }) }
        }
    }

    /**
     * The car's hotspot: which mode it is on, what the driver has to do to it, and whether it is on now.
     *
     * The state line used to be a sentence under the edit button, inside a sub-heading set larger than the
     * step it sat in. It is this leg's own progress, so it is drawn at this leg's own level, under the two
     * buttons that can change it.
     */
    private fun hotspotControls(card: LinearLayout) {
        val modes = connectionReport().wirelessModes
        if (modes.isEmpty()) {
            // No Wi-Fi service at all: there is no hotspot to point the phone at, so only the reason is left.
            blockerLines(card, bluetoothLeg = false)
            return
        }
        // A mode saved where it was offered can outlive the mode being offered: storage keeps the
        // local-only hotspot, which the probe may not offer. Deciding by the offered set keeps the mode
        // drawn and the mode in use the same one, and leaves a way back to the mode that does not need
        // those calls.
        val saved = AirPlayPersistence.loadWirelessHotspotMode(this)
        val mode = displayedHotspotMode(pendingCarHotspotSetup, saved, modes)
        // Left tappable while a session runs: choosing a mode is one of the settings
        // [applyConnectionSetting] can apply, and it asks before it does.
        legChoices(card, modes.map { WirelessLegOption(wirelessModeTitle(it), wirelessModeDescription(it), it == mode) }) { index ->
            // The choice is written down here, at the moment it is made, for both modes. It used to be
            // written for every mode but the car hotspot, which left the page drawing one mode while
            // storage — and so the connection, and the readiness lines — still held another.
            applyWirelessLink(modes[index])
        }
        blockerLines(card, bluetoothLeg = false)
        if (mode == WirelessHotspotMode.MANUAL) {
            card.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            if (pendingCarHotspotSetup) {
                // Setup mode is entered by tapping the mode and by a connect that found nothing usable, so it
                // needs an exit that is not "save something": on this head unit the one mode on offer is drawn
                // as a statement, so there is no other mode to tap to leave it.
                val actions = row().apply { gravity = Gravity.CENTER_VERTICAL }
                actions.addView(button(getString(R.string.save_hotspot_details_and_use_this_mode), true) { editHotspotCredentials() }, LinearLayout.LayoutParams(0, dp(60), 1f))
                actions.addView(button(getString(R.string.cancel), false) { cancelCarHotspotSetup() }, LinearLayout.LayoutParams(-2, dp(60)).apply { marginStart = dp(12) })
                card.addView(actions, matchButton(12, 60))
            } else {
                card.addView(button("${getString(R.string.edit_saved_hotspot_prefix)}${hotspotSsid()}", false) { editHotspotCredentials() }, matchButton(12, 60))
            }
            card.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
            // Off by default, and offered only here: switching the car's hotspot on spends the car's
            // Wi-Fi, so it is not something to start doing on a head unit whose driver never asked.
            toggle(card, getString(R.string.auto_open_car_hotspot), getString(R.string.auto_open_car_hotspot_help), AirPlayPersistence.loadAutoCarHotspot(this)) { AirPlayPersistence.saveAutoCarHotspot(this, it) }
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            card.addView(label(getString(R.string.existing_wifi_hint), 16, MUTED))
            card.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            // The one thing the driver has to get right is being on the same network as the iPhone,
            // and the car's own screen is where they set it. Saying which network the car is on now
            // is what turns "I think I did that" into something they can check before connecting.
            val joined = joinedWifiName()
            card.addView(
                label(
                    if (joined == null) getString(R.string.existing_wifi_not_joined)
                    else getString(R.string.existing_wifi_joined, joined),
                    15,
                    if (joined == null) WARNING else MUTED,
                ).apply { setPadding(0, dp(12), 0, 0) },
            )
            // The one part of the network DiPlay may not be able to read for itself. A head unit that
            // will not hand `getConfiguredNetworks()` the key leaves the phone being told to join a
            // secured network with no password to join it with, and the bring-up then waits for a phone
            // that has nothing to join with — which on this page looks like a connection that never
            // finishes. So the field is offered, and the line says which of the two states it is in
            // rather than promising that nothing else is needed.
            val saved = joined != null && AirPlayPersistence.hasExistingWifiPassphrase(this, joined)
            card.addView(
                label(
                    if (saved) getString(R.string.existing_wifi_password_saved)
                    else getString(R.string.existing_wifi_password_may_be_needed),
                    15,
                    if (saved) MUTED else WARNING,
                ).apply { setPadding(0, dp(12), 0, 0) },
            )
            card.addView(
                button(
                    getString(
                        if (saved) R.string.existing_wifi_change_password
                        else R.string.existing_wifi_enter_password,
                    ),
                    false,
                ) {
                    askExistingWifiCredentials { ssid, password ->
                        AirPlayPersistence.saveExistingWifiNetwork(this, ssid, password)
                        render()
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                },
                matchButton(10, 60),
            )
            // Every password the driver has saved, one row per network, read from storage on every
            // redraw. The two lines above name only the network the car is on, and on a head unit whose
            // Wi-Fi has not come up yet — every start after a reboot — that is no network at all, so a
            // saved password with no way to see or change it is what "my password was not saved" was
            // read from. Nothing here asks which network the car is on: the list is the same list
            // whatever the radio is doing.
            card.addView(label(getString(R.string.existing_wifi_saved_networks_title), 18, TEXT, true).apply { setPadding(0, dp(20), 0, 0) })
            card.addView(label(getString(R.string.existing_wifi_saved_networks_hint), 14, MUTED).apply { setPadding(0, dp(6), 0, dp(4)) })
            val savedNetworks = AirPlayPersistence.loadExistingWifiNetworks(this)
            if (savedNetworks.isEmpty()) {
                card.addView(label(getString(R.string.existing_wifi_saved_networks_none), 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            } else {
                savedNetworks.forEach { network ->
                    val savedRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
                    // The name takes the room the two buttons leave, and wraps inside it: a network
                    // name is up to 32 bytes, and the buttons are the same width whatever it says.
                    savedRow.addView(label(network.ssid, 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                    savedRow.addView(
                        button(getString(R.string.existing_wifi_edit_saved), false) {
                            askSavedWifiCredentials(network.ssid, network.passphrase) { ssid, password ->
                                AirPlayPersistence.saveExistingWifiNetwork(this, ssid, password)
                                render()
                                toast(getString(R.string.saved_for_your_next_connection))
                            }
                        },
                        LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(12) },
                    )
                    savedRow.addView(
                        button(getString(R.string.existing_wifi_delete_saved), false) {
                            AirPlayPersistence.removeExistingWifiNetwork(this, network.ssid)
                            render()
                        },
                        LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) },
                    )
                    card.addView(savedRow)
                }
            }
        } else {
            card.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            card.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    /**
     * The Wi-Fi network the car is on right now, or null when it is on none.
     *
     * Read only: this page never joins a network. It exists so the driver can see whether the step
     * they just did in the car's settings took.
     */
    private fun joinedWifiName(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val ssid = runCatching { wifi.connectionInfo?.ssid }.getOrNull()?.removeSurrounding("\"")
        return ssid?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
    }

    private fun hotspotSsid() = hotspot().first
    private fun hotspotPassword() = hotspot().second
    private fun hotspot(): Pair<String, String> =
        cachedHotspot ?: savedOrCarHotspot(this).also { cachedHotspot = it }
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    /**
     * The same rules under the words that fit a network the car joined.
     *
     * The rules really are the same — both pairs end up in the same two iAP2 fields — so only what the
     * driver is told changes. "Enter the car hotspot name" asks for the wrong thing here: the name is
     * the router's, or the other phone's, and it is not the driver's to choose.
     */
    private fun existingWifiError(ssid: String, password: String): String? =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid.trim(), password.trim())?.let { problem ->
            getString(
                when (problem) {
                    com.shilapi.xcertplay.orchestration.ManualHotspotValidation.Error.EMPTY_NAME ->
                        R.string.network_error_empty_name
                    com.shilapi.xcertplay.orchestration.ManualHotspotValidation.Error.LONG_NAME ->
                        R.string.network_error_long_name
                    com.shilapi.xcertplay.orchestration.ManualHotspotValidation.Error.INVALID_CHARACTER ->
                        R.string.hotspot_error_invalid_character
                    com.shilapi.xcertplay.orchestration.ManualHotspotValidation.Error.PASSWORD_LENGTH ->
                        R.string.network_error_password_length
                },
            )
        }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) = askNetworkCredentials(
        title = getString(R.string.car_hotspot_details),
        hint = getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av),
        nameHint = getString(R.string.hotspot_name),
        passwordHint = getString(R.string.hotspot_password),
        initialSsid = hotspotSsid(),
        initialPassphrase = hotspotPassword(),
        validate = ::hotspotError,
        done = done,
    )

    /**
     * The password of the network the car is on, which DiPlay may not have been able to read.
     *
     * The same two fields as the car hotspot's, for the same reason: both end up in the same two iAP2
     * fields, so both are held to the same rules. What differs is who supplies them — there the driver
     * copies them off the car's hotspot screen, here they type the network's own password because the
     * platform would not hand DiPlay the copy the car saved.
     *
     * The fields start from what is stored for the network the car is on and from nothing else: a
     * password belongs to one network, and one offered under another name is a wrong password rather
     * than a missing one. With the car on no network there is nothing to start from, and the list
     * further up this card is where a password is changed instead.
     */
    private fun askExistingWifiCredentials(done: (String, String) -> Unit): Unit {
        val joined = joinedWifiName()
        // Only the password saved for *this* network. Each stored entry belongs to one network, and
        // prefilling one under another name invites a driver to press Save without retyping — which
        // writes the wrong password against the right name, and that is a pair the phone will be
        // handed as though it were correct. A password from the wrong network is worse than none.
        //
        // The name is the stored entry's own spelling, and only the stored entry's: that lookup is
        // [AirPlayPersistence.hasExistingWifiPassphrase]'s, so the entry prefilled here is the entry
        // that would be sent. Both fields come off the one entry, never one off each.
        val stored = joined
            ?.takeIf { AirPlayPersistence.hasExistingWifiPassphrase(this, it) }
            ?.let { name ->
                AirPlayPersistence.loadExistingWifiNetworks(this)
                    .firstOrNull { sameWifiNetworkName(it.ssid, name) }
            }
        askNetworkCredentials(
            title = getString(R.string.existing_wifi_password_title),
            hint = getString(R.string.copy_these_from_the_wi_fi_the_car_is_on),
            nameHint = getString(R.string.wifi_network_name),
            passwordHint = getString(R.string.wifi_network_password),
            initialSsid = stored?.ssid ?: joined.orEmpty(),
            initialPassphrase = stored?.passphrase.orEmpty(),
            validate = ::existingWifiError,
            done = done,
        )
    }

    /**
     * The two fields for one password already saved, opened from that network's own row.
     *
     * The same dialog as the one above, for the same reason and under the same rules — what differs is
     * where it starts from. There it is the network the car is on, which is not a network the driver
     * can choose; here it is the row that was tapped, which is the only way to correct a password for a
     * network the car is not on right now.
     *
     * The name is shown, not offered: a stored entry is looked up by its name, so the name is that
     * entry's identity rather than one of its fields, and the save below upserts by name. An editable
     * field here would let a driver correct a spelling and thereby file a *second* entry under the new
     * name — the old one, password and all, left in the list, which is an edit that silently adds a
     * network. Held to the tapped row's name, this dialog changes that entry's password and can write
     * no other.
     *
     * A name that is genuinely wrong is corrected by deleting the row and filling the password in on the
     * card above, which is where a network not in the list gets one.
     */
    private fun askSavedWifiCredentials(ssid: String, passphrase: String, done: (String, String) -> Unit) =
        askNetworkCredentials(
            title = getString(R.string.existing_wifi_edit_saved_title),
            hint = getString(R.string.existing_wifi_edit_saved_hint),
            nameHint = getString(R.string.wifi_network_name),
            passwordHint = getString(R.string.wifi_network_password),
            initialSsid = ssid,
            initialPassphrase = passphrase,
            validate = ::existingWifiError,
            editableName = false,
            done = done,
        )

    /**
     * The two fields a network's name and password are typed into, wherever they come from.
     *
     * [editableName] is false for the one caller that edits a stored entry rather than typing a new
     * pair: an entry is looked up by its name, so there the name is drawn greyed and cannot be typed
     * over. Every other caller is asking for a name as much as for a password, and leaves it true.
     */
    private fun askNetworkCredentials(
        title: String,
        hint: String,
        nameHint: String,
        passwordHint: String,
        initialSsid: String,
        initialPassphrase: String,
        validate: (String, String) -> String?,
        done: (String, String) -> Unit,
        editableName: Boolean = true,
    ) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(hint, 16, MUTED))
        val ssid = EditText(this).apply {
            this.hint = nameHint; setText(initialSsid); setSingleLine()
            // Filled but greyed: what this entry is filed under, which is not something this dialog can
            // change. Disabled as well as unfocusable, so neither a tap nor a keyboard can reach it.
            if (!editableName) { isEnabled = false; isFocusable = false }
        }
        val password = EditText(this).apply {
            this.hint = passwordHint; setText(initialPassphrase); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = validate(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    /**
     * Writes the chosen route, and enters setup mode only when there is something to set up.
     *
     * The flag used to be "the driver chose the car hotspot", which made choosing a route the same
     * act as entering a form — and on a page with no form (the cockpit) it could only ever refuse
     * the next connection. It is derived from the credentials now: a car that already holds a usable
     * name and password has nothing to ask for, and one that does not is exactly the case the form
     * exists for. This is the same question the readiness check asks, from the same two values.
     */
    private fun applyWirelessLink(
        mode: WirelessHotspotMode,
        setupPending: Boolean = mode == WirelessHotspotMode.MANUAL &&
            hotspotError(hotspotSsid(), hotspotPassword()) != null,
        andThen: () -> Unit = {},
    ) {
        applyConnectionSetting {
            // Written with the mode, inside the store block, so a driver who is asked to confirm and
            // then declines is left with both as they were. Setting the flag before the dialog left the
            // page drawing the car hotspot while storage — and the connection — still held the other
            // mode, which is the disagreement this change exists to remove.
            pendingCarHotspotSetup = setupPending
            AirPlayPersistence.saveWirelessHotspotMode(this, mode)
            andThen()
            toast(getString(R.string.saved_for_your_next_connection))
        }
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        // A session must not start while the hop is changing the head unit: it would be built on a
        // socket that is about to move, and the operation would unwind underneath it. Checked before
        // anything else, because none of the gaps below are the reason to refuse this time.
        val hopBusy = wireless &&
            vendorHop(this)?.let { hop -> runCatching { hop.busy(this) }.getOrDefault(false) } == true
        if (hopBusy) {
            toast(getString(R.string.maintenance_busy_no_session))
            return
        }
        if (wireless && !CarPlayBackgroundSession.hasSession()) {
            val readiness = currentWirelessReadiness()
            if (!readiness.ready) {
                // What is missing belongs next to the controls that fix it, not in a dialog over them.
                // The hop's own service is one of those gaps, and putting it in place runs off the main
                // thread: ask for it now, and the page redraws when it is there.
                startVendorHopInBackground()
                refuseConnection(null, readiness.blocking.first())
                return
            }
            wirelessBlocked = false
        }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(hotspotSsid(), hotspotPassword()) != null) {
            pendingCarHotspotSetup = true
            refuseConnection(
                getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings),
                WirelessGap.HOTSPOT_CREDENTIALS,
            )
            return
        }
        // The hotspot being off is one of the readiness gaps, so it is said on the page with the other
        // gaps rather than in a dialog of its own. That dialog could only ever have been reached with a
        // session already up, where the check above does not run — which is also why these two refusals
        // raise the card themselves rather than leaning on the check above to have raised it.
        if (wireless && carHotspotOff()) { refuseConnection(null, WirelessGap.HOTSPOT_OFF); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            refuseConnection(null, WirelessGap.PHONE_NOT_CHOSEN)
            return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            val watch = adapterPage.also { adapterPage = null }
            val project = {
                if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) openProjection()
            }
            if (watch == null) {
                project()
            } else {
                // The connection page claims this same stick. Wait for the release off the UI thread.
                Thread({
                    watch.releaseAndWait()
                    runOnUiThread { project() }
                }, "adapter-release").start()
            }
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    /**
     * What is running and what a connection would still be missing, read once per redraw.
     *
     * Held for the length of one redraw, like [connectionReport] and for the same reason: laying the
     * page out asks several times over, and a card that reads the session for itself is a card that
     * can disagree with the step beside it about whether anything is running.
     */
    private fun pageState(): WirelessPageState = cachedPageState ?: WirelessPageState(
        session = sessionPhase(CarPlayBackgroundSession.hasSession(), CarPlayBackgroundSession.active),
        readiness = currentWirelessReadiness(),
    ).also { cachedPageState = it }

    /** Whether a new connection may be started. See [SessionPhase] for why this is not just "running". */
    private fun sessionIsIdle(): Boolean = pageState().session == SessionPhase.Idle

    /**
     * Said under a control that is greyed out because a session is running, so the grey has a reason
     * on the page rather than only in the code. Nothing on this page tears a running session down;
     * the way to a different one is the Disconnect button on the home page.
     */
    private fun liveSessionNote(parent: LinearLayout) {
        if (sessionIsIdle()) return
        parent.addView(
            label(getString(R.string.carplay_is_running_this_needs_it_closed), 15, MUTED)
                .apply { setPadding(0, dp(10), 0, 0) },
        )
    }

    /**
     * Save a setting the next connection is built from.
     *
     * Nothing here reconnects on the driver's behalf. Reconnecting would take a value that was just
     * changed — and may be wrong — and start a whole new attempt with it, which is the shape of the
     * afternoon this came from: the mode changed, the retry loop kept running on the old one, and
     * every thirty seconds the screen said the same thing. The session is closed instead, the page
     * goes home, and the driver chooses wireless or USB when they are ready. One more step, and every
     * part of it is theirs. See 49 §一 and §D.
     *
     * The display settings do not come through here: they go back to `choice(reconnects = true)`,
     * because a wrong one of those comes back as a picture that is merely the wrong shape, and being
     * sent home for that is a punishment rather than a safeguard.
     */
    private fun applyConnectionSetting(store: () -> Unit) {
        // Read from the session rather than from [pageState]: this runs from a click, which can be long
        // after the last redraw, and the one-redraw cache would offer to disconnect a session that has
        // since ended.
        val session = sessionPhase(CarPlayBackgroundSession.hasSession(), CarPlayBackgroundSession.active)
        if (session == SessionPhase.Idle) {
            store()
            render()
            return
        }
        if (session == SessionPhase.Connecting) {
            // No picture to lose, so no question — but the attempt in flight was built from the value
            // being replaced, and letting it run on would put a picture up on the old one. See 49 §一:
            // the middle row is "cancel the attempt, then store".
            CarPlayBackgroundSession.stop { runOnUiThread { store(); render() } }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.apply_and_disconnect_title))
            .setMessage(getString(R.string.apply_and_disconnect_body))
            .setPositiveButton(getString(R.string.apply_and_disconnect)) { _, _ ->
                CarPlayBackgroundSession.stop {
                    runOnUiThread { store(); page = "home"; render() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * A connection that cannot start: say why, and stay where the driver is.
     *
     * Every refusal here used to end in `page = "wireless"` — so pressing Connect, the one control
     * whose whole job is to connect, moved the driver to a settings page and left them to work out
     * why. The reason belongs under the button they pressed. The way to the controls that fix it is
     * the "Open settings" button the raised card carries, which the driver presses knowing where it
     * goes; that is a different thing from being sent there.
     */
    private fun refuseConnection(reason: String?, gap: WirelessGap) {
        reason?.let { toast(it) }
        // A connect that never started is still something to count: without this line, a page that
        // refused ten times reads the same as a day nobody tried.
        AttemptJournal.writeRefusal(this, gap)
        wirelessBlocked = true
        render()
    }

    private fun connectionReport(): ConnectionSupportReport =
        cachedReport ?: DeviceConnectionSupport.inspect(this).also { cachedReport = it }

    private fun deviceSupportsWireless(): Boolean = connectionReport().wirelessCapable

    /**
     * Whether any radio on this head unit could carry the iAP2 leg at all.
     *
     * [deviceSupportsWireless] asks whether the route can be driven, which a head unit with no radio
     * answers the same way as one whose Wi-Fi is switched off. This is the narrower fact behind the
     * "not supported" line: there is nothing here to switch on.
     */
    private fun hasWirelessRadio(): Boolean = connectionReport().wirelessSupported

    /** Which routes this head unit can run. What a route is missing is said by that route, not here. */
    private fun supportedConnectionLabel(): TextView {
        val report = connectionReport()
        val text = if (report.usable.isEmpty()) {
            getString(R.string.connection_methods_none)
        } else {
            getString(R.string.connection_methods_supported, report.usable.joinToString(" · ", transform = ::connectionMethodName))
        }
        return label(text, 15, if (report.usable.isEmpty()) WARNING else MUTED).apply { setPadding(0, 0, 0, dp(12)) }
    }

    /** Whether a blocker is about the Bluetooth leg rather than about the hotspot the phone joins. */
    private fun CarHotspotBlocker.isBluetoothLeg(): Boolean = when (this) {
        CarHotspotBlocker.NO_BLUETOOTH_ADAPTER,
        CarHotspotBlocker.BLUETOOTH_CALL_FAILED,
        CarHotspotBlocker.BLUETOOTH_PERMISSION,
        CarHotspotBlocker.BLUETOOTH_OFF,
        CarHotspotBlocker.NO_RFCOMM,
        -> true
        CarHotspotBlocker.NO_WIFI,
        CarHotspotBlocker.NO_HOTSPOT_API,
        -> false
    }

    /**
     * The reasons this route cannot be driven, under the leg each one is about. They used to be printed
     * together at the top of the page, furthest from either of the two things they were talking about.
     */
    private fun blockerLines(card: LinearLayout, bluetoothLeg: Boolean) {
        connectionReport().carHotspotNotes.filter { it.isBluetoothLeg() == bluetoothLeg }
            .forEach { card.addView(label(carHotspotNote(it), 15, WARNING).apply { setPadding(0, dp(4), 0, dp(4)) }) }
    }

    private fun carHotspotNote(blocker: CarHotspotBlocker): String = when (blocker) {
        CarHotspotBlocker.NO_WIFI -> getString(R.string.connection_block_no_wifi)
        CarHotspotBlocker.NO_HOTSPOT_API -> getString(R.string.connection_block_no_hotspot_api)
        CarHotspotBlocker.NO_BLUETOOTH_ADAPTER -> getString(R.string.connection_block_no_bluetooth)
        CarHotspotBlocker.BLUETOOTH_CALL_FAILED -> getString(R.string.connection_block_bluetooth_call)
        CarHotspotBlocker.BLUETOOTH_PERMISSION -> getString(R.string.connection_block_bluetooth_permission)
        CarHotspotBlocker.BLUETOOTH_OFF -> getString(R.string.connection_block_bluetooth_off)
        CarHotspotBlocker.NO_RFCOMM -> getString(R.string.connection_block_no_rfcomm)
    }

    private fun connectionMethodName(connection: UsableConnection): String = when (connection) {
        UsableConnection.USB -> getString(R.string.connect_with_usb)
        UsableConnection.CAR_HOTSPOT -> getString(R.string.built_in_car_hotspot)
    }

    private fun wirelessModeTitle(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.built_in_car_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.same_wifi_network)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
    }

    private fun wirelessModeDescription(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_mode_manual_desc)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.hotspot_mode_existing_wifi_desc)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * One way in, as a card on the home page. [note] is what is missing for this route right now,
     * said on the route itself rather than in a dialog over both of them.
     */
    private fun connectionEntry(
        title: String,
        hint: String,
        note: String?,
        action: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View = column().apply {
        background = rounded(SURFACE, if (note == null) BORDER else WARNING)
        setPadding(dp(22), dp(20), dp(22), dp(20))
        addView(label(title, 22, TEXT, true))
        addView(label(hint, 15, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        if (note != null) addView(label(note, 15, WARNING).apply { setPadding(0, dp(8), 0, 0) })
        val buttons = row().apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(
            button(action, true) { onClick() }.apply { isEnabled = enabled },
            LinearLayout.LayoutParams(0, dp(64), 1f),
        )
        addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    /** The manual fallback for the hotspot pair, for when auto-fill is not what the driver wants. */
    private fun editHotspotCredentials() {
        askHotspotCredentials { ssid, password ->
            // Inside the store block, so a driver who is asked to confirm and then declines has not
            // had the details written down behind the dialog.
            applyWirelessLink(WirelessHotspotMode.MANUAL, setupPending = false) {
                saveHotspotCredentials(ssid, password)
            }
        }
    }

    /**
     * Leaves setup mode without saving any details. The car hotspot stays the chosen mode — that is what the
     * driver picked — and its unsaved name and password are still unsaved, with the readiness line below
     * saying so; only the demand to save before connecting goes.
     */
    private fun cancelCarHotspotSetup() {
        pendingCarHotspotSetup = false
        render()
    }

    /** What the wireless route asks of the driver, for the hotspot mode that is in effect. */
    private fun wirelessEntryHint(): String = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.hotspot_hint_existing)
    }


    /**
     * The car's own Bluetooth adapter is read straight from the system stack, and the permission that read needs
     * is the only thing that still opens a system prompt. The chooser itself is on the connection page.
     */
    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            render()
        }
    }

    private fun saveChosenPhone(address: String, name: String) {
        applyConnectionSetting { DiPlayPreferences.savePhone(this, address, name) }
    }

    private fun externalBluetoothSelected() =
        DiPlayPreferences.bluetoothHop(this) == WirelessBluetoothHop.USB_ADAPTER

    private fun adapterStatusView(): TextView? {
        if (!externalBluetoothSelected()) {
            releaseAdapterPage()
            adapterStatus = null
            return null
        }
        val seen = adapterStatusNow()
        if (!seen.plugged) {
            adapterStatus = null
            return null
        }
        val view = label("", 15, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) }
        adapterStatus = view
        paintAdapterStatus(view, seen)
        return view
    }

    /** The adapter's step goes into the line already on screen; starting up must not rebuild the page. */
    private fun paintAdapterStatus(view: TextView, seen: WirelessAdapterStatus) {
        if (CarPlayBackgroundSession.hasSession()) {
            view.text = getString(R.string.adapter_status_while_connecting)
            view.setTextColor(MUTED)
            return
        }
        view.text = seen.sentence
        view.setTextColor(if (seen.flowEffective) MUTED else WARNING)
    }

    /**
     * The adapter as it stands.
     *
     * Its session is only brought up when the radio is on the bus: a page holding a status for a radio that is
     * not in the socket must not claim the USB interface. A run already holding the interface is left alone —
     * its own session owns the stick — and the answer is whatever it last reported.
     */
    private fun adapterStatusNow(): WirelessAdapterStatus {
        if (!externalBluetoothSelected()) {
            releaseAdapterPage()
            return noAdapterSeen().also { adapterSeen = it }
        }
        if (CarPlayBackgroundSession.hasSession()) return adapterSeen ?: noAdapterSeen()
        val route = AdapterRoutes.active ?: run {
            releaseAdapterPage()
            return noAdapterSeen().also { adapterSeen = it }
        }
        val page = adapterPage ?: newAdapterPage(route)
        if (!route.present(this)) return page.status.also { adapterSeen = it }
        return page.refresh(com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this))
            .also { adapterSeen = it }
    }

    private fun newAdapterPage(route: com.shilapi.xcertplay.transport.adapter.AdapterRoute): AdapterPage =
        route.page(
            context = this,
            onStatus = { seen ->
                adapterSeen = seen
                adapterStatus?.let { paintAdapterStatus(it, seen) }
            },
            onLine = {},
        ).also { adapterPage = it }

    /** What to report when there is no adapter stack in this build, or the car's own radio is the choice. */
    private fun noAdapterSeen() = WirelessAdapterStatus(
        plugged = false,
        named = false,
        flowEffective = false,
        sentence = null,
    )

    private fun releaseAdapterPage() {
        adapterPage?.close()
        adapterPage = null
    }

    private fun registerUsbTopology() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching { registerReceiver(usbTopology, filter) }
    }

    private fun unregisterUsbTopology() = runCatching { unregisterReceiver(usbTopology) }

    /**
     * Media mount events, listened to exactly while the page is up — the same pair of callbacks as
     * [registerUsbTopology]. The action is only ever sent by the system, so the receiver is not
     * exported; targetSdk 37 makes that flag mandatory on Android 13 and later.
     */
    private fun registerMediaMount() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        // An old ROM that refuses the registration must not take the settings page down with it;
        // the vendor mount roots are still scanned as a fallback.
        runCatching { ContextCompat.registerReceiver(this, mediaMountReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private fun unregisterMediaMount() = runCatching { unregisterReceiver(mediaMountReceiver) }

    /**
     * Plugging the adapter in puts its step line on the page; pulling it out takes the line away. Only a change
     * of presence redraws, so this cannot become the redraw loop the per-second bus read used to be.
     */
    private fun onUsbTopologyChanged() {
        if (!externalBluetoothSelected()) return
        val plugged = AdapterRoutes.active?.present(this) == true
        if (plugged == adapterPluggedSeen) return
        adapterPluggedSeen = plugged
        if (page == "home") render()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun carBluetoothOn(): Boolean {
        vendorHop(this)?.let { hop ->
            // The AOSP adapter is off on this head unit, so reading it would leave the connection page
            // stuck on CAR_BLUETOOTH_OFF for ever. What matters here is the leg the session will use.
            return runCatching { hop.ready(this) }.getOrDefault(false)
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    /**
     * What a connection would be missing right now.
     *
     * Read from the head unit on every call — the once-a-second tick calls it too, because a fact
     * that changed without a redraw is exactly what the tick is for. What that costs here is a
     * couple of preference reads, the AP state (one reflective call), the vendor radio's ready flag
     * and the joined SSID; this build has no adapter route, so the USB enumeration below is never
     * reached. A build that adds one would make this the wrong thing to call every second.
     */
    private fun currentWirelessReadiness(): WirelessReadiness {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val hop = DiPlayPreferences.bluetoothHop(this)
        val plugged = AdapterRoutes.active?.present(this) == true
        // The adapter radio is the only one whose gaps read the step line, and that line is the adapter's own
        // wording — so it is asked even with the radio out of the socket, where it says so.
        val seen = if (hop == WirelessBluetoothHop.USB_ADAPTER) adapterStatusNow() else noAdapterSeen()
        val pairedAddress = adapterPage?.host?.pairedTarget()?.address
        val saved = DiPlayPreferences.phoneAddress(this)
        val phoneChosen = when (hop) {
            WirelessBluetoothHop.USB_ADAPTER -> pairedAddress != null && saved?.equals(pairedAddress, ignoreCase = true) == true
            WirelessBluetoothHop.CAR -> saved != null
            null -> false
        }
        return WirelessReadinessCheck.assess(
            WirelessReadinessInput(
                credentialsSaved = hotspotError(hotspotSsid(), hotspotPassword()) == null,
                // The car's own access point, straight from the head unit, and null where it will not
                // say. Not `!carHotspotOff()`: that is a car-hotspot-mode notion, false for every other
                // mode whatever the hotspot is doing, so reading it here said the hotspot was running
                // on a head unit that had never been asked — and refused the same-network route over it.
                hotspotOn = com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this),
                // Asked rather than assumed: where the firmware exposes the AP switch, the same-network
                // route is allowed to start with the hotspot on and the connect path releases it. Where
                // it does not, the gap is the driver's to close and is reported as such.
                hotspotCanBeReleased = com.shilapi.xcertplay.network.CarHotspotController(this).canReleaseHotspot(),
                hop = hop,
                phoneChosen = phoneChosen,
                carBluetoothOn = carBluetoothOn(),
                adapterPlugged = plugged,
                adapter = if (plugged || hop == WirelessBluetoothHop.USB_ADAPTER) seen else null,
                mode = mode,
                // The same-network route is the only one that asks the car to be a station, and the
                // only one whose gap list reads this. Answered from the same read the page already
                // uses to show the driver which network the car joined.
                carWifiAssociated = joinedWifiName() != null,
            ),
        )
    }

    /**
     * The sentence for a gap. [blocks] is whether this gap refuses the connection or only says
     * something worth knowing: the car's own hotspot being on is both, depending on whether DiPlay
     * can release it, and the two readings ask the driver to do opposite things.
     */
    private fun gapText(gap: WirelessGap, adapter: WirelessAdapterStatus?, blocks: Boolean = true): String = when (gap) {
        WirelessGap.HOTSPOT_CREDENTIALS -> getString(R.string.gap_hotspot_credentials)
        WirelessGap.HOTSPOT_OFF -> getString(R.string.gap_hotspot_off)
        WirelessGap.HOTSPOT_ON -> getString(
            if (blocks) R.string.gap_car_hotspot_on else R.string.gap_car_hotspot_on_releasing,
        )
        WirelessGap.CAR_WIFI_DISCONNECTED -> getString(R.string.gap_car_wifi_disconnected)
        WirelessGap.RADIO_NOT_CHOSEN -> getString(R.string.gap_radio)
        WirelessGap.CAR_BLUETOOTH_OFF -> getString(carBluetoothOffText(AdapterRoutes.available))
        WirelessGap.PHONE_NOT_CHOSEN -> getString(R.string.gap_phone)
        WirelessGap.DONGLE_NOT_READY -> adapter?.sentence ?: getString(R.string.gap_adapter_missing)
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .show()
    }

    private fun refreshStatus() {
        // A clock-driven skin has to change on the clock. Checked here because the tick is the only
        // thing that runs while the cockpit is on screen and nothing is being pressed; compared
        // against the palette a redraw would produce now, so it fires once per actual change.
        if (themeScheduleAtRender == ThemeSchedule.AUTO_TIME) {
            val expected = CockpitThemeManager.resolvePalette(
                skinAtRender,
                themeScheduleAtRender,
                resources.configuration.uiMode,
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            )
            if (expected !== palette) {
                render()
                return
            }
        }
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            // The entries start a connection; this button only returns to one already running.
            connectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        // The cockpit's stage is repainted here rather than rebuilt: this runs on every tick, and a
        // rebuild a second would be the churn the in-place design exists to avoid. The gaps are the
        // same ones the settings page prints, from the same one readiness object.
        //
        // The phase and the gaps are both asked for fresh instead of taken from [pageState]: that
        // object holds its answer for the length of one redraw, and the tick is not a redraw —
        // reading it painted whatever was true when the page was built. The phase kept saying a
        // connection that had ended was up; the gaps kept saying a hotspot that had since been
        // turned off was still in the way, until some other tap rebuilt the page.
        hero?.let { card ->
            val readiness = currentWirelessReadiness()
            val blocked = wirelessBlocked && readiness.blocking.isNotEmpty()
            // A notice is what is left once nothing blocks. While something does, the card stays
            // quiet until the driver tries — the refusal is what raises it, and that has not
            // changed — and when it is raised the whole list goes up with it.
            val shown = if (blocked || readiness.blocking.isEmpty()) readiness.gaps else emptyList()
            card.updateState(
                sessionPhase(CarPlayBackgroundSession.hasSession(), CarPlayBackgroundSession.active),
                DiPlayPreferences.phoneName(this),
                shown.map { gapText(it, readiness.adapter, it in readiness.blocking) },
                blocked,
            )
        }
        // The quick column reads the same live values: the car can join or leave a network, and its
        // saved hotspot name can change in the car's settings, while this page is up — no tap
        // rebuilds it. Equal values are a no-op inside updateState, so a quiet tick touches nothing.
        widgets?.updateState(
            currentHotspotMode(),
            hotspotSsid(),
            joinedWifiName(),
            audioChannelLabel(),
        )
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    /**
     * One button, one exit. The store decides which layer can take the bytes; this only reports what
     * it decided, in the words that decision deserves.
     *
     * Nothing is probed here. Proving a volume writable means creating a directory and writing a file
     * to it, and doing that while the page is laid out is disk I/O on the drawing thread — the one
     * pause a driver notices as a frozen screen.
     */
    private fun exportDiagnostics() {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val mounted = mountedUsbPaths.toSet()
        Thread({
            val result = runCatching {
                val report = buildDiagnosticReport(appContext)
                DiagnosticExportStore.export(appContext, fileName, report, mounted) to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                result.fold(
                    onSuccess = { (outcome, report) -> showExportOutcome(outcome, report) },
                    onFailure = {
                        // Storage was gone for every layer including the app's own. The one useful
                        // action left is the one that failed, so the button offers it again.
                        AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                            .setMessage(getString(R.string.diagnostic_report_save_failed_body))
                            .setPositiveButton(getString(R.string.save_diagnostic_report)) { _, _ -> exportDiagnostics() }
                            .setNegativeButton(getString(R.string.close), null).show()
                    },
                )
            }
        }, "diplay-export").start()
    }

    /**
     * What happened, not what was attempted. A report on the stick and a report on the head unit are
     * different titles and different sentences, and the probe's own line sits under both, in small
     * print: it names the mount point and the kernel's reason, which is the only evidence a driver can
     * carry away from a head unit with no adb and no log view.
     */
    private fun showExportOutcome(outcome: ExportOutcome, report: String) {
        val toUsb = outcome.destination == ExportDestination.USB_ROOT ||
            outcome.destination == ExportDestination.USB_PACKAGE_DIR
        val body = getString(
            if (toUsb) R.string.diagnostic_report_saved_to_usb else R.string.diagnostic_report_saved_to_device_no_usb,
            outcome.primaryPath,
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(if (toUsb) R.string.diagnostic_report_saved else R.string.no_writable_usb_detected))
            .setMessage(TextUtils.concat(body, probeLine(outcome.probeSummary)))
            .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
            .setNegativeButton(getString(R.string.done), null)
        // No managed copy means no URI the provider may sign, so there is nothing a share sheet could
        // open. A button that could only fail is worse than no button.
        outcome.shareableUri?.let { shareUri ->
            builder.setNeutralButton(getString(R.string.share)) { _, _ -> shareReport(shareUri, report) }
        }
        builder.show()
    }

    /** The probe's evidence line: 12sp and muted, under the sentence about where the file went. */
    private fun probeLine(summary: String): CharSequence {
        if (summary.isBlank()) return ""
        return SpannableString("\n\n$summary").apply {
            setSpan(AbsoluteSizeSpan(12, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MUTED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun shareReport(uri: Uri, report: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_diagnostic_report)))
        }.onFailure { showDiagnosticReport(report) }
    }

    /**
     * The part a reader needs to place the log: which build, which car, which settings. Kept separate
     * from the log text so a byte budget can never spend it — the upload path used to keep the tail of
     * the whole report, which dropped exactly this block and kept the log.
     */
    private fun buildDiagnosticHeader(appContext: android.content.Context): String = buildString {
        appendLine("DiPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${setupErrorDetail?.let { "authentication unavailable — $it" } ?: "ready"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine(DiagnosticCounters.summary())
        // Directly under the loss counters, and in the header rather than in a log section: the byte
        // budget always keeps the header, and a reader looking for "why is it slow" should not have
        // to scroll through a bring-up that ended three runs ago to find the numbers.
        appendLine(PerfTrace.summary())
        appendLine()
        appendLine("--- Connection attempts (newest last) ---")
        appendLine(recentAttempts(appContext))
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
    }

    /**
     * The last five attempts, one line each — the block that answers "did it connect, and where did it
     * stop" without reading a log.
     *
     * Rendered from the same records the appendix carries verbatim, so the summary and the evidence
     * cannot tell different stories. Times are the head unit's wall clock, which may not be right:
     * they place a run for a reader, and the ordering comes from the file.
     */
    private fun recentAttempts(appContext: android.content.Context): String {
        val attempts = AttemptJournal.lines(appContext)
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .takeLast(5)
        if (attempts.isEmpty()) return "No connection attempt recorded yet."
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        return attempts.joinToString("\n") { attempt ->
            buildString {
                val started = attempt.optLong("t")
                append(if (started > 0) clock.format(Date(started)) else "--:--:--")
                append("  ")
                append(attempt.optString("transport", "-"))
                append(' ')
                append(attempt.optString("mode", "-"))
                append("  ")
                append(attempt.optString("result", "-").lowercase(Locale.US))
                append("  ")
                append(attempt.optString("at", "-"))
                append("  ")
                append(attempt.optString("why", "-"))
                append("  ")
                append(attempt.optLong("ms"))
                append("ms")
                if (attempt.optBoolean("reset")) append("  wifi-reset")
            }
        }
    }

    private fun buildDiagnosticSections(appContext: android.content.Context): List<DiagnosticSection> {
        val logs = SessionLogFile.REPORT_NAMES.mapNotNull { name ->
            val file = File(appContext.filesDir, "logs/$name")
            if (!file.isFile) return@mapNotNull null
            DiagnosticSection(
                name = name,
                lines = file.useLines { lines -> lines.mapNotNull { DiagnosticRedactor.redact(it) }.toList() },
                newest = name == "diplay.log",
            )
        }
        // Appended without the generic redaction pass. A record is redacted when it is written, and that
        // pass replaces a whole line that mentions a credential with a single token — for a JSON line
        // that means the record stops being one, in the one place the record is supposed to be readable.
        val attempts = AttemptJournal.lines(appContext)
        return if (attempts.isEmpty()) {
            logs
        } else {
            logs + DiagnosticSection(AttemptJournal.EVENTS, attempts, newest = true)
        }
    }

    private fun buildDiagnosticReport(appContext: android.content.Context): String =
        DiagnosticDigest.compose(
            buildDiagnosticHeader(appContext),
            buildDiagnosticSections(appContext),
        )

    /**
     * The report in full, oldest run first.
     *
     * Save fits the same content into one file by ranking it and cutting what does not fit (see
     * [DiagnosticDigest]); uploading has no reason to cut anything, because the bucket's cap is per
     * object and [SupabaseLogUpload.parts] splits the report across as many objects as it needs. A part
     * therefore reads the way a log does — in the order things happened — instead of starting with the
     * lines a ranking put first.
     */
    private fun buildCompleteReport(appContext: android.content.Context): String = buildString {
        appendLine(buildDiagnosticHeader(appContext))
        for (section in buildDiagnosticSections(appContext)) {
            appendLine("--- ${section.name} ---")
            section.lines.forEach { appendLine(it) }
        }
    }

    /** Generates the whole report, then uploads it in as many objects as its size needs. */
    private fun uploadGeneratedReport() {
        if (uploadInProgress) return
        uploadInProgress = true
        uploadButton?.apply { isEnabled = false; text = getString(R.string.uploading_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_KEY
        Thread({
            val result = runCatching {
                val report = buildCompleteReport(appContext)
                val parts = SupabaseLogUpload.parts(report)
                parts.forEachIndexed { index, body ->
                    SupabaseLogUpload.post(
                        url,
                        key,
                        SupabaseLogUpload.partName(fileName, index, parts.size),
                        body,
                    )
                }
                parts.size to report.toByteArray(Charsets.UTF_8).size
            }
            runOnUiThread {
                uploadInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                uploadButton?.apply { isEnabled = true; text = getString(R.string.generate_and_upload_report) }
                if (result.isSuccess) {
                    val (count, bytes) = result.getOrThrow()
                    val destination = if (count == 1) {
                        fileName
                    } else {
                        SupabaseLogUpload.partName(fileName, 0, count) + " … " +
                            SupabaseLogUpload.partName(fileName, count - 1, count)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.report_uploaded))
                        .setMessage(getString(R.string.report_uploaded_detail, destination, bytes))
                        .setPositiveButton(getString(R.string.done), null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.could_not_upload_the_report))
                        .setMessage(result.exceptionOrNull()?.message?.take(400).orEmpty())
                        .setPositiveButton(getString(R.string.close), null)
                        .show()
                }
            }
        }, "diplay-log-upload").start()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            // Read once. The file is 384 KB and every tap used to open and read it again on the UI
            // thread, which is a few milliseconds of disk on the one thread that must not wait.
            val pcm = cachedTestTonePcm
                ?: assets.open("navigation_test.pcm").use { it.readBytes() }.also { cachedTestTonePcm = it }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state.toString()))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        // 4.5s: the clip is 192,226 samples at 44.1 kHz, which is 4.36s. Anything shorter cuts the
        // tone off mid-beep, which sounds like a broken stream rather than a test.
        handler.postDelayed(stop, 4500)
    }

    /** The stream-type cells, for the test that pins them to the card rather than to the screen. */
    internal fun channelButtonsForTest(): List<Button> = channelButtons.toList()

    /** Play the test tone from a test without reaching into the private members around it. */
    internal fun playTestToneForTest(streamType: Int) = playTestTone(streamType)

    internal fun testToneTrackForTest(): AudioTrack? = testToneTrack

    internal fun testToneStopForTest(): Runnable? = toneStop

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        val content = rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER)
        target.background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null)
        } else content
    }

    /**
     * The 21 stream types, three rows of seven, each cell an equal share of the card it is in.
     *
     * The cells used to be sized `(screenWidthPixels - 80) / 7`: seven of those plus their margins
     * came to about 1890 px on this 1920 px head unit, which is wider than the drawer or the card
     * that has to hold them. Everything past the second column was clipped away and could not be
     * tapped — the row was drawn against the screen, not against its own parent. A row of
     * `layout_weight = 1f` cells is measured against that parent and cannot overflow it.
     */
    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val cols = 7
        var row: LinearLayout? = null
        for (i in 0..20) {
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        if (i > 0) topMargin = dp(6)
                    }
                }
                container.addView(row)
            }
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                setPadding(0, 0, 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val col = i % cols
            val params = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = if (col > 0) dp(3) else 0
                marginEnd = if (col < cols - 1) dp(3) else 0
            }
            row?.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return container
    }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"

    private fun languageCards(columns: CardColumns) {
        columns.card(R.string.language_section_title) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@DiPlayActivity, icon)?.let {
                DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, ACCENT) }
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    /**
     * A page's cards, laid out across the width it has.
     *
     * One column is what a page of four entry cards wants, and it is what every page here used to get.
     * The settings page carries everything now -- hotspot, phone, the two connect steps, display, audio,
     * diagnostics, firmware, language -- and a card stretched across the whole 1856 px of a 1920 px head
     * unit leaves most of its own row empty while the page runs to several screens. On a wide screen the
     * cards are placed two to a row instead.
     *
     * Pairs, not two independent columns: a row is then as tall as its taller card and the page still
     * reads left to right, which filling one whole column before the next does not give. A narrow head
     * unit keeps the single column it always had, and so does anything added through [fullWidth] --
     * the audio card measures its own grid against the screen rather than against the card it sits in,
     * and half a row would put that grid straight out of the card.
     */
    private inner class CardColumns(private val content: LinearLayout, forceNarrow: Boolean = false) {
        private val wide = !forceNarrow && resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        private var openRow: LinearLayout? = null
        private var inRow = 0

        /** A full-width line between the cards: the page's own headings and hints. */
        fun line(view: View) {
            openRow = null
            inRow = 0
            content.addView(view)
        }

        /** A section card, titled and iconed as [section] does it. */
        fun card(title: Int, icon: Int? = null, fullWidth: Boolean = false, build: (LinearLayout) -> Unit) =
            card(getString(title), icon, fullWidth, build)

        /** The same, for a title that is not a resource -- the hop's own words for the firmware. */
        fun card(title: String, icon: Int? = null, fullWidth: Boolean = false, build: (LinearLayout) -> Unit) {
            section(cellFor(fullWidth), title, icon, build)
        }

        /** The full-width lane itself, for what is not a section card -- the readiness card, say. */
        fun raw(): LinearLayout {
            openRow = null
            inRow = 0
            return content
        }

        private fun cellFor(fullWidth: Boolean): LinearLayout {
            if (!wide || fullWidth) {
                openRow = null
                inRow = 0
                return content
            }
            var row = openRow
            if (row == null || inRow >= 2) {
                row = row().apply { gravity = Gravity.TOP }
                content.addView(row)
                openRow = row
                inRow = 0
            }
            val first = inRow == 0
            inRow++
            return column().also { cell ->
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = dp(18) })
            }
        }
    }

    /**
     * A two-state row.
     *
     * The control is drawn here instead of using `SwitchCompat`, which under this app's theme is
     * invisible and unclickable: the theme is `android:style/Theme.Holo.NoActionBar`, not an
     * AppCompat theme, so the default switch style it resolves its thumb and track drawables from
     * does not exist. Both come back null and the switch measures to zero width — it is in the view
     * tree, it just has no size to draw or to be touched in. Every row using it read as plain text.
     */
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        var current = value
        val pill = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(88)
            minHeight = dp(48)
            isClickable = true
        }
        fun paint() {
            pill.text = getString(if (current) R.string.switch_on else R.string.switch_off)
            pill.setTextColor(if (current) BG else MUTED)
            pill.background = rounded(if (current) ACCENT else SURFACE, if (current) ACCENT else BORDER)
        }
        paint()
        pill.setOnClickListener {
            current = !current
            paint()
            save(current)
        }
        line.addView(pill)
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val content = rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER)
        background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null)
        } else content
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val EXTRA_SETTINGS_TAB = "settings_tab"
        private const val KEY_SETTINGS_TAB = "key_settings_tab"

        /**
         * Asks for the home page with its settings drawer already open.
         *
         * The one caller today is the three-finger swipe on the CarPlay picture, which needs the
         * settings without being sent to a second settings surface of its own.
         */
        const val EXTRA_OPEN_SETTINGS_DRAWER = "open_settings_drawer"

        /**
         * The width at which a page is given two columns instead of one.
         *
         * A 1920 px head unit at 160 dpi is 1200 dp, so this is what "wide" means here; a head unit
         * narrower than this keeps the single column, where a halved card could not hold its own labels.
         */
        private const val WIDE_LAYOUT_MIN_WIDTH_DP = 850

        /**
         * The page a write to the head unit's `/system` is followed on.
         *
         * Not one of the buckets: it is reached by asking for a firmware action, or by opening the app
         * onto one already running, and it is left by its own control. Nothing routes to it from the
         * header, which is why it draws none.
         */
        private const val PAGE_FIRMWARE = "firmware"

        private const val HOP_DEVICES_TTL_MILLIS = 10_000L

        /** How long a hop's maintenance answer is reused before it is asked for again. */
        private const val HOP_MAINTENANCE_TTL_MILLIS = 10_000L

        /** How often the page re-asks while an action runs, so the step being attempted moves. */
        private const val HOP_MAINTENANCE_WATCH_MILLIS = 700L

        /** Where the app-start hotspot switch reports itself, beside the connect-time one. */
        private const val TAG_CAR_HOTSPOT = "DiPlay-CarHotspot"

        /** The same budget the connect-time switch gets: the firmware is polled until it reports. */
        private const val CAR_HOTSPOT_AUTO_ENABLE_TIMEOUT_MILLIS = 15_000L

        private val WARNING = Color.rgb(255, 196, 128)
    }

    /**
     * The page's own colours, taken from the palette the redraw resolved.
     *
     * They used to be fixed dark constants, which is why every page but the cockpit stayed dark-navy
     * under a white skin. One set of names, one source: [palette] is recomputed once per redraw, so
     * these move with it and no card has to know which skin is in effect.
     */
    private val BG: Int get() = palette.screenBg
    private val SURFACE: Int get() = palette.cardBg
    private val BORDER: Int get() = palette.cardBorder
    private val ACCENT: Int get() = palette.accent
    private val TEXT: Int get() = palette.textMain
    private val MUTED: Int get() = palette.textMuted
}

/**
 * Which of the two car-Bluetooth wordings to show.
 *
 * The longer one ends by sending the driver to the external radio, so it is only true on a page that offers
 * one — the same answer the radio choices at the top of the page take from [AdapterRoutes]. Whichever build
 * this is, this is the one place that decides.
 */
internal fun carBluetoothOffText(hasAdapter: Boolean): Int =
    if (hasAdapter) R.string.gap_car_bluetooth else R.string.gap_car_bluetooth_no_adapter
