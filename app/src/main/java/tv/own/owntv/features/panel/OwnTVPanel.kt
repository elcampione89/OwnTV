package tv.own.owntv.features.panel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * OwnTV Panel mod: the Xtream Codes portals users can log in to are managed in the OwnTV Panel's
 * web UI (see the `owntv-panel` PHP package) instead of being typed in as raw server URLs.
 *
 * Point [API_URL] at your panel's `api/` folder. If you generated an API key in the panel's
 * Settings, put it in [API_KEY]; otherwise leave it blank.
 */
object OwnTVPanel {
    const val API_URL = "https://blacksheep.lol/wow/owntv-panel/api/"
    const val API_KEY = ""

    /** Hide the server URL in the add/edit forms when a portal comes from the panel. */
    const val HIDE_SERVER_URLS = true
}

/** One enabled portal as the panel's `api/` returns it. */
data class PanelPortal(
    val id: Int,
    val name: String,
    val url: String,
    val epgUrl: String,
    val userAgent: String,
)

/** Reads the panel's portal list. Network and parsing run off the main thread. */
suspend fun fetchPanelPortals(client: OkHttpClient): Result<List<PanelPortal>> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(OwnTVPanel.API_URL)
            .header("Accept", "application/json")
            .apply { if (OwnTVPanel.API_KEY.isNotBlank()) header("X-OwnTV-Key", OwnTVPanel.API_KEY) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Panel returned HTTP ${response.code}")
            val body = response.body.string()
            val array = JSONObject(body).optJSONArray("portals") ?: error("Panel reply has no portal list")
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    // The panel only manages Xtream Codes portals; ignore anything else it may list later.
                    if (o.optString("type", "xc") != "xc") continue
                    val url = o.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        PanelPortal(
                            id = o.optInt("id"),
                            name = o.optString("name").ifBlank { url },
                            url = url,
                            epgUrl = o.optString("epg_url").trim(),
                            userAgent = o.optString("user_agent").trim(),
                        ),
                    )
                }
            }
        }
    }
}
