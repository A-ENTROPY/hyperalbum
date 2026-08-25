package com.smartvision.gallery.data.glass

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed persistence for [GlassConfig].
 *
 * Layout — 8 string preferences, one per sub-spec, each value is a tiny
 * CSV ("cornerRadius=32,blurRadius=16,…"). CSV is enough for ~8 scalar
 * fields per spec; keeps the data class free of a serialization framework.
 */
private val Context.glassConfigStore: DataStore<Preferences> by preferencesDataStore(
    name = "liquid_glass_config"
)

class GlassConfigRepository(private val context: Context) {

    private val store get() = context.glassConfigStore

    /** First read of the current config (suspends until DataStore loads). */
    suspend fun read(): GlassConfig = GlassConfig(
        tabBar = store.data.map { it[K_TABBAR].toTabBar() }.first(),
        staticGlass = store.data.map { it[K_STATIC].toStatic() }.first(),
        topBar = store.data.map { it[K_TOPBAR].toTopBar() }.first(),
        control = store.data.map { it[K_CONTROL].toControl() }.first(),
        toggle = store.data.map { it[K_TOGGLE].toToggle() }.first(),
        lens = store.data.map { it[K_LENS].toLens() }.first(),
        backdrop = store.data.map { it[K_BACKDROP].toBackdrop() }.first(),
        background = store.data.map { it[K_BACKGROUND].toBackground() }.first(),
        searchBar = store.data.map { it[K_SEARCHBAR].toSearchBar() }.first(),
        chipFilter = store.data.map { it[K_CHIPFILTER].toChipFilter() }.first(),
        heroFrost = store.data.map { it[K_HEROFROST].toHeroFrost() }.first(),
    )

    /** Reactive stream of the current config. */
    fun observe(): Flow<GlassConfig> = store.data.map { prefs ->
        GlassConfig(
            tabBar = prefs[K_TABBAR].toTabBar(),
            staticGlass = prefs[K_STATIC].toStatic(),
            topBar = prefs[K_TOPBAR].toTopBar(),
            control = prefs[K_CONTROL].toControl(),
            toggle = prefs[K_TOGGLE].toToggle(),
            lens = prefs[K_LENS].toLens(),
            backdrop = prefs[K_BACKDROP].toBackdrop(),
            background = prefs[K_BACKGROUND].toBackground(),
            searchBar = prefs[K_SEARCHBAR].toSearchBar(),
            chipFilter = prefs[K_CHIPFILTER].toChipFilter(),
            heroFrost = prefs[K_HEROFROST].toHeroFrost(),
        )
    }

    suspend fun saveTabBar(value: TabBarGlassConfig) {
        store.edit { it[K_TABBAR] = value.encode() }
    }

    suspend fun saveStatic(value: StaticGlassConfig) {
        store.edit { it[K_STATIC] = value.encode() }
    }

    suspend fun saveTopBar(value: TopBarGlassConfig) {
        store.edit { it[K_TOPBAR] = value.encode() }
    }

    suspend fun saveControl(value: ControlGlassConfig) {
        store.edit { it[K_CONTROL] = value.encode() }
    }

    suspend fun saveToggle(value: ToggleGlassConfig) {
        store.edit { it[K_TOGGLE] = value.encode() }
    }

    suspend fun saveLens(value: LensGlassConfig) {
        store.edit { it[K_LENS] = value.encode() }
    }

    suspend fun saveBackdrop(value: BackdropGlassConfig) {
        store.edit { it[K_BACKDROP] = value.encode() }
    }

    suspend fun saveBackground(value: BackgroundGlassConfig) {
        store.edit { it[K_BACKGROUND] = value.encode() }
    }

    suspend fun saveSearchBar(value: SearchBarGlassConfig) {
        store.edit { it[K_SEARCHBAR] = value.encode() }
    }

    suspend fun saveChipFilter(value: ChipFilterGlassConfig) {
        store.edit { it[K_CHIPFILTER] = value.encode() }
    }

