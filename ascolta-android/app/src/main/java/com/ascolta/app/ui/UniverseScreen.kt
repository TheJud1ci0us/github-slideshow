package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ascolta.app.R
import com.ascolta.app.graph
import com.ascolta.app.universe.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

internal fun worldNumber(n: Double): String {
    if(!n.isFinite() || n<0)return "0"
    val suffix=listOf("","K","M","B","T","Qa","Qi","Sx","Sp","Oc","No")
    val tier=if(n<1)0 else floor(log10(n)/3).toInt().coerceAtLeast(0)
    if(tier>=suffix.size)return String.format(Locale.US,"%.1e",n)
    val value=n/1000.0.pow(tier)
    return (if(value>=100 || (tier==0 && value==floor(value)))String.format(Locale.US,"%.0f",value) else String.format(Locale.US,"%.1f",value).removeSuffix(".0"))+suffix[tier]
}
internal fun worldKind(kind: UniverseGame.Kind): Int = when(kind) {
    UniverseGame.Kind.HOME->R.string.world_home;UniverseGame.Kind.FOREST->R.string.world_forest
    UniverseGame.Kind.QUARRY->R.string.world_quarry;UniverseGame.Kind.FARM->R.string.world_farm;UniverseGame.Kind.SANCTUARY->R.string.world_sanctuary
}
internal fun worldResearch(kind: UniverseGame.Research): Int = when(kind) {
    UniverseGame.Research.FORESTRY->R.string.world_forestry;UniverseGame.Research.MASONRY->R.string.world_masonry
    UniverseGame.Research.TRADE->R.string.world_trade;UniverseGame.Research.AUTOMATION->R.string.world_automation
}
internal fun worldCountry(theme: UniverseGame.Theme) = Locale("",theme.country).getDisplayCountry(Locale.getDefault())
internal data class WorldActions(
    val unlock:(UniverseGame.Tile)->Boolean={false}, val upgrade:(UniverseGame.Tile)->Boolean={false},
    val recruit:()->Boolean={false}, val research:(UniverseGame.Research,Boolean)->Boolean={_,_->false},val voyage:(UniverseGame.Theme)->Boolean={false},
)

