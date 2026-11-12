package com.ascolta.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.ascolta.app.data.FishGame
import com.ascolta.app.data.FishGame.Fish
import com.ascolta.app.data.FishGame.Species
import com.ascolta.app.data.FishGame.Tier
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the Aquarium's screens to build/screens/ (no phone or emulator needed), to be looked
 * at: layout, the fish and their effects at a fixed moment.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi", application = android.app.Application::class)
class AquariumScreenshotTest {
    private val rich = FishGame.State(
        coinMs = 1_234 * FishGame.COIN_MS, freeCasts = 2, sinceRare = 6, comboMs = 21 * FishGame.MINUTE, day = 20_000, dayMs = 4 * FishGame.MINUTE,
        fish = mapOf(
            Fish(Species.CLOWNFISH, Tier.COMMON).key to 4, Fish(Species.BLUETANG, Tier.COMMON).key to 1, Fish(Species.GOLDFISH, Tier.UNCOMMON).key to 2,
            Fish(Species.ANGELFISH, Tier.RARE).key to 1, Fish(Species.BETTA, Tier.VERY_RARE).key to 1, Fish(Species.SHARK, Tier.LEGENDARY).key to 1,
            Fish(Species.PUFFERFISH, Tier.COMMON).key to 3, Fish(Species.NEONTETRA, Tier.UNCOMMON).key to 1, Fish(Species.ANGLERFISH, Tier.RARE).key to 1,
            Fish(Species.BUTTERFLYFISH, Tier.LEGENDARY).key to 1, Fish(Species.CLOWNFISH, Tier.VERY_RARE).key to 1,
        ),
        decorations = FishGame.Decoration.entries.toSet(),
    )

    @Composable
    private fun Screen(state: FishGame.State, dark: Boolean = false, sheet: String? = null, caught: FishGame.Catch? = null) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface {
                AquariumContent(
                    state, today = 20_000, welcome = 0, onBack = {}, onCast = { null }, onMerge = {}, onBuy = {}, onChoose = {},
                    animate = false, initialSheet = sheet, initialCatch = caught, inlineSheets = true,
                )
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("build/screens/$name.png") { content() }

    @Test fun tank() = shot("1-tank") { Screen(rich) }
    @Test fun tankEmptyDark() = shot("2-tank-empty-dark") { Screen(FishGame.State(coinMs = 60 * FishGame.COIN_MS), dark = true) }
    @Test fun tankDeep() = shot("3-tank-deep") { Screen(rich.copy(background = FishGame.Background.DEEP, decorations = setOf(FishGame.Decoration.SHIP, FishGame.Decoration.CHEST, FishGame.Decoration.SEAWEED))) }
    @Test fun fishing() = shot("4-fishing") { Screen(rich, sheet = "fishing") }
    @Test fun caught() = shot("5-caught") {
        Screen(rich, sheet = "fishing", caught = FishGame.Catch(rich, Fish(Species.BUTTERFLYFISH, Tier.LEGENDARY), isNew = true, guaranteed = false, free = false))
    }
    @Test fun collection() = shot("6-collection") { Screen(rich, sheet = "collection") }
    // Large text (the phone's font size setting) on a narrower phone: nothing may wrap letter by letter.
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun bigFontTank() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); shot("8-big-tank") { Screen(rich) } }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun bigFontFishing() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); shot("9-big-fishing") { Screen(rich, sheet = "fishing") } }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun bigFontCollection() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); shot("10-big-collection") { Screen(rich, sheet = "collection") } }
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun bigFontShop() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); shot("11-big-shop") { Screen(rich, sheet = "shop") } }
    @Test @Config(qualifiers = "w360dp-h300dp-xxhdpi") fun bigFontGradeButtons() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        shot("12-big-grades") {
            MaterialTheme { Surface { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) { GradeButtons {} } } }
        }
    }
    @Test fun shop() = shot("7-shop") { Screen(rich.copy(decorations = setOf(FishGame.Decoration.SEAWEED)), dark = true, sheet = "shop") }
}
