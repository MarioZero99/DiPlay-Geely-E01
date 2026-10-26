package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.media.NavigationAudioStream
import com.shilapi.xcertplay.network.ExistingWifiDriverCredentials
import com.shilapi.xcertplay.network.existingWifiPassphraseFor
import com.shilapi.xcertplay.network.sameWifiNetworkName
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.ui.theme.AppSkin
import com.shilapi.xcertplay.ui.theme.ThemeSchedule
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_DIRECT_SURFACE_VIEW = "direct_surface_view"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"

    /** Every network the car has been on and the password the driver typed for each, as pairs. */
    private const val KEY_EXISTING_WIFI_SSID = "existing_wifi_ssid"
    private const val KEY_EXISTING_WIFI_PASSPHRASE = "existing_wifi_passphrase"
    private const val KEY_EXISTING_WIFI_NETWORKS = "existing_wifi_networks"

    /** The two fields of one entry of [KEY_EXISTING_WIFI_NETWORKS]. */
    private const val KEY_EXISTING_WIFI_ENTRY_SSID = "ssid"
    private const val KEY_EXISTING_WIFI_ENTRY_PASSPHRASE = "passphrase"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_CLUSTER_MAP_SCALE = "cluster_map_scale_percent"
    private const val KEY_CLUSTER_MEDIA_METADATA = "cluster_media_metadata_enabled"
    private const val KEY_CLUSTER_MEDIA_SOURCE = "cluster_media_source"
    private const val KEY_CLUSTER_CONTENT = "cluster_content"
    private const val KEY_CLUSTER_MARKER_X = "cluster_marker_horizontal_step"
    private const val KEY_CLUSTER_MARKER_Y = "cluster_marker_vertical_step"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_AUTO_CAR_HOTSPOT = "auto_car_hotspot"
    private const val KEY_APP_SKIN = "app_skin"
    private const val KEY_THEME_SCHEDULE = "theme_schedule"
    private const val KEY_STEERING_WHEEL_MAPPINGS = "steering_wheel_key_mappings"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_OEM_LABEL = "GEELY"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadDirectSurfaceView(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIRECT_SURFACE_VIEW, directSurfaceViewDefault(Build.VERSION.SDK_INT))

    fun saveDirectSurfaceView(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DIRECT_SURFACE_VIEW, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_STREAM_TYPE, NavigationAudioStream.deviceDefault)

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    /**
     * The transport the driver picked, or null when they never did.
     *
     * `wireless_enabled` used to answer both questions at once — "what did you use last time" and
     * "what should we do at launch" — and a boolean cannot say "nobody has chosen". The home page
     * needs that third answer to ask instead of opening a wireless attempt nobody requested.
     * Readers that only want a decision keep using [loadWirelessEnabled].
     */
    fun loadConnectionMode(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_WIRELESS_ENABLED)) prefs.getBoolean(KEY_WIRELESS_ENABLED, true) else null
    }

    /** Last transport the user actually started. Defaults to wireless when nothing was chosen. */
    fun loadWirelessEnabled(context: Context): Boolean = loadConnectionMode(context) ?: true

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    /**
     * The saved hotspot mode, rewritten to one this build can run.
     *
     * A string the enum no longer has — `WIFI_P2P`, written by an install that offered it — reads as
     * the car hotspot, and the write below puts that back into storage so the next read is not the
     * migration again.
     *
     * The same-network route is experimental: only a local package offers it. A value written by one
     * of those reads as the car hotspot here, and is written back as one — without this the
     * connection would keep running the experimental route while the page, which no longer draws it,
     * said car hotspot. The official package's way to the same network is the car hotspot it has.
     */
    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        val supported = supportedHotspotMode(mode)
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = supportedHotspotMode(mode)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    /** The mode this package can actually run: the one that is gone reads as the car hotspot. */
    private fun supportedHotspotMode(mode: WirelessHotspotMode): WirelessHotspotMode = when {
        mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> WirelessHotspotMode.MANUAL
        else -> mode
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    /**
     * Every password the driver has saved, one per network.
     *
     * A password belongs to one network, and the same password filed under a different name is a wrong
     * password — so each entry carries its network's name, and the connect path uses the one whose name
     * matches the network the car actually joined. Keeping only the last one would make saving a
     * password for today's network throw away yesterday's, and the driver would be asked for the same
     * password again the next time they were on it.
     *
     * This exists because the platform is allowed to refuse. `WifiManager.getConfiguredNetworks()`
     * hands an app the network's details but not always its key, and on a head unit that refuses, the
     * phone is told to join a secured network with no password to join it with. The car's own copy is
     * tried first; this is what the driver can supply when the car will not.
     *
     * Reading never throws and never hands back an entry that could not be sent. The text parsed here
     * is ours, but it is also just a file: it can be truncated, hand-edited, or left by a build with a
     * different idea of what an entry is, and none of those may cost the driver the passwords that are
     * still readable in it.
     */
    fun loadExistingWifiNetworks(context: Context): List<ExistingWifiDriverCredentials> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_EXISTING_WIFI_NETWORKS, null)
            ?.let(::decodeExistingWifiNetworks)
            ?.let { return it }
        // Nothing readable under the list's key. The single pair is the whole of what a build from
        // before this change could have written, so it seeds the list — this is what carries a password
        // saved before the list existed forward, once. It is not written back as a list here: the next
        // save or removal answers for the list itself, and until then the pair is still the truth.
        return legacyExistingWifiCredentials(
            prefs.getString(KEY_EXISTING_WIFI_SSID, null),
            prefs.getString(KEY_EXISTING_WIFI_PASSPHRASE, null),
        )
    }

    /**
     * The password held for that network, or `""`.
     *
     * Which entry is this network's is answered by [existingWifiPassphraseFor], the same function the
     * connect path asks: the page and the session get their answer from one rule, so what this reports
     * as saved is what would be sent, and a change to how names are matched cannot reach one of them
     * without reaching the other.
     */
    fun loadExistingWifiPassphrase(context: Context, ssid: String): String =
        existingWifiPassphraseFor(loadExistingWifiNetworks(context), ssid).orEmpty()

    /**
     * Saves the driver's password for one network.
     *
     * A network already in the list keeps its place and takes the new password: the list is written
     * oldest first, and a network does not become the newest thing in it because its password was
     * corrected. A network that is not in it yet is appended.
     *
     * The single pair this app used to keep is written as well, holding what was just saved. A build
     * from before this change reads only that pair, and the one password it can hold is best spent on
     * the network the driver typed a password for last.
     */
    fun saveExistingWifiNetwork(context: Context, ssid: String, passphrase: String) {
        val cleanSsid = ssid.trim()
        val cleanPassphrase = passphrase.trim()
        require(cleanPassphrase.isEmpty() || cleanPassphrase.length in 8..63) {
            "Wi-Fi password must be 8–63 characters"
        }
        val stored = loadExistingWifiNetworks(context)
        val held = stored.indexOfFirst { sameWifiNetworkName(it.ssid, cleanSsid) }
        val updated = if (held >= 0) {
            stored.toMutableList().apply {
                this[held] = ExistingWifiDriverCredentials(cleanSsid, cleanPassphrase)
            }
        } else {
            stored + ExistingWifiDriverCredentials(cleanSsid, cleanPassphrase)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXISTING_WIFI_NETWORKS, encodeExistingWifiNetworks(updated))
            .putString(KEY_EXISTING_WIFI_SSID, cleanSsid)
            .putString(KEY_EXISTING_WIFI_PASSPHRASE, cleanPassphrase)
            .apply()
    }

    /**
     * Forgets the password held for one network and leaves every other one alone.
     *
     * The single pair goes with it when it names the same network: it is a second copy of one of these
     * entries, and a build from before this change would otherwise go on handing out a password the
     * driver has just taken away. Its own name is what decides — clearing it whenever any network is
     * removed would throw away the one password such a build can still use.
     */
    fun removeExistingWifiNetwork(context: Context, ssid: String) {
        val remaining = loadExistingWifiNetworks(context)
            .filterNot { sameWifiNetworkName(it.ssid, ssid) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(KEY_EXISTING_WIFI_NETWORKS, encodeExistingWifiNetworks(remaining))
        val legacySsid = prefs.getString(KEY_EXISTING_WIFI_SSID, null).orEmpty()
        if (legacySsid.isNotEmpty() && sameWifiNetworkName(legacySsid, ssid)) {
            editor.remove(KEY_EXISTING_WIFI_SSID).remove(KEY_EXISTING_WIFI_PASSPHRASE)
        }
        editor.apply()
    }

    /** Whether a password is held for this network, so the page can say so instead of asking again. */
    fun hasExistingWifiPassphrase(context: Context, ssid: String): Boolean =
        ssid.isNotEmpty() && loadExistingWifiPassphrase(context, ssid).isNotEmpty()

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    /**
     * The driver's wheel table, or the default when nothing usable is stored.
     *
     * A blob this build cannot read is treated as absent rather than half-honoured: the default wheel
     * works, where a partly-read one is a wheel whose keys quietly do nothing.
     */
    fun loadSteeringWheelKeyMappings(context: Context): SteeringWheelKeyMappings =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STEERING_WHEEL_MAPPINGS, null)
            ?.let { SteeringWheelKeyMappingsCodec.decode(it) }
            ?: SteeringWheelKeyMappings.DEFAULT

    fun saveSteeringWheelKeyMappings(context: Context, mappings: SteeringWheelKeyMappings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STEERING_WHEEL_MAPPINGS, SteeringWheelKeyMappingsCodec.encode(mappings))
            .apply()
    }

    /** Back to the two track codes the wheel is known to send here. */
    fun resetSteeringWheelKeyMappings(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STEERING_WHEEL_MAPPINGS)
            .apply()
    }

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadAutoCarHotspot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CAR_HOTSPOT, false)

    fun saveAutoCarHotspot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_CAR_HOTSPOT, enabled)
            .apply()
    }

    fun loadAppSkin(context: Context): AppSkin =
        AppSkin.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APP_SKIN, AppSkin.ZEEKR_WHITE.key),
        )

    fun saveAppSkin(context: Context, skin: AppSkin) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_SKIN, skin.key)
            .apply()
    }

    fun loadThemeSchedule(context: Context): ThemeSchedule =
        ThemeSchedule.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME_SCHEDULE, ThemeSchedule.AUTO_CAR.key),
        )

    fun saveThemeSchedule(context: Context, schedule: ThemeSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_SCHEDULE, schedule.key)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)

    fun saveClusterMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MAP, enabled).apply()
    }

    fun loadClusterMediaMetadataEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MEDIA_METADATA, true)

    fun saveClusterMediaMetadataEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MEDIA_METADATA, enabled).apply()
    }

    fun loadClusterMediaSource(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MEDIA_SOURCE, 2) // Default 2 (Bluetooth)

    fun saveClusterMediaSource(context: Context, source: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MEDIA_SOURCE, source).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    @Volatile private var cachedIdentity: AirPlayIdentity? = null

    @Synchronized
    fun resetCachedIdentityForTesting() {
        cachedIdentity = null
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        cachedIdentity?.let { return it }
        synchronized(this) {
            cachedIdentity?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
            val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
            val pairingId = prefs.getString(KEY_PAIRING_ID, null)
            val identity = if (privateKey != null && publicKey != null && pairingId != null) {
                AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
            } else {
                AirPlayIdentity.generate().also { gen ->
                    prefs.edit()
                        .putString(KEY_IDENT_PRIVATE, gen.privateKey.toHex())
                        .putString(KEY_IDENT_PUBLIC, gen.publicKey.toHex())
                        .putString(KEY_PAIRING_ID, gen.pairingId)
                        .apply()
                }
            }
            cachedIdentity = identity
            return identity
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"

    /**
     * The stored list, or null when there is nothing under the key that can be read as one.
     *
     * "[A] list was stored here" and "nothing readable was stored here" are different answers — the
     * first is a driver with no saved passwords, the second is a driver whose list this build has to
     * seed from the single pair — and only null can carry the difference to the caller.
     *
     * Inside the list, one bad element is dropped and the rest are kept. Every rule here is one the
     * value has to satisfy to be worth sending: an entry with no name cannot be looked up, an entry
     * with U+0000 in either field is not a name or a password any iAP2 message can carry, and a
     * passphrase of the wrong shape is one the phone will reject at association — sending it would
     * turn "no password" into "wrong password", which is worse.
     */
    private fun decodeExistingWifiNetworks(text: String): List<ExistingWifiDriverCredentials>? =
        runCatching {
            val entries = JSONArray(text)
            (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val ssid = entry.opt(KEY_EXISTING_WIFI_ENTRY_SSID) as? String
                val passphrase = entry.opt(KEY_EXISTING_WIFI_ENTRY_PASSPHRASE) as? String
                if (ssid.isNullOrBlank() || passphrase == null) return@mapNotNull null
                if ('\u0000' in ssid || '\u0000' in passphrase) return@mapNotNull null
                if (passphrase.isNotEmpty() && !isWifiPassphraseShape(passphrase)) return@mapNotNull null
                ExistingWifiDriverCredentials(ssid, passphrase)
            }
        }.getOrNull()

    private fun encodeExistingWifiNetworks(
        networks: List<ExistingWifiDriverCredentials>,
    ): String {
        val entries = JSONArray()
        networks.forEach { network ->
            entries.put(
                JSONObject()
                    .put(KEY_EXISTING_WIFI_ENTRY_SSID, network.ssid)
                    .put(KEY_EXISTING_WIFI_ENTRY_PASSPHRASE, network.passphrase),
            )
        }
        return entries.toString()
    }

    /**
     * The pair as a list of one, for the build that had only the pair.
     *
     * Only a complete pair counts: half of it is a name with no password or a password with no name,
     * and neither is a password that could be sent to anyone.
     */
    private fun legacyExistingWifiCredentials(
        ssid: String?,
        passphrase: String?,
    ): List<ExistingWifiDriverCredentials> =
        if (
            ssid.isNullOrBlank() ||
            passphrase.isNullOrEmpty() ||
            '\u0000' in ssid ||
            '\u0000' in passphrase ||
            !isWifiPassphraseShape(passphrase)
        ) {
            emptyList()
        } else {
            listOf(ExistingWifiDriverCredentials(ssid, passphrase))
        }

    /**
     * Whether a non-empty [text] has the shape of a WPA password.
     *
     * WPA defines two forms and both are legal in the field: 8–63 characters of passphrase, and 64
     * hexadecimal characters standing for the 256-bit key itself. A head unit configured out of band
     * can hold the second form, and refusing it over its length threw away a password that would
     * have worked. An empty value is the open network case and is not this function's business.
     */
    private fun isWifiPassphraseShape(text: String): Boolean =
        text.length in 8..63 || (text.length == 64 && text.all { it in HEX_DIGITS })

    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
}
