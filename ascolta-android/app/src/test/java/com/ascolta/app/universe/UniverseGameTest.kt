package com.ascolta.app.universe

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

class UniverseGameTest {
    private val origin=UniverseGame.Tile(0,0)
    private val quarry=UniverseGame.Tile(0,1)
    private val forest=UniverseGame.Tile(1,0)
    private fun rich()=UniverseGame.initial(0).copy(wallet=UniverseGame.Wallet(1e12,1e12,1e12))
    @Test fun initialWorldCanUnlockItsFirstQuarryImmediately() {
        val s=UniverseGame.initial(0)
        val next=UniverseGame.unlock(s,quarry,0)!!
        assertEquals(3,next.levels.size);assertEquals(1,next.level(quarry));assertTrue(UniverseGame.rates(next).stone>0)
    }
    @Test fun offlineResourcesSettleExactlyOnce() {
        val s=UniverseGame.initial(0);val next=UniverseGame.advance(s,10_000)
        assertEquals(s.wallet.wood+17,next.wallet.wood,.0001)
        assertEquals(s.wallet.gold+7,next.wallet.gold,.0001)
        assertEquals(next,UniverseGame.advance(next,10_000))
    }
    @Test fun offlineEarningsStopAtEightHours() {
        val s=UniverseGame.initial(0)
        assertEquals(UniverseGame.advance(s,UniverseGame.OFFLINE_CAP_MS).wallet,UniverseGame.advance(s,UniverseGame.OFFLINE_CAP_MS*10).wallet)
    }
    @Test fun clockGoingBackDoesNotAwardOrRemoveResources() {
        val s=UniverseGame.initial(100_000)
        assertSame(s,UniverseGame.advance(s,99_000))
    }
    @Test fun boostAppliesOnlyToItsActualDuration() {
        val s=UniverseGame.initial(0).copy(boostMs=5_000)
        val next=UniverseGame.advance(s,10_000)
        assertEquals(s.wallet.gold+.7*20,next.wallet.gold,.0001);assertEquals(0L,next.boostMs)
    }
    @Test fun onlyAdjacentLandCanBeUnlockedAndNeverTwice() {
        val s=rich();assertNull(UniverseGame.unlock(s,UniverseGame.Tile(4,0),0))
        val next=UniverseGame.unlock(s,quarry,0)!!
        assertNull(UniverseGame.unlock(next,quarry,0));assertNull(UniverseGame.unlock(next,origin,0))
    }
    @Test fun mapIsBoundedAndFrontierNeverIncludesOwnedTiles() {
        assertEquals(61,UniverseGame.tiles.size)
        val s=rich().copy(levels=UniverseGame.tiles.associate { it.id to 1 })
        assertTrue(UniverseGame.frontier(s).isEmpty())
    }
    @Test fun cannotSpendMoreThanTheWallet() {
        val s=UniverseGame.initial(0).copy(wallet=UniverseGame.Wallet())
        assertNull(UniverseGame.unlock(s,quarry,0));assertNull(UniverseGame.upgrade(s,forest,0));assertNull(UniverseGame.recruit(s,0))
    }
    @Test fun upgradesIncreaseOutputAndCost() {
        val s=rich();val cost=UniverseGame.upgradeCost(s,forest)
        val next=UniverseGame.upgrade(s,forest,0)!!
        assertTrue(UniverseGame.rates(next).wood>UniverseGame.rates(s).wood)
        assertTrue(UniverseGame.upgradeCost(next,forest).wood>cost.wood)
        assertEquals(s.wallet.gold-cost.gold,next.wallet.gold,.0001)
    }
    @Test fun tenthLevelHasAMilestoneMultiplierAndUpgradeCapWorks() {
        val s=rich().copy(levels=mapOf(origin.id to 1,forest.id to 9))
        val next=UniverseGame.upgrade(s,forest,0)!!
        assertTrue(UniverseGame.rates(next).wood>UniverseGame.rates(s).wood*2)
        assertNull(UniverseGame.upgrade(s.copy(levels=mapOf(forest.id to UniverseGame.MAX_LEVEL)),forest,0))
    }
    @Test fun workersIncreaseProductionAndHaveACap() {
        val s=rich();val next=UniverseGame.recruit(s,0)!!
        assertEquals(UniverseGame.rates(s).wood*1.15,UniverseGame.rates(next).wood,.0001)
        assertNull(UniverseGame.recruit(s.copy(workers=UniverseGame.MAX_WORKERS),0))
    }
    @Test fun researchWorksWithoutAnyLearningCurrency() {
        val next=UniverseGame.research(rich(),UniverseGame.Research.FORESTRY,false,0)!!
        assertEquals(0,next.insight);assertEquals(1,next.research[UniverseGame.Research.FORESTRY])
        assertEquals(UniverseGame.rates(rich()).wood*2,UniverseGame.rates(next).wood,.0001)
    }
    @Test fun insightDiscountHalvesAllResourceCosts() {
        val s=rich().copy(insight=2);val cost=UniverseGame.researchCost(s,UniverseGame.Research.MASONRY)
        val next=UniverseGame.research(s,UniverseGame.Research.MASONRY,true,0)!!
        assertEquals(s.wallet.gold-cost.gold*.5,next.wallet.gold,.0001);assertEquals(1,next.insight)
        assertNull(UniverseGame.research(s.copy(insight=0),UniverseGame.Research.MASONRY,true,0))
    }
    @Test fun onlySuccessfulReviewsEarnInsightAndOnlyOncePerCardPerDay() {
        val s=UniverseGame.initial(0)
        assertNull(UniverseGame.review(s,1,false,10,0))
        val next=UniverseGame.review(s,1,true,10,0)!!
        assertEquals(1,next.insight);assertNull(UniverseGame.review(next,1,true,10,0))
        assertEquals(2,UniverseGame.review(next,1,true,11,0)!!.insight)
    }
    @Test fun reviewRewardsHaveADailyCap() {
        var s=UniverseGame.initial(0)
        (1L..40L).forEach { s=UniverseGame.review(s,it,true,10,0)!! }
        assertNull(UniverseGame.review(s,41,true,10,0));assertEquals(40,s.insight)
    }
    @Test fun listeningAwardsInsightEveryFiveRealMinutesAndCapsEnergy() {
        var s=UniverseGame.initial(0)
        repeat(5){s=UniverseGame.listen(s,60_000,it*60_000L,1)}
        assertEquals(1,s.insight);assertEquals(300_000L,s.listeningMs)
        repeat(100){s=UniverseGame.listen(s,60_000,s.updatedAt,1)}
        assertEquals(UniverseGame.BOOST_CAP_MS,s.boostMs)
    }
    @Test fun seekingToTheEndCannotEarnAnEpisodeReward() {
        assertNull(UniverseGame.finishEpisode(UniverseGame.initial(0),1,1_800_000,0))
        var s=UniverseGame.initial(0)
        repeat(10){s=UniverseGame.listen(s,60_000,it*60_000L,1)}
        val rewarded=UniverseGame.finishEpisode(s,1,1_800_000,s.updatedAt)!!
        assertEquals(s.insight+2,rewarded.insight)
        assertNull(UniverseGame.finishEpisode(rewarded,1,1_800_000,s.updatedAt))
        assertNull(UniverseGame.finishEpisode(rewarded,2,1_800_000,s.updatedAt))
    }
    @Test fun portalRequiresBothLandAndProduction() {
        val s=rich().copy(lifetimeGold=1e6)
        assertEquals(0,UniverseGame.prestigeReward(s))
        assertEquals(0,UniverseGame.prestigeReward(s.copy(levels=UniverseGame.tiles.take(12).associate { it.id to 1 },lifetimeGold=9_999.0)))
    }
    @Test fun voyageResetsTheWorldButKeepsStudyRewardsAndPermanentStars() {
        val s=rich().copy(levels=UniverseGame.tiles.take(12).associate { it.id to 10 },lifetimeGold=40_000.0,insight=5,stars=2,reviewDay=10,reviewed=setOf(1),rewardedEpisodes=listOf(8),legacyRelics=3)
        val next=UniverseGame.voyage(s,UniverseGame.Theme.JAPAN,0)!!
        assertEquals(4,next.stars);assertEquals(5,next.insight);assertEquals(2,next.levels.size)
        assertEquals(setOf(1L),next.reviewed);assertEquals(listOf(8L),next.rewardedEpisodes);assertEquals(3,next.legacyRelics)
        assertEquals(UniverseGame.Theme.JAPAN,next.theme);assertTrue(next.research.isEmpty());assertEquals(1,next.workers)
        assertTrue(UniverseGame.rates(next).gold>UniverseGame.rates(UniverseGame.initial(0)).gold)
    }
    @Test fun worldRemainsPlayableWithoutListeningOrReviews() {
        var s=UniverseGame.initial(0)
        repeat(480){ minute ->
            s=UniverseGame.advance(s,(minute+1)*60_000L)
            UniverseGame.frontier(s).sortedBy { if(it.kind==UniverseGame.Kind.QUARRY)0 else if(it.kind==UniverseGame.Kind.FARM)1 else 2 }.forEach { t -> UniverseGame.unlock(s,t,s.updatedAt)?.let { s=it } }
        }
        assertTrue(s.levels.size>=12);assertEquals(0L,s.listeningMs)
    }
    @Test fun geometryHasFiniteUnitNormalsAndStaysSmall() {
        val s=rich().copy(levels=UniverseGame.tiles.associate { it.id to 80 })
        val scene=WorldBuilder.build(s)
        val data=listOf(scene.terrain,scene.props,scene.worker,scene.water,scene.clouds)
        data.forEach { mesh ->
            assertEquals(0,mesh.size%27);assertTrue(mesh.all { it.isFinite() })
            for(i in mesh.indices step 9) { val n=mesh[i+6]*mesh[i+6]+mesh[i+7]*mesh[i+7]+mesh[i+8]*mesh[i+8];assertEquals(1f,n,.002f) }
        }
        assertTrue(data.sumOf { it.size }<600_000)
    }
    @Test fun perspectiveCameraAndRayPickingAgree() {
        val tile=quarry;val target=worldCenter(tile).copy(y=.15f)
        val camera=WorldCamera(target=target)
        val ray=camera.ray(206f,250f,412,500)
        assertEquals(1f,ray.dot((target-camera.eye).normalized()),.0001f)
        val scene=WorldBuilder.build(UniverseGame.initial(0))
        val hit=scene.bounds.mapNotNull { b->b.hit(camera.eye,ray)?.let { it to b.tile } }.minByOrNull { it.first }!!.second
        assertEquals(tile,hit)
        assertTrue(camera.matrix(.824f).all { it.isFinite() })
    }
}
