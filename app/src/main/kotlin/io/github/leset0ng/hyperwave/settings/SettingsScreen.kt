package io.github.leset0ng.hyperwave.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.leset0ng.hyperwave.AppearanceSettings
import io.github.leset0ng.hyperwave.AppearanceSettings.Style
import io.github.leset0ng.hyperwave.R
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosPageHorizontalPadding
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.scaffold.GlasmioListPage
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import kotlin.math.roundToInt

// Display order: iOS, MiOS, HyperOS — the mix sits between its two parents.
private val styleNames = listOf(
    Style.IOS to R.string.appearance_ios,
    Style.MIOS to R.string.appearance_mios,
    Style.HYPEROS to R.string.appearance_hyperos,
)

private val styleDetails = mapOf(
    Style.IOS to R.string.appearance_ios_detail,
    Style.HYPEROS to R.string.appearance_hyperos_detail,
    Style.MIOS to R.string.appearance_mios_detail,
)

/** Module settings page laid out the way Glasmio's own sample pages are. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val launcher = remember(context) { ComponentName(context, "${context.packageName}.Launcher") }
    var style by remember { mutableStateOf(AppearanceSettings.load(context)) }
    var artworkFlip by remember { mutableStateOf(AppearanceSettings.loadArtworkFlip(context)) }
    var heightPercent by rememberSaveable {
        mutableFloatStateOf(AppearanceSettings.loadHeightPercent(context).toFloat())
    }
    var hideLauncher by remember { mutableStateOf(context.isLauncherHidden(launcher)) }

    GlasmioTheme {
        val background = GlasmioTheme.colors.groupedBackground
        GlasmioBackdropHost(
            modifier = modifier.fillMaxSize(),
            sampledContent = { Box(Modifier.fillMaxSize().background(background)) },
            overlayContent = {
                GlasmioListPage(
                    modifier = Modifier.safeDrawingPadding(),
                    contentPadding = PaddingValues(
                        start = IosPageHorizontalPadding,
                        end = IosPageHorizontalPadding,
                        top = 12.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            LargeTitle()
                            Section(stringResource(R.string.appearance_title)) {
                                GlasmioSegmentedControl(
                                    items = styleNames.map { (candidate, name) ->
                                        candidate to stringResource(name)
                                    },
                                    selected = style,
                                    onSelected = { selected ->
                                        style = selected
                                        AppearanceSettings.save(context, selected)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                GlasmioGroupedTableFooter(
                                    stringResource(styleDetails.getValue(style)))
                                GlasmioGroupedTableFooter(
                                    stringResource(R.string.appearance_hint))
                            }
                            Section(stringResource(R.string.playback_title)) {
                                GlasmioGroupedList {
                                    GlasmioSwitchPreference(
                                        title = { Text(stringResource(R.string.artwork_flip_title)) },
                                        checked = artworkFlip,
                                        onCheckedChange = { enabled ->
                                            artworkFlip = enabled
                                            AppearanceSettings.saveArtworkFlip(context, enabled)
                                        },
                                        showTopSeparator = false,
                                    )
                                    GlasmioSwitchPreference(
                                        title = { Text(stringResource(R.string.hide_launcher_title)) },
                                        checked = hideLauncher,
                                        onCheckedChange = { hidden ->
                                            hideLauncher = hidden
                                            context.setLauncherHidden(launcher, hidden)
                                        },
                                    )
                                }
                                GlasmioGroupedTableFooter(
                                    stringResource(R.string.hide_launcher_hint))
                            }
                            Section(stringResource(R.string.height_title)) {
                                GlasmioCard(Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.height_percent, heightPercent.roundToInt()),
                                            style = IosTypography.headline,
                                        )
                                        GlasmioSlider(
                                            value = heightPercent,
                                            onValueChange = { heightPercent = it },
                                            valueRange = AppearanceSettings.MIN_HEIGHT_PERCENT.toFloat()..
                                                AppearanceSettings.MAX_HEIGHT_PERCENT.toFloat(),
                                            onValueChangeFinished = {
                                                val percent = heightPercent.roundToInt().coerceIn(
                                                    AppearanceSettings.MIN_HEIGHT_PERCENT,
                                                    AppearanceSettings.MAX_HEIGHT_PERCENT,
                                                )
                                                heightPercent = percent.toFloat()
                                                AppearanceSettings.saveHeightPercent(context, percent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LargeTitle() {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = IosTypography.largeTitle,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.setup_instructions),
            style = IosTypography.subheadline,
            color = GlasmioTheme.colors.secondaryContent,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = IosTypography.headline, modifier = Modifier.padding(horizontal = 4.dp))
        content()
    }
}

private fun Context.isLauncherHidden(launcher: ComponentName): Boolean =
    packageManager.getComponentEnabledSetting(launcher) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

private fun Context.setLauncherHidden(launcher: ComponentName, hidden: Boolean) {
    packageManager.setComponentEnabledSetting(
        launcher,
        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}
