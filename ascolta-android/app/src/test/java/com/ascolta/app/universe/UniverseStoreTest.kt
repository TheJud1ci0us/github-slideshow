package com.ascolta.app.universe

import android.app.Application
import com.ascolta.app.data.FishGame
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class UniverseStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){context.getSharedPreferences("lingua_universe",0).edit().clear().commit()}
    @Test fun legacyCoinsAreMigratedOnceAndOriginalSaveIsRetained() {
        context.getSharedPreferences("aquarium",0).edit().putString("state","original").commit()
        val legacy=FishGame.State(coinMs=600_000,fish=mapOf("CLOWNFISH:COMMON" to 1))
        val first=UniverseStore(context,"ja",legacy){1_000}
        assertEquals(124.0,first.state.value.wallet.gold,0.0);assertEquals(1,first.state.value.legacyRelics)
        assertEquals(UniverseGame.Theme.JAPAN,first.state.value.theme)
        assertTrue(first.unlock(UniverseGame.Tile(0,1)))
        val second=UniverseStore(context,"fr",legacy){1_000}
        assertEquals(first.state.value.wallet,second.state.value.wallet);assertEquals(3,second.state.value.levels.size)
        assertEquals("original",context.getSharedPreferences("aquarium",0).getString("state",null))
    }
    @Test fun playbackOnlyRewardsForeignRealTimeAndFlushesTheRemainder() {
        var now=1_000L;val store=UniverseStore(context,"fr"){now}
        repeat(30){store.addListening(100,false,1)};store.flush();assertEquals(0L,store.state.value.listeningMs)
        repeat(30){store.addListening(100,true,1)};now+=3_000;store.flush()
        assertEquals(3_000L,store.state.value.listeningMs)
        assertEquals(3_000L,UniverseStore(context,"fr"){now}.state.value.listeningMs)
    }
    @Test fun reviewAndCorrectionRewardsSurviveReopening() {
        val store=UniverseStore(context,"fr"){1_000}
        assertTrue(store.review(1,true));assertFalse(store.review(1,true));assertFalse(store.review(2,false))
        assertTrue(store.rewardCatch("episode:1"));assertFalse(store.rewardCatch("episode:1"))
        val reopened=UniverseStore(context,"fr"){1_000}
        assertFalse(reopened.review(1,true));assertFalse(reopened.rewardCatch("episode:1"));assertEquals(1,reopened.state.value.insight)
    }
    @Test fun corruptedSaveIsPreservedForRecovery() {
        val prefs=context.getSharedPreferences("lingua_universe",0);prefs.edit().putString("state","broken").commit()
        val store=UniverseStore(context,"fr"){1_000}
        assertEquals(2,store.state.value.levels.size);assertEquals("broken",prefs.getString("recovery_state",null))
    }
    @Test fun roundTripPreservesProductionAndProgress() {
        val s=UniverseGame.initial(1_000).copy(research=mapOf(UniverseGame.Research.TRADE to 3),stars=3,insight=7,boostMs=10_000,reviewed=setOf(2),reviewDay=123)
        val read=UniverseStore.decode(UniverseStore.encode(s))
        assertEquals(s.wallet,read.wallet);assertEquals(s.levels,read.levels);assertEquals(s.insight,read.insight)
        assertEquals(UniverseGame.rates(s),UniverseGame.rates(read));assertEquals(s.reviewed,read.reviewed)
    }
    @Test fun writeRealRendererFixtures() {
        val folder=File("build/world-preview").apply {mkdirs()}
        val initial=UniverseGame.initial(0,"ja")
        val rich=initial.copy(levels=UniverseGame.tiles.filter {it.distance<=2}.associate {it.id to 12},workers=4,insight=8,stars=2,wallet=UniverseGame.Wallet(2500.0,1400.0,3600.0))
        listOf("initial" to initial,"developed" to rich,"france" to rich.copy(theme=UniverseGame.Theme.FRANCE)).forEach { (name,state)->
            val scene=WorldBuilder.build(state)
            val owned=state.levels.keys.mapNotNull(UniverseGame::tile).map(::worldCenter)
            val target=V3(owned.map {it.x}.average().toFloat(),0f,owned.map {it.z}.average().toFloat())
            val camera=WorldCamera(radius=7f+kotlin.math.sqrt(state.levels.size.toFloat())*1.05f,target=target)
            val data=JSONObject().put("state",UniverseStore.encode(state)).put("camera",JSONArray(camera.matrix(412f/475)))
                .put("eye",JSONArray(listOf(camera.eye.x,camera.eye.y,camera.eye.z)))
            listOf("terrain" to scene.terrain,"props" to scene.props,"water" to scene.water,"clouds" to scene.clouds,"worker" to scene.worker).forEach { (key,v)->data.put(key,JSONArray(v.toList())) }
            data.put("workerHomes",JSONArray(scene.workerHomes.map {listOf(it.x,it.y,it.z)}))
            File(folder,"$name.json").writeText(data.toString())
        }
    }
}