@Composable
fun UniverseScreen(onBack:()->Unit) {
    val graph=LocalContext.current.graph
    val store=graph.universe
    val state by store.state.collectAsState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val old=remember { state }
    val offline=remember { if(System.currentTimeMillis()-old.updatedAt>=60_000) (UniverseGame.advance(old,System.currentTimeMillis()).wallet.gold-old.wallet.gold).coerceAtLeast(0.0) else 0.0 }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while(true) { store.tick();delay(1_000) } } }
    DisposableEffect(Unit) { onDispose { store.flush() } }
    UniverseContent(state,onBack,WorldActions(store::unlock,store::upgrade,store::recruit,store::research,store::voyage),offlineGold=offline)
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun UniverseContent(
    state:UniverseGame.State,onBack:()->Unit,actions:WorldActions=WorldActions(),offlineGold:Double=0.0,
    initialSheet:String?=null,worldView:(@Composable (Modifier)->Unit)?=null,
) {
    var selected by rememberSaveable { mutableStateOf("0,1") }
    var sheet by remember { mutableStateOf(initialSheet) }
    var help by remember { mutableStateOf(false) }
    var animate by rememberSaveable { mutableStateOf(true) }
    var welcome by rememberSaveable { mutableStateOf(offlineGold>1) }
    var confirmTheme by remember { mutableStateOf<UniverseGame.Theme?>(null) }
    var nativeView by remember { mutableStateOf<UniverseView?>(null) }
    val context=LocalContext.current
    val snackbar=remember { SnackbarHostState() };val scope=rememberCoroutineScope()
    fun act(ok:Boolean) { scope.launch { snackbar.showSnackbar(context.getString(if(ok)R.string.world_done else R.string.world_not_enough)) } }
    val tile=UniverseGame.tile(selected) ?: UniverseGame.Tile(0,0)
    val rates=remember(state.levels,state.workers,state.research,state.stars,state.boostMs>0) { UniverseGame.rates(state) }
    @Composable fun hud() {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ResourceCard(stringResource(R.string.world_wood),state.wallet.wood,rates.wood,Modifier.weight(1f))
                ResourceCard(stringResource(R.string.world_stone),state.wallet.stone,rates.stone,Modifier.weight(1f))
                ResourceCard(stringResource(R.string.world_gold),state.wallet.gold,rates.gold,Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.world_insight_count,state.insight),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.world_stars_count,state.stars),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.boostMs>0)Text(stringResource(R.string.world_boost,((state.boostMs+59_999)/60_000).toInt()),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
            }
        }
    }
    @Composable fun scene(modifier:Modifier) {
        Box(modifier.background(androidx.compose.ui.graphics.Color(0xFF11313D))) {
            if(worldView!=null)worldView(Modifier.fillMaxSize())
            else UniverseViewport(state,selected,animate,{selected=it.id},{nativeView=it},{sheet="land"},Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Text(worldCountry(state.theme),style=MaterialTheme.typography.titleMedium,color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold)
                Text(stringResource(R.string.world_land_count,state.levels.size,UniverseGame.tiles.size),style=MaterialTheme.typography.labelMedium,color=androidx.compose.ui.graphics.Color(0xFFC2DED7))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(onClick={nativeView?.resetCamera()}) { Icon(Icons.Filled.CenterFocusStrong,stringResource(R.string.world_camera),tint=androidx.compose.ui.graphics.Color.White) }
                IconButton(onClick={animate=!animate}) { Icon(if(animate)Icons.Filled.Pause else Icons.Filled.PlayArrow,stringResource(if(animate)R.string.world_pause_motion else R.string.world_resume_motion),tint=androidx.compose.ui.graphics.Color.White) }
            }
            if(welcome)Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).clickable { welcome=false },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(stringResource(R.string.world_offline,worldNumber(offlineGold)),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    @Composable fun controls() {
        Column(Modifier.fillMaxWidth()) {
            Card(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
                Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    val level=state.level(tile)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(worldKind(tile.kind)),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        if(level>0)Text(stringResource(R.string.world_level,level),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    }
                    if(level>0)Text(stringResource(R.string.world_building_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(stringResource(R.string.world_expand_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val cost=if(level>0)UniverseGame.upgradeCost(state,tile) else UniverseGame.unlockCost(state,tile)
                    if(level<UniverseGame.MAX_LEVEL) {
                        Cost(cost)
                        Button(onClick={act(if(level>0)actions.upgrade(tile) else actions.unlock(tile))},enabled=state.wallet.canPay(cost) && (level>0 || tile in UniverseGame.frontier(state)),modifier=Modifier.fillMaxWidth()) {
                            Text(stringResource(if(level>0)R.string.world_upgrade else R.string.world_unlock))
                        }
                    } else Text(stringResource(R.string.world_max_level),color=MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
                val menus=listOf("land" to R.string.world_buildings,"workers" to R.string.world_workers,"research" to R.string.world_research,"portal" to R.string.world_portal)
                menus.forEachIndexed { i,pair ->
                    Column(Modifier.weight(1f).heightIn(min=56.dp).clickable { sheet=pair.first }.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(when(i){0->Icons.Filled.Landscape;1->Icons.Filled.Groups;2->Icons.Filled.Science;else->Icons.Filled.AutoAwesome},null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(stringResource(pair.second),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Scaffold(
        topBar={TopAppBar(title={Text(stringResource(R.string.world_title),maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.action_back))}},actions={IconButton(onClick={help=true}){Icon(Icons.Filled.HelpOutline,stringResource(R.string.world_help))}})},
        snackbarHost={SnackbarHost(snackbar)},
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if(maxWidth>600.dp && maxWidth>maxHeight) Row(Modifier.fillMaxSize()) {
                scene(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(310.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { hud();controls() }
            } else Column(Modifier.fillMaxSize()) { hud();Spacer(Modifier.height(8.dp));scene(Modifier.weight(1f).fillMaxWidth());controls() }
        }
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text(stringResource(R.string.world_title))},text={Text(stringResource(R.string.world_help_text))},confirmButton={TextButton(onClick={help=false}){Text(stringResource(R.string.action_close))}})
    if(sheet!=null)ModalBottomSheet(onDismissRequest={sheet=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        when(sheet) {
            "land"->LazyColumn(Modifier.fillMaxWidth().heightIn(max=550.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                item { Text(stringResource(R.string.world_buildings),Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge) }
                items((UniverseGame.tiles.filter { it.id in state.levels }+UniverseGame.frontier(state)),key={it.id}) { t ->
                    ListItem(headlineContent={Text(stringResource(worldKind(t.kind)))},supportingContent={Text(if(state.level(t)>0)stringResource(R.string.world_level,state.level(t)) else stringResource(R.string.world_unexplored))},trailingContent={if(t.id==selected)Icon(Icons.Filled.Check,null)},modifier=Modifier.clickable {selected=t.id;sheet=null})
                }
            }
            "workers"->Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.world_worker_count,state.workers,UniverseGame.MAX_WORKERS),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.world_workers_hint));Cost(UniverseGame.workerCost(state))
                Button(onClick={act(actions.recruit())},enabled=state.workers<UniverseGame.MAX_WORKERS && state.wallet.canPay(UniverseGame.workerCost(state)),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.world_recruit))}
            }
            "research"->Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.world_research),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.world_research_hint),style=MaterialTheme.typography.bodyMedium)
                UniverseGame.Research.entries.forEach { research ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val level=state.research[research]?:0;val cost=UniverseGame.researchCost(state,research);val insight=UniverseGame.insightCost(state,research)
                        Text(stringResource(worldResearch(research)),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.world_research_level,level,if(research==UniverseGame.Research.AUTOMATION)"1.6" else "2"),style=MaterialTheme.typography.bodySmall)
                        if(level<UniverseGame.MAX_RESEARCH) {
                            Cost(cost)
                            Button(onClick={act(actions.research(research,false))},enabled=state.wallet.canPay(cost),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.world_research))}
                            OutlinedButton(onClick={act(actions.research(research,true))},enabled=state.insight>=insight && state.wallet.canPay(cost*.5),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.world_use_insight,insight))}
                        } else Text(stringResource(R.string.world_max_level))
                    } }
                }
            }
            "portal"->Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.world_portal),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.world_portal_hint))
                Text(stringResource(R.string.world_multiplier,worldNumber(state.permanentMultiplier)),color=MaterialTheme.colorScheme.primary)
                val reward=UniverseGame.prestigeReward(state)
                if(reward==0)Text(stringResource(R.string.world_portal_goal)) else Text(stringResource(R.string.world_voyage_reward,reward))
                UniverseGame.Theme.entries.forEach { theme -> OutlinedButton(onClick={confirmTheme=theme},enabled=reward>0,modifier=Modifier.fillMaxWidth()){Text(worldCountry(theme))} }
                if(state.legacyGold>0 || state.legacyRelics>0)Text(stringResource(R.string.world_legacy,state.legacyGold,state.legacyRelics),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    confirmTheme?.let { theme -> AlertDialog(onDismissRequest={confirmTheme=null},title={Text(stringResource(R.string.world_voyage_title))},text={Text(stringResource(R.string.world_voyage_warning))},confirmButton={TextButton(onClick={confirmTheme=null;sheet=null;selected="0,1";act(actions.voyage(theme))}){Text(stringResource(R.string.world_travel))}},dismissButton={TextButton(onClick={confirmTheme=null}){Text(stringResource(R.string.action_cancel))}}) }
}

