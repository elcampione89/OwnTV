package tv.own.owntv.features.panel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.settings.SettingsRepository
import java.io.File

/** App switches managed in the OwnTV Panel (App page). */
data class PanelOptions(
    /** Only "Log in with your account" is offered for adding playlists. */
    val panelOnly: Boolean = false,
    /** Panel portals' server addresses are never shown, when adding or editing. */
    val hideServerUrls: Boolean = OwnTVPanel.HIDE_SERVER_URLS,
)

/** A message from the panel, shown once per [id]. */
data class PanelAnnouncement(val id: String, val title: String, val text: String)

/**
 * OwnTV Panel mod: everything the panel pushes to the app besides the portal list.
 *
 * On every start [sync] reads `api/config/`: the switches (kept in [options], persisted so they hold
 * offline), the announcement, and the revision of the uploaded settings backup. When that revision
 * differs from the one applied last, the backup is downloaded and restored (Settings section only,
 * hardware settings included), so changing it in the panel changes every TV on its next start.
 */
object PanelConfig {
    private const val TAG = "OwnTVPanel"
    private const val PREFS = "owntv_panel"
    private const val K_PANEL_ONLY = "panel_only"
    private const val K_HIDE_URLS = "hide_server_urls"
    private const val K_PORTAL_URLS = "portal_urls"
    private const val K_SETTINGS_REV = "settings_rev"
    private const val K_ANN_SEEN = "announcement_seen"
    private const val K_UI_REV = "ui_rev"
    private const val K_KEYS_REV = "keys_rev"
    private const val BACKGROUND_MARKER = "__PANEL_BACKGROUND__"
    private const val BACKGROUND_PREFIX = "panel-background-"

    private val _options = MutableStateFlow(PanelOptions())
    val options: StateFlow<PanelOptions> = _options.asStateFlow()

    private val _announcement = MutableStateFlow<PanelAnnouncement?>(null)
    /** The announcement still to show, or null. */
    val announcement: StateFlow<PanelAnnouncement?> = _announcement.asStateFlow()

    @Volatile private var portalUrls: Set<String> = emptySet()
    @Volatile private var hideUrls: Boolean = OwnTVPanel.HIDE_SERVER_URLS

