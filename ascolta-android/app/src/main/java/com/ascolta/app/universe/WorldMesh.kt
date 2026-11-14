package com.ascolta.app.universe

import kotlin.math.*

/** Flat-shaded, genuinely three-dimensional triangle meshes; nine floats per vertex. */
class WorldMesh {
    private val vertices = ArrayList<Float>()
    val size get() = vertices.size
    fun data() = vertices.toFloatArray()
    fun tri(a: V3, b: V3, c: V3, color: Int) {
        val raw = (b - a).cross(c - a)
        if(raw.dot(raw)<.0000000001f)return
        val normal = raw.normalized()
        val red = ((color shr 16) and 255) / 255f; val green = ((color shr 8) and 255) / 255f; val blue = (color and 255) / 255f
        listOf(a,b,c).forEach { p -> vertices.addAll(listOf(p.x,p.y,p.z,red,green,blue,normal.x,normal.y,normal.z)) }
    }
    fun quad(a: V3,b: V3,c: V3,d: V3,color: Int) { tri(a,b,c,color); tri(a,c,d,color) }
    fun box(p: V3, x: Float, y: Float, z: Float, color: Int) {
        val a=p.x-x/2;val b=p.x+x/2;val c=p.y;val d=p.y+y;val e=p.z-z/2;val f=p.z+z/2
        quad(V3(a,c,f),V3(b,c,f),V3(b,d,f),V3(a,d,f),color)
        quad(V3(b,c,e),V3(a,c,e),V3(a,d,e),V3(b,d,e),color)
        quad(V3(b,c,f),V3(b,c,e),V3(b,d,e),V3(b,d,f),color)
        quad(V3(a,c,e),V3(a,c,f),V3(a,d,f),V3(a,d,e),color)
        quad(V3(a,d,e),V3(a,d,f),V3(b,d,f),V3(b,d,e),color)
    }
    fun cylinder(p: V3, radius: Float, height: Float, color: Int, sides: Int = 8, topRadius: Float = radius) {
        fun at(i: Int,y: Float,r: Float): V3 { val angle=2*PI.toFloat()*i/sides + if(sides==6)PI.toFloat()/6 else 0f;return V3(p.x+cos(angle)*r,p.y+y,p.z+sin(angle)*r) }
        for(i in 0 until sides) {
            val a=at(i,0f,radius);val b=at(i+1,0f,radius);val c=at(i+1,height,topRadius);val d=at(i,height,topRadius)
            quad(a,d,c,b,color)
            if(topRadius>0)tri(V3(p.x,p.y+height,p.z),c,d,color)
        }
    }
    fun rock(p: V3, x: Float,y: Float,z: Float,color: Int) {
        val top=V3(p.x,p.y+y,p.z);val bottom=V3(p.x,p.y-.02f,p.z)
        val ring=(0..5).map { i -> val a=i*PI.toFloat()/3; V3(p.x+cos(a)*x,p.y+y*.42f,p.z+sin(a)*z) }
        for(i in 0..5) { tri(ring[i],top,ring[(i+1)%6],color);tri(ring[(i+1)%6],bottom,ring[i],color) }
    }
    fun ring(p: V3, outer: Float, inner: Float, color: Int, sides: Int = 6) {
        fun at(i:Int,r:Float) = V3(p.x+cos(i*2*PI.toFloat()/sides+PI.toFloat()/6)*r,p.y,p.z+sin(i*2*PI.toFloat()/sides+PI.toFloat()/6)*r)
        for(i in 0 until sides)quad(at(i,outer),at(i,inner),at(i+1,inner),at(i+1,outer),color)
    }
}

data class WorldScene(val terrain: FloatArray,val props: FloatArray,val water: FloatArray,val clouds: FloatArray,val worker: FloatArray,val bounds: List<WorldBounds>,val workerHomes: List<V3>)

