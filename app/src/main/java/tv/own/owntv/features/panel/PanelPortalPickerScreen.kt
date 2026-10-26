package tv.own.owntv.features.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingHelp
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.ui.components.OwnTVIcon

private sealed interface PortalsUi {
    data object Loading : PortalsUi
    data class Loaded(val portals: List<PanelPortal>) : PortalsUi
    data class Failed(val message: String) : PortalsUi
}

/**
 * Panel login, step 1: the portals enabled in the OwnTV Panel, one row each. Picking one hands it to
 * the Xtream form ([tv.own.owntv.features.setup.AddSourceScreen] with `panelPortal`), where the user
 * only types a username and password.
 */
@Composable
fun PanelPortalPickerScreen(
    parent: String,
    onPick: (PanelPortal) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val client: OkHttpClient = koinInject()
    var attempt by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<PortalsUi>(PortalsUi.Loading) }
    val rowsFocus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        ui = PortalsUi.Loading
        ui = fetchPanelPortals(client).fold(
            onSuccess = { PortalsUi.Loaded(it) },
            onFailure = { PortalsUi.Failed(it.message ?: it.javaClass.simpleName) },
        )
        runCatching { rowsFocus.requestFocus() }
    }

    val title = stringResource(R.string.panel_choose_service)
    val helpText = stringResource(R.string.panel_login_help)
    val hints = listOf(
        stringResource(R.string.common_ok) to stringResource(R.string.settings_key_open),
        stringResource(R.string.common_back) to parent,
    )

    StageFullPage(
        parents = listOf(parent),
        title = title,
        count = (ui as? PortalsUi.Loaded)?.portals?.size?.toString().orEmpty(),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
        settingsRoot = false,
    ) {
        when (val s = ui) {
            PortalsUi.Loading -> StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = stringResource(R.string.panel_loading),
                desc = null,
                value = null,
                onClick = {},
                help = SettingHelp(title, helpText, hints = hints),
            )
            is PortalsUi.Failed -> {
                val retry = stringResource(R.string.panel_retry)
                StageSettingRow(
                    icon = OwnTVIcon.REFRESH,
                    title = retry,
                    desc = stringResource(R.string.panel_load_failed) + " — " + s.message,
                    value = SettingValue.Action(retry),
                    onClick = { attempt++ },
                    help = SettingHelp(retry, s.message, hints = hints),
                )
            }
            is PortalsUi.Loaded -> if (s.portals.isEmpty()) {
                val retry = stringResource(R.string.panel_retry)
                StageSettingRow(
                    icon = OwnTVIcon.REFRESH,
                    title = stringResource(R.string.panel_no_services),
                    desc = null,
                    value = SettingValue.Action(retry),
                    onClick = { attempt++ },
                    help = SettingHelp(title, helpText, hints = hints),
                )
            } else {
                s.portals.forEach { portal ->
                    StageSettingRow(
                        icon = OwnTVIcon.LIVE_TV,
                        title = portal.name,
                        desc = stringResource(R.string.panel_service_line, portal.name),
                        value = SettingValue.Opens(null),
                        onClick = { onPick(portal) },
                        help = SettingHelp(portal.name, helpText, hints = hints),
                    )
                }
            }
        }
    }
}