    suspend fun saveHeroFrost(value: HeroFrostGlassConfig) {
        store.edit { it[K_HEROFROST] = value.encode() }
    }

    suspend fun resetToDefaults() {
        store.edit {
            it.remove(K_TABBAR)
            it.remove(K_STATIC)
            it.remove(K_TOPBAR)
            it.remove(K_CONTROL)
            it.remove(K_TOGGLE)
            it.remove(K_LENS)
            it.remove(K_BACKDROP)
            it.remove(K_BACKGROUND)
            it.remove(K_SEARCHBAR)
            it.remove(K_CHIPFILTER)
            it.remove(K_HEROFROST)
        }
    }

    private companion object {
        val K_TABBAR = stringPreferencesKey("tabbar")
        val K_STATIC = stringPreferencesKey("static")
        val K_TOPBAR = stringPreferencesKey("topbar")
        val K_CONTROL = stringPreferencesKey("control")
        val K_TOGGLE = stringPreferencesKey("toggle")
        val K_LENS = stringPreferencesKey("lens")
        val K_BACKDROP = stringPreferencesKey("backdrop")
        val K_BACKGROUND = stringPreferencesKey("background")
        val K_SEARCHBAR = stringPreferencesKey("searchbar")
        val K_CHIPFILTER = stringPreferencesKey("chipfilter")
        val K_HEROFROST = stringPreferencesKey("herofrost")
    }
}

// --- CSV codec ---------------------------------------------------------

