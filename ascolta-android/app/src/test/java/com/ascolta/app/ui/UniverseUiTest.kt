package com.ascolta.app.ui

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.ascolta.app.universe.UniverseGame
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], qualifiers="w412dp-h915dp-xxhdpi", application=android.app.Application::class)
class UniverseUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun rich()=UniverseGame.initial(0,"ja").copy(
        levels=UniverseGame.tiles.filter {it.distance<=2}.associate {it.id to 12},
        wallet=UniverseGame.Wallet(2500.0,1400.0,3600.0),insight=8,workers=4,stars=2,lifetimeGold=20_000.0,
    )
    @Composable private fun World(state:UniverseGame.State=rich(),actions:WorldActions=WorldActions(),sheet:String?=null,onBack:()->Unit={}) {
        MaterialTheme(colorScheme=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF8CD4BE))) {
            UniverseContent(state,onBack,actions,initialSheet=sheet,worldView={ modifier ->
                // These images are rendered by Mesa EGL from production meshes and shaders.
                val bitmap=BitmapFactory.decodeFile("build/world-preview/${if(state.levels.size==2)"initial" else "developed"}.png")
                checkNotNull(bitmap){"Run tools/universe/render_preview.py after UniverseStoreTest before UI tests"}
                Image(bitmap.asImageBitmap(),null,modifier,contentScale=ContentScale.Crop)
            })
        }
    }
    private fun content(state:UniverseGame.State=rich(),actions:WorldActions=WorldActions(),sheet:String?=null,onBack:()->Unit={}) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent { World(state,actions,sheet,onBack) }
    }
    @Test fun starterCanExpandAndBackRemainsAvailable() {
        var expanded:UniverseGame.Tile?=null;var back=false
        content(UniverseGame.initial(0),WorldActions(unlock={expanded=it;true}),onBack={back=true})
        compose.onNodeWithText("Unlock land").assertIsEnabled().performClick()
        assertEquals(UniverseGame.Tile(0,1),expanded)
        compose.onNodeWithContentDescription("Back").performClick();assertTrue(back)
    }
    @Test fun upgradeAndAnimationControlsRespond() {
        var upgraded:UniverseGame.Tile?=null
        content(actions=WorldActions(upgrade={upgraded=it;true}))
        compose.onNodeWithText("Upgrade building").performClick();assertEquals(UniverseGame.Tile(0,1),upgraded)
        compose.onNodeWithContentDescription("Pause animations").performClick()
        compose.onNodeWithContentDescription("Resume animations").assertExists()
    }
    @Test fun recruitDispatchesFromWorkersSheet() {
        var recruits=0;content(actions=WorldActions(recruit={recruits++;true}),sheet="workers")
        compose.onNodeWithText("Recruit a worker").performClick();assertEquals(1,recruits)
    }
    @Test fun researchOffersOrdinaryResourcesAndOptionalInsight() {
        val choices=mutableListOf<Boolean>();content(actions=WorldActions(research={_,discount->choices+=discount;true}),sheet="research")
        compose.onAllNodes(hasText("Research",substring=false) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).onFirst().performClick()
        compose.onAllNodesWithText("Use 1 insight",substring=true).onFirst().performScrollTo().performClick()
        assertEquals(listOf(false,true),choices)
    }
    @Test fun portalAlwaysConfirmsResetAndCanCancel() {
        var travelled:UniverseGame.Theme?=null;content(actions=WorldActions(voyage={travelled=it;true}),sheet="portal")
        compose.onAllNodesWithText("France").onFirst().performScrollTo().performClick()
        assertNull(travelled)
        compose.onNodeWithText("Cancel").performClick();assertNull(travelled)
        compose.onAllNodesWithText("Italy").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Begin voyage").performClick();assertEquals(UniverseGame.Theme.ITALY,travelled)
    }
    @Test fun portalIsUnavailableBeforeMilestone() {
        content(UniverseGame.initial(0),sheet="portal")
        compose.onNode(hasText("France") and hasClickAction()).assertIsNotEnabled()
    }
    private fun shot(name:String,state:UniverseGame.State=rich(),sheet:String?=null)=captureRoboImage("build/screens/world-$name.png") { World(state,sheet=sheet) }
    @Test fun initialPhoneScreenshot()=shot("initial",UniverseGame.initial(0,"ja"))
    @Test fun developedPhoneScreenshot()=shot("developed")
    @Test @Config(qualifiers="w360dp-h780dp-xxhdpi") fun narrowPhoneScreenshot()=shot("360")
    @Test @Config(qualifiers="w360dp-h780dp-xxhdpi") fun largeFontScreenshot() {
        RuntimeEnvironment.setFontScale(1.6f);shot("large-font")
    }
    @Test @Config(qualifiers="w915dp-h412dp-land-xxhdpi") fun landscapeScreenshot()=shot("landscape")
    @Test fun researchScreenshot() { content(sheet="research");compose.mainClock.advanceTimeBy(1_000);compose.waitForIdle();compose.onNodeWithText("Forestry tools").assertIsDisplayed();compose.onNode(isDialog()).captureRoboImage("build/screens/world-research.png") }
    @Test fun portalScreenshot() { content(sheet="portal");compose.mainClock.advanceTimeBy(1_000);compose.waitForIdle();compose.onNodeWithText("Permanent production ×2").assertIsDisplayed();compose.onNode(isDialog()).captureRoboImage("build/screens/world-portal.png") }
}