@Composable private fun ResourceCard(label:String,value:Double,rate:Double,modifier:Modifier) {
    Surface(modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp)) {
            Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            Text(worldNumber(value),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("+${worldNumber(rate)}/s",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun Cost(cost:UniverseGame.Wallet) {
    Text(stringResource(R.string.world_cost,worldNumber(cost.wood),worldNumber(cost.stone),worldNumber(cost.gold)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun UniverseViewport(state:UniverseGame.State,selected:String,animate:Boolean,onSelect:(UniverseGame.Tile)->Unit,onView:(UniverseView)->Unit,onBuildings:()->Unit,modifier:Modifier) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<UniverseView?>(null) };var ready by remember { mutableStateOf(false) };var failed by remember { mutableStateOf(false) }
    val currentSelect by rememberUpdatedState(onSelect)
    val description=stringResource(R.string.world_gestures)
    Box(modifier) {
        if(!failed)AndroidView(factory={context->UniverseView(context,{currentSelect(it)},{ready=true},{failed=true}).also {view=it;onView(it);it.contentDescription=description}},update={it.submit(state,selected,animate);if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))it.resumeWorld()},modifier=Modifier.fillMaxSize())
        if(!ready && !failed)CircularProgressIndicator(Modifier.align(Alignment.Center))
        if(failed)Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(stringResource(R.string.world_graphics_unavailable),color=androidx.compose.ui.graphics.Color.White)
            Button(onClick=onBuildings){Text(stringResource(R.string.world_buildings))}
        }
    }
    DisposableEffect(lifecycle,view) {
        val observer=LifecycleEventObserver { _,event -> when(event) { Lifecycle.Event.ON_RESUME->view?.resumeWorld();Lifecycle.Event.ON_PAUSE->view?.pauseWorld();else->Unit } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);view?.pauseWorld() }
    }
}