private fun String?.toTabBar(): TabBarGlassConfig = parseCsv().let { p ->
    TabBarGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(32f),
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(1.1396973f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(3.0147204f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(32f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFEAF4FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.15f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.35f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.55f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.07801767f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.06835171f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.45f,
    )
}

private fun TabBarGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toStatic(): StaticGlassConfig = parseCsv().let { p ->
    StaticGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(18f),
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(6f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(2.9755275f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(20.072989f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF0F6FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.12f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.35f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        // 3D layered effect knobs — MUST mirror the encode() side. Without
        // these, after every slider drag the DataStore re-emits with the
        // DEFAULT values for the 4 new fields, snapping the slider thumb
        // back to its initial position and making the slider feel stuck.
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.45f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.086947605f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.08635917f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.35f,
    )
}

private fun StaticGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toLens(): LensGlassConfig = parseCsv().let { p ->
    LensGlassConfig(
        lensSize = p["lensSize"]?.toFloatOrNull()?.toDp() ?: 100.dp,
        lensRefractionHeight = p["lensRefractionHeight"]?.toFloatOrNull()?.toDp() ?: Dp(14.004309f),
        lensRefractionAmount = p["lensRefractionAmount"]?.toFloatOrNull()?.toDp() ?: Dp(16.442223f),
        // Float model (0..1). Also accept legacy "true"/"false" from old
        // DataStore entries so we don't snap to default during migration.
        lensChromaticAberration = p["lensChromaticAberration"]?.let { v ->
            when (v.lowercase()) {
                "true" -> 1.0f
                "false" -> 0.0f
                else -> v.toFloatOrNull()
            }
        } ?: 1.0f,
        stretchMax = p["stretchMax"]?.toFloatOrNull() ?: 1.5f,
        squashMax = p["squashMax"]?.toFloatOrNull() ?: 0.0f,
        iconScaleInside = p["iconScaleInside"]?.toFloatOrNull() ?: 1.0f,
        iconTintAlpha = p["iconTintAlpha"]?.toFloatOrNull() ?: 1.0f,
    )
}

private fun LensGlassConfig.encode(): String = listOf(
    "lensSize" to lensSize.value,
    "lensRefractionHeight" to lensRefractionHeight.value,
    "lensRefractionAmount" to lensRefractionAmount.value,
    "lensChromaticAberration" to lensChromaticAberration,
    "stretchMax" to stretchMax,
    "squashMax" to squashMax,
    "iconScaleInside" to iconScaleInside,
    "iconTintAlpha" to iconTintAlpha,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toBackdrop(): BackdropGlassConfig = parseCsv().let { p ->
    BackdropGlassConfig(
        lightStart = p["lightStart"]?.toLongOrNull() ?: 0xFFFFE4ECL,
        lightMid = p["lightMid"]?.toLongOrNull() ?: 0xFFE8F4FFL,
        lightEnd = p["lightEnd"]?.toLongOrNull() ?: 0xFFFFF8E8L,
        darkStart = p["darkStart"]?.toLongOrNull() ?: 0xFF2A1B2EL,
        darkMid = p["darkMid"]?.toLongOrNull() ?: 0xFF1A2B3DL,
        darkEnd = p["darkEnd"]?.toLongOrNull() ?: 0xFF2E2A1BL,
    )
}

private fun BackdropGlassConfig.encode(): String = listOf(
    "lightStart" to lightStart,
    "lightMid" to lightMid,
    "lightEnd" to lightEnd,
    "darkStart" to darkStart,
    "darkMid" to darkMid,
    "darkEnd" to darkEnd,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toTopBar(): TopBarGlassConfig = parseCsv().let { p ->
    TopBarGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(0f),
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(0f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(3.1104944f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(20.035328f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF2F7FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.12f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.15f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.25f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.05f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.07968616f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.25f,
    )
}

private fun TopBarGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toControl(): ControlGlassConfig = parseCsv().let { p ->
    ControlGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(999f),
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(4f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(2.997056f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(19.95682f),
        lensPressExtra = p["lensPressExtra"]?.toFloatOrNull()?.toDp() ?: Dp(64f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF0F4FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.08f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.3f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.40f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.101177245f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.10f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.30f,
    )
}

private fun ControlGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "lensPressExtra" to lensPressExtra.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toToggle(): ToggleGlassConfig = parseCsv().let { p ->
    ToggleGlassConfig(
        width = p["width"]?.toFloatOrNull()?.toDp() ?: Dp(51f),
        height = p["height"]?.toFloatOrNull()?.toDp() ?: Dp(31f),
        trackCornerRadius = p["trackCornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(15.5f),
        knobDiameter = p["knobDiameter"]?.toFloatOrNull()?.toDp() ?: Dp(27f),
        knobShadowBlur = p["knobShadowBlur"]?.toFloatOrNull()?.toDp() ?: Dp(3f),
        onColorArgb = p["onColorArgb"]?.toLongOrNull() ?: 0xFF0088FFL,
        offTrackAlpha = p["offTrackAlpha"]?.toFloatOrNull() ?: 0.12f,
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(5.5395455f),
    )
}

private fun ToggleGlassConfig.encode(): String = listOf(
    "width" to width.value,
    "height" to height.value,
    "trackCornerRadius" to trackCornerRadius.value,
    "knobDiameter" to knobDiameter.value,
    "knobShadowBlur" to knobShadowBlur.value,
    "onColorArgb" to onColorArgb,
    "offTrackAlpha" to offTrackAlpha,
    "blurRadius" to blurRadius.value,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toBackground(): BackgroundGlassConfig = parseCsv().let { p ->
    BackgroundGlassConfig(
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(48f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(0f),
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        lightTintArgb = p["lightTintArgb"]?.toLongOrNull() ?: 0xFFFFFFFFL,
        lightTintAlpha = p["lightTintAlpha"]?.toFloatOrNull() ?: 0.18f,
        darkTintArgb = p["darkTintArgb"]?.toLongOrNull() ?: 0xFF1A1A2EL,
        darkTintAlpha = p["darkTintAlpha"]?.toFloatOrNull() ?: 0.40f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.08f,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.20f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.08390579f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.08105948f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.25f,
    )
}

private fun BackgroundGlassConfig.encode(): String = listOf(
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "vibrancy" to vibrancy,
    "lightTintArgb" to lightTintArgb,
    "lightTintAlpha" to lightTintAlpha,
    "darkTintArgb" to darkTintArgb,
    "darkTintAlpha" to darkTintAlpha,
    "highlightAlpha" to highlightAlpha,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toSearchBar(): SearchBarGlassConfig = parseCsv().let { p ->
    SearchBarGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: 16.dp,
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(1.0542965f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(3.0453568f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(19.981937f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF0F4FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.10f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.30f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.35f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.09921453f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.10f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.30f,
    )
}

private fun SearchBarGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toChipFilter(): ChipFilterGlassConfig = parseCsv().let { p ->
    ChipFilterGlassConfig(
        cornerRadius = p["cornerRadius"]?.toFloatOrNull()?.toDp() ?: Dp(30.297167f),
        shadowElevation = p["shadowElevation"]?.toFloatOrNull()?.toDp() ?: Dp(4f),
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(3.0264966f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(20.745031f),
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF0F4FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.08f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.3f,
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.40f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.07154111f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.10f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.30f,
        springDampingRatio = p["springDampingRatio"]?.toFloatOrNull() ?: 0.2869336f,
        springStiffness = p["springStiffness"]?.toFloatOrNull() ?: 100f,
        selectedScale = p["selectedScale"]?.toFloatOrNull() ?: 1.0977943f,
        floatingElevation = p["floatingElevation"]?.toFloatOrNull()?.toDp() ?: Dp(10.013742f),
    )
}

private fun ChipFilterGlassConfig.encode(): String = listOf(
    "cornerRadius" to cornerRadius.value,
    "shadowElevation" to shadowElevation.value,
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "vibrancy" to vibrancy,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
    "springDampingRatio" to springDampingRatio,
    "springStiffness" to springStiffness,
    "selectedScale" to selectedScale,
    "floatingElevation" to floatingElevation.value,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.toHeroFrost(): HeroFrostGlassConfig = parseCsv().let { p ->
    HeroFrostGlassConfig(
        blurRadius = p["blurRadius"]?.toFloatOrNull()?.toDp() ?: Dp(20.424711f),
        lensAmount = p["lensAmount"]?.toFloatOrNull()?.toDp() ?: Dp(30.01058f),
        vibrancy = p["vibrancy"]?.toBooleanStrictOrNull() ?: true,
        tintArgb = p["tintArgb"]?.toLongOrNull() ?: 0xFFF0F4FFL,
        tintAlpha = p["tintAlpha"]?.toFloatOrNull() ?: 0.10f,
        highlightAlpha = p["highlightAlpha"]?.toFloatOrNull() ?: 0.20f,
        fadeStart = p["fadeStart"]?.toFloatOrNull() ?: 0.30f,
        fadeEnd = p["fadeEnd"]?.toFloatOrNull() ?: 0.85f,
        specularAlpha = p["specularAlpha"]?.toFloatOrNull() ?: 0.30f,
        bottomShadowAlpha = p["bottomShadowAlpha"]?.toFloatOrNull() ?: 0.11040235f,
        edgeDarkAlpha = p["edgeDarkAlpha"]?.toFloatOrNull() ?: 0.08f,
        topTintExtra = p["topTintExtra"]?.toFloatOrNull() ?: 0.25f,
    )
}

private fun HeroFrostGlassConfig.encode(): String = listOf(
    "blurRadius" to blurRadius.value,
    "lensAmount" to lensAmount.value,
    "vibrancy" to vibrancy,
    "tintArgb" to tintArgb,
    "tintAlpha" to tintAlpha,
    "highlightAlpha" to highlightAlpha,
    "fadeStart" to fadeStart,
    "fadeEnd" to fadeEnd,
    "specularAlpha" to specularAlpha,
    "bottomShadowAlpha" to bottomShadowAlpha,
    "edgeDarkAlpha" to edgeDarkAlpha,
    "topTintExtra" to topTintExtra,
).joinToString(",") { (k, v) -> "$k=$v" }

private fun String?.parseCsv(): Map<String, String> {
    if (this.isNullOrEmpty()) return emptyMap()
    return split(",").mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
    }.toMap()
}

private fun Float?.toDp(): Dp = this?.let { Dp(it) } ?: Dp(0f)