    private fun setOptions(opts: PanelOptions) {
        hideUrls = opts.hideServerUrls
        _options.value = opts
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the last known switches. Cheap; call once at app start before any UI. */
    fun load(context: Context) {
        val p = prefs(context)
        setOptions(
            PanelOptions(
                panelOnly = p.getBoolean(K_PANEL_ONLY, false),
                hideServerUrls = p.getBoolean(K_HIDE_URLS, OwnTVPanel.HIDE_SERVER_URLS),
            ),
        )
        portalUrls = p.getStringSet(K_PORTAL_URLS, emptySet()).orEmpty()
    }

    /** True when [url] belongs to a panel portal and the panel says to hide server addresses. */
    fun hidesUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !hideUrls) return false
        val u = normalise(url)
        return portalUrls.any { u.startsWith(it) }
    }

    fun dismissAnnouncement(context: Context) {
        val shown = _announcement.value ?: return
        prefs(context).edit().putString(K_ANN_SEEN, shown.id).apply()
        _announcement.value = null
    }

    /** Fetches the panel's config and applies it. Never throws; a panel that is down changes nothing. */
    suspend fun sync(
        context: Context,
        client: OkHttpClient,
        backup: BackupManager,
        settingsRepo: SettingsRepository,
    ) = withContext(Dispatchers.IO) {
        val p = prefs(context)

        // Portal addresses, so playlists made from them can be recognised (and hidden) later.
        fetchPanelPortals(client).onSuccess { list ->
            portalUrls = list.map { normalise(it.url) }.toSet()
            p.edit().putStringSet(K_PORTAL_URLS, portalUrls).apply()
        }

        val configUrl = OwnTVPanel.API_URL.toHttpUrl().resolve("config/") ?: return@withContext
        val root = runCatching {
            client.newCall(panelRequest(configUrl.toString())).execute().use { r ->
                if (!r.isSuccessful) error("config HTTP ${r.code}")
                JSONObject(r.body.string())
            }
        }.onFailure { Log.w(TAG, "config: ${it.message}") }.getOrNull() ?: return@withContext

        root.optJSONObject("options")?.let { o ->
            val opts = PanelOptions(
                panelOnly = o.optBoolean("panel_only", false),
                hideServerUrls = o.optBoolean("hide_server_urls", OwnTVPanel.HIDE_SERVER_URLS),
            )
            setOptions(opts)
            p.edit().putBoolean(K_PANEL_ONLY, opts.panelOnly).putBoolean(K_HIDE_URLS, opts.hideServerUrls).apply()
        }

        val ann = root.optJSONObject("announcement")
        if (ann != null) {
            val id = ann.optString("id")
            if (id.isNotBlank() && id != p.getString(K_ANN_SEEN, null)) {
                _announcement.value = PanelAnnouncement(id, ann.optString("title"), ann.optString("text"))
            }
        }

        val settingsApplied = applySettingsFile(context, client, backup, configUrl, root.optJSONObject("settings"))
        applyKeys(context, settingsRepo, root.optJSONObject("keys"), force = settingsApplied)
        applyLook(context, client, settingsRepo, configUrl, root.optJSONObject("ui"), force = settingsApplied)
    }

    /**
     * Metadata & subtitles: the TMDB and OpenSubtitles API keys (secrets, so set directly rather than
     * through the backup importer) and the metadata source / language. A key the panel doesn't send
     * is left as the TV has it.
     */
    private suspend fun applyKeys(context: Context, settingsRepo: SettingsRepository, keys: JSONObject?, force: Boolean) {
        val p = prefs(context)
        if (keys == null) { p.edit().remove(K_KEYS_REV).apply(); return }
        val revision = keys.optString("revision")
        if (revision.isBlank() || (!force && revision == p.getString(K_KEYS_REV, null))) return
        try {
            keys.optString("tmdb_api_key").takeIf { it.isNotBlank() }?.let { settingsRepo.setTmdbApiKey(it) }
            keys.optString("open_subtitles_api_key").takeIf { it.isNotBlank() }?.let { settingsRepo.setOpenSubtitlesApiKey(it) }
            keys.optJSONObject("settings")?.takeIf { it.length() > 0 }?.let { settingsRepo.importSettings(it, keepDeviceSettings = false) }
            p.edit().putString(K_KEYS_REV, revision).apply()
            Log.i(TAG, "applied panel keys $revision")
        } catch (t: Throwable) {
            Log.w(TAG, "keys: ${t.message}")
        }
    }

    /** The App page's settings backup. Returns true when it was (re)applied this time. */
    private suspend fun applySettingsFile(
        context: Context,
        client: OkHttpClient,
        backup: BackupManager,
        configUrl: okhttp3.HttpUrl,
        settings: JSONObject?,
    ): Boolean {
        val p = prefs(context)
        if (settings == null) return false
        val revision = settings.optString("revision")
        if (revision.isBlank() || revision == p.getString(K_SETTINGS_REV, null)) return false
        val fileUrl = configUrl.resolve(settings.optString("url", "../settings/")) ?: return false
        val password = settings.optString("password").takeIf { it.isNotBlank() }

        val file = File(context.cacheDir, "panel-settings.own")
        return try {
            client.newCall(panelRequest(fileUrl.toString())).execute().use { r ->
                if (!r.isSuccessful) error("settings HTTP ${r.code}")
                file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            }
            backup.import(
                file = file,
                sections = setOf(BackupManager.Section.SETTINGS),
                backupPassword = password,
                mode = BackupManager.ImportMode.MERGE,
                // The panel's settings are meant for every TV, decoder and engine choices included.
                deviceSettings = true,
            ).getOrThrow()
            p.edit().putString(K_SETTINGS_REV, revision).apply()
            Log.i(TAG, "applied panel settings $revision")
            true
        } catch (t: Throwable) {
            // Not recorded as applied, so the next start tries again.
            Log.w(TAG, "settings: ${t.message}")
            false
        } finally {
            file.delete()
        }
    }

    /**
     * The Look page: OwnTV setting keys and values, handed to OwnTV's own settings importer (which
     * checks every key and value). Applied after the settings file, and again whenever that file is,
     * so the panel's look always wins over it.
     */
    private suspend fun applyLook(
        context: Context,
        client: OkHttpClient,
        settingsRepo: SettingsRepository,
        configUrl: okhttp3.HttpUrl,
        ui: JSONObject?,
        force: Boolean,
    ) {
        val p = prefs(context)
        if (ui == null) { p.edit().remove(K_UI_REV).apply(); return }
        val revision = ui.optString("revision")
        if (revision.isBlank() || (!force && revision == p.getString(K_UI_REV, null))) return
        val values = ui.optJSONObject("settings") ?: JSONObject()
        try {
            val bgUrl = ui.optString("background_url").takeIf { it.isNotBlank() && it != "null" }
            if (bgUrl != null) {
                // A new name per revision, so screens keyed on the picture's path redraw it.
                context.filesDir.listFiles { f -> f.name.startsWith(BACKGROUND_PREFIX) }?.forEach { it.delete() }
                val target = File(context.filesDir, BACKGROUND_PREFIX + revision)
                val url = configUrl.resolve(bgUrl) ?: error("background url")
                client.newCall(panelRequest(url.toString())).execute().use { r ->
                    if (!r.isSuccessful) error("background HTTP ${r.code}")
                    val tmp = File(context.cacheDir, "panel-background.tmp")
                    tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                }
                values.put("bg_image_path", target.absolutePath)
            } else if (values.optString("bg_image_path") == BACKGROUND_MARKER) {
                values.remove("bg_image_path")
            }
            settingsRepo.importSettings(values, keepDeviceSettings = false)
            p.edit().putString(K_UI_REV, revision).apply()
            Log.i(TAG, "applied panel look $revision")
        } catch (t: Throwable) {
            Log.w(TAG, "look: ${t.message}")
        }
    }

    private fun panelRequest(url: String) = Request.Builder()
        .url(url)
        .apply { if (OwnTVPanel.API_KEY.isNotBlank()) header("X-OwnTV-Key", OwnTVPanel.API_KEY) }
        .build()

    private fun normalise(url: String) = url.trim().lowercase().removePrefix("http://").removePrefix("https://").trimEnd('/')
}