object WorldBuilder {
    private const val SAND=0xE6CF95
    fun build(state: UniverseGame.State): WorldScene {
        val terrain=WorldMesh();val props=WorldMesh();val water=WorldMesh();val clouds=WorldMesh();val bounds=ArrayList<WorldBounds>()
        val owned=UniverseGame.tiles.filter { it.id in state.levels };val frontier=UniverseGame.frontier(state)
        water.quad(V3(-40f,-.54f,-40f),V3(-40f,-.54f,40f),V3(40f,-.54f,40f),V3(40f,-.54f,-40f),0x267C89)
        (owned+frontier).forEach { tile ->
            val p=worldCenter(tile);val unlocked=tile.id in state.levels
            if(unlocked) {
                terrain.cylinder(p.copy(y=-.48f),.60f,.55f,0x997A58,6,.65f)
                terrain.cylinder(p.copy(y=.065f),.65f,.05f,SAND,6)
                terrain.cylinder(p.copy(y=.117f),.605f,.018f,when(tile.kind) { UniverseGame.Kind.QUARRY->0xAFB9A0;UniverseGame.Kind.FARM->0x83AB58;else->0x68A77A },6)
                when(tile.kind) {
                    UniverseGame.Kind.HOME -> home(props,p,state.theme)
                    UniverseGame.Kind.FOREST -> forest(props,p,state.theme,state.level(tile))
                    UniverseGame.Kind.QUARRY -> quarry(props,p,state.level(tile))
                    UniverseGame.Kind.FARM -> farm(props,p,state.theme)
                    UniverseGame.Kind.SANCTUARY -> sanctuary(props,p,state.theme)
                }
            } else {
                terrain.cylinder(p.copy(y=-.07f),.61f,.04f,0x367985,6)
                props.ring(p.copy(y=-.025f),.58f,.562f,0x6BB5B0)
                props.rock(p.copy(y=-.018f),.075f,.18f,.075f,0x91D2C3)
            }
            bounds+=WorldBounds(tile,V3(p.x-.56f,if(unlocked)-.4f else -.08f,p.z-.65f),V3(p.x+.56f,if(unlocked)height(tile.kind) else .18f,p.z+.65f))
        }
        // Shallow rocks outside the coast and gently moving low-poly clouds.
        for(i in 0..8) {
            val a=i*2.399f;val r=3.8f+i*.16f
            clouds.rock(V3(cos(a)*r,2.3f+(i%3)*.25f,sin(a)*r),.55f,.35f,.32f,0xD8EBE4)
            clouds.rock(V3(cos(a)*r+.4f,2.3f+(i%3)*.25f,sin(a)*r),.4f,.24f,.3f,0xE4F1E8)
        }
        val worker=WorldMesh().apply {
            box(V3(0f,.16f,0f),.14f,.19f,.10f,0xF1E4BF)
            box(V3(-.045f,.06f,0f),.055f,.11f,.065f,0x294F66);box(V3(.045f,.06f,0f),.055f,.11f,.065f,0x294F66)
            box(V3(0f,.35f,0f),.125f,.115f,.12f,0xD5A478)
            cylinder(V3(0f,.455f,0f),.10f,.035f,0xF3C76E,8)
            cylinder(V3(0f,.487f,0f),.072f,.06f,0xF3C76E,8,.04f)
            box(V3(-.10f,.19f,0f),.055f,.15f,.07f,0x5BC0AB);box(V3(.10f,.19f,0f),.055f,.15f,.07f,0x5BC0AB)
        }
        return WorldScene(terrain.data(),props.data(),water.data(),clouds.data(),worker.data(),bounds,owned.filter { it.kind != UniverseGame.Kind.SANCTUARY }.map(::worldCenter))
    }
    private fun height(kind: UniverseGame.Kind) = when(kind) { UniverseGame.Kind.HOME->1.8f;UniverseGame.Kind.FOREST->1.5f;UniverseGame.Kind.SANCTUARY->1.7f;else->1.0f }
    private fun home(m: WorldMesh,p:V3,theme:UniverseGame.Theme) {
        m.cylinder(p.copy(y=.14f),.4f,.08f,0xD8D0B0,6)
        if(theme==UniverseGame.Theme.JAPAN) {
            m.box(p.copy(y=.22f),.50f,.38f,.47f,0xE8D6AC)
            roof(m,p.copy(y=.60f),.72f,.28f,.66f,0xB44E4B)
            m.box(p.copy(y=.88f),.32f,.28f,.3f,0xF1E5C5)
            roof(m,p.copy(y=1.16f),.53f,.24f,.48f,0xA94546)
            m.cylinder(p.copy(y=1.4f),.028f,.21f,0xE7C675,6)
        } else {
            m.box(p.copy(y=.22f),.5f,.48f,.46f,0xEADCB5)
            roof(m,p.copy(y=.70f),.66f,.35f,.63f,if(theme==UniverseGame.Theme.ITALY)0xB8684B else 0x526E80)
            m.box(V3(p.x+.19f,.7f,p.z-.15f),.08f,.40f,.09f,0xD5C3A1)
        }
        m.box(V3(p.x,.22f,p.z+.237f),.115f,.23f,.025f,0x755E4C)
        m.box(V3(p.x-.15f,.43f,p.z+.241f),.075f,.10f,.02f,0x60AAB0)
        m.box(V3(p.x+.15f,.43f,p.z+.241f),.075f,.10f,.02f,0x60AAB0)
    }
    private fun roof(m:WorldMesh,p:V3,x:Float,y:Float,z:Float,c:Int) {
        val a=V3(p.x-x/2,p.y,p.z-z/2);val b=V3(p.x+x/2,p.y,p.z-z/2);val d=V3(p.x-x/2,p.y,p.z+z/2);val e=V3(p.x+x/2,p.y,p.z+z/2)
        val left=V3(p.x-x*.4f,p.y+y,p.z);val right=V3(p.x+x*.4f,p.y+y,p.z)
        m.quad(a,left,right,b,c);m.quad(d,e,right,left,c);m.tri(a,d,left,c);m.tri(b,right,e,c)
    }
    private fun forest(m:WorldMesh,p:V3,theme:UniverseGame.Theme,level:Int) {
        val spots=listOf(V3(-.22f,0f,-.12f),V3(.21f,0f,-.12f),V3(0f,0f,.23f))
        spots.forEachIndexed { i,offset ->
            val t=p+offset;val h=.65f+(i%2)*.18f
            m.cylinder(t.copy(y=.14f),.045f,h*.5f,0x886447,6)
            if(theme==UniverseGame.Theme.JAPAN) {
                m.rock(t.copy(y=.35f),.27f,h,.23f,if(i==1)0xE89EA9 else 0xF0B8B8)
                m.rock(V3(t.x+.15f,.53f,t.z),.19f,.40f,.18f,0xF2C7BD)
            } else {
                m.cylinder(t.copy(y=.37f),.28f,h,if(theme==UniverseGame.Theme.BRAZIL)0x399C71 else 0x2E7B59,7,0f)
                m.cylinder(t.copy(y=.57f),.21f,h*.70f,0x519B68,7,0f)
            }
        }
        m.box(V3(p.x+.25f,.14f,p.z+.3f),.18f,.13f,.16f,0xBD996A)
        if(level>=10)m.box(V3(p.x-.27f,.14f,p.z+.3f),.17f,.18f,.16f,0xD4B77B)
    }
    private fun quarry(m:WorldMesh,p:V3,level:Int) {
        m.rock(V3(p.x-.15f,.15f,p.z-.08f),.32f,.62f,.29f,0x869D9E)
        m.rock(V3(p.x+.23f,.14f,p.z+.10f),.25f,.40f,.28f,0xB0BDB4)
        m.rock(V3(p.x-.22f,.14f,p.z+.30f),.15f,.28f,.14f,0xC6CEB8)
        m.box(V3(p.x+.2f,.14f,p.z-.30f),.28f,.25f,.12f,0x9C7853)
        if(level>=10)m.rock(V3(p.x+.05f,.25f,p.z-.16f),.11f,.65f,.10f,0x8DD6CC)
    }
    private fun farm(m:WorldMesh,p:V3,theme:UniverseGame.Theme) {
        m.box(p.copy(y=.14f),.78f,.03f,.65f,0x997756)
        for(x in -1..1)for(z in -1..1) {
            val t=V3(p.x+x*.22f,.17f,p.z+z*.19f)
            m.cylinder(t,.018f,.12f,0x578E50,5)
            m.rock(t.copy(y=.25f),.07f,.14f,.065f,when(theme) { UniverseGame.Theme.FRANCE->0xAD88BF;UniverseGame.Theme.JAPAN->0xE0BB6D;UniverseGame.Theme.ITALY->0x8DA559;UniverseGame.Theme.BRAZIL->0xE8B85F })
        }
    }
    private fun sanctuary(m:WorldMesh,p:V3,theme:UniverseGame.Theme) {
        m.cylinder(p.copy(y=.14f),.40f,.12f,0xC9D8CD,6)
        m.cylinder(p.copy(y=.26f),.28f,.10f,0xE2DDC0,6)
        if(theme==UniverseGame.Theme.JAPAN) {
            m.box(V3(p.x-.20f,.36f,p.z),.07f,.72f,.08f,0xC16353);m.box(V3(p.x+.20f,.36f,p.z),.07f,.72f,.08f,0xC16353)
            m.box(p.copy(y=.94f),.64f,.10f,.13f,0xD37960);m.box(p.copy(y=1.1f),.72f,.10f,.16f,0xAC4E48)
        } else {
            m.cylinder(p.copy(y=.36f),.15f,.48f,0xE6D6A3,8)
            m.rock(p.copy(y=.84f),.19f,.44f,.17f,0x82C9C3)
        }
    }
}
