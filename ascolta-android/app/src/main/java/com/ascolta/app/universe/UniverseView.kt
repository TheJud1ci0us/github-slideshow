package com.ascolta.app.universe

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** ES 2 meshes, real depth testing and perspective. Rendering exists only while this view is visible. */
class UniverseView(context: Context, private val select: (UniverseGame.Tile) -> Unit, onReady: () -> Unit, onError: () -> Unit) : GLSurfaceView(context) {
    private val worldRenderer = UniverseRenderer(context, { post { onReady() } }, { post { onError() } })
    private var live = false
    private var animated = true
    private var previousFrame = 0L
    private var multiTouch = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if (!live) return
            if (animated && time - previousFrame >= 33_000_000) { previousFrame = time; requestRender() }
            if (animated) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            worldRenderer.camera = worldRenderer.camera.copy(radius = (worldRenderer.camera.radius / detector.scaleFactor).coerceIn(4.5f, 24f))
            worldRenderer.manualCamera = true; requestRender(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale.isInProgress || multiTouch) return true
            val old = worldRenderer.camera
            worldRenderer.camera = old.copy(yaw = old.yaw + distanceX * .006f, elevation = (old.elevation + distanceY * .004f).coerceIn(.34f, 1.34f))
            worldRenderer.manualCamera = true; requestRender(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!multiTouch) {
                val camera = worldRenderer.camera
                val ray = camera.ray(e.x,e.y,width,height)
                worldRenderer.bounds.mapNotNull { b -> b.hit(camera.eye,ray)?.let { it to b.tile } }.minByOrNull { it.first }?.second?.let(select)
                performClick()
            }
            return true
        }
    })
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser { egl, display ->
            fun config(samples: Boolean): EGLConfig? {
                val attrs = intArrayOf(EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,EGL10.EGL_DEPTH_SIZE,16,
                    EGL10.EGL_RENDERABLE_TYPE,4,0x3032,if(samples)1 else 0,0x3031,if(samples)4 else 0,EGL10.EGL_NONE)
                val count=IntArray(1);val found=arrayOfNulls<EGLConfig>(1)
                egl.eglChooseConfig(display,attrs,found,1,count)
                return if(count[0]>0)found[0] else null
            }
            config(true) ?: config(false) ?: throw IllegalStateException("OpenGL ES 2 configuration unavailable")
        }
        preserveEGLContextOnPause = true
        setRenderer(worldRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }
    fun submit(state: UniverseGame.State, selected: String, animate: Boolean) {
        worldRenderer.state = state; worldRenderer.selected = selected
        worldRenderer.animate = animate
        if(animated != animate) { animated = animate; Choreographer.getInstance().removeFrameCallback(frame); if(live && animated)Choreographer.getInstance().postFrameCallback(frame) }
        requestRender()
    }
    fun resumeWorld() {
        if(live)return
        live=true; onResume(); requestRender()
        if(animated)Choreographer.getInstance().postFrameCallback(frame)
    }
    fun pauseWorld() {
        if(!live)return
        live=false;Choreographer.getInstance().removeFrameCallback(frame);onPause()
    }
    fun resetCamera() { worldRenderer.manualCamera=false;worldRenderer.recenter=true;requestRender() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { multiTouch=false;parent?.requestDisallowInterceptTouchEvent(true) }
        if(event.pointerCount>1)multiTouch=true
        scale.onTouchEvent(event);gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onDetachedFromWindow() { pauseWorld();super.onDetachedFromWindow() }
}

internal class UniverseRenderer(private val context: Context, private val ready: () -> Unit, private val failed: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var state=UniverseGame.initial(0)
    @Volatile var selected="0,1"
    @Volatile var camera=WorldCamera()
    @Volatile var manualCamera=false
    @Volatile var recenter=false
    @Volatile var animate=true
    @Volatile var bounds: List<WorldBounds> = emptyList()
    private var aspect=1f
    private var program=0
    private var scene: WorldScene?=null
    private var geometryKey=""
    private var selectionKey=""
    private var buffers=IntArray(6)
    private var counts=IntArray(6)
    private var started=SystemClock.elapsedRealtime()
    private var lastTime=0f
    private var error=false
    private var announced=false
    private var position=0;private var color=0;private var normal=0
    private var mvp=0;private var model=0;private var eye=0;private var time=0;private var sea=0
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            fun compile(type:Int,path:String):Int {
                val shader=glCreateShader(type);glShaderSource(shader,context.assets.open(path).bufferedReader().use { it.readText() });glCompileShader(shader)
                val status=IntArray(1);glGetShaderiv(shader,GL_COMPILE_STATUS,status,0)
                check(status[0]!=0) { glGetShaderInfoLog(shader) };return shader
            }
            val vertex=compile(GL_VERTEX_SHADER,"universe/world.vert");val fragment=compile(GL_FRAGMENT_SHADER,"universe/world.frag")
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program)
            val status=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,status,0);check(status[0]!=0) { glGetProgramInfoLog(program) }
            glDeleteShader(vertex);glDeleteShader(fragment)
            position=glGetAttribLocation(program,"aPosition");color=glGetAttribLocation(program,"aColor");normal=glGetAttribLocation(program,"aNormal")
            mvp=glGetUniformLocation(program,"uMvp");model=glGetUniformLocation(program,"uModel");eye=glGetUniformLocation(program,"uEye");time=glGetUniformLocation(program,"uTime");sea=glGetUniformLocation(program,"uSea")
            glGenBuffers(6,buffers,0);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDisable(GL_CULL_FACE)
            glClearColor(.066f,.19f,.24f,1f)
            geometryKey="";selectionKey="";error=false;announced=false;started=SystemClock.elapsedRealtime()
        } catch(e:Exception) { com.ascolta.app.data.AppLog.w("Universe3D","Renderer setup failed",e);error=true;failed() }
    }
    override fun onSurfaceChanged(gl: GL10?,width:Int,height:Int) { glViewport(0,0,width,height);aspect=width.toFloat()/height.coerceAtLeast(1) }
    private fun upload(slot:Int,data:FloatArray) {
        val buffer=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data);buffer.position(0)
        glBindBuffer(GL_ARRAY_BUFFER,buffers[slot]);glBufferData(GL_ARRAY_BUFFER,data.size*4,buffer,GL_STATIC_DRAW);counts[slot]=data.size/9
    }
    override fun onDrawFrame(gl: GL10?) {
        if(error)return
        try {
            val snapshot=state
            val key="${snapshot.theme}:${snapshot.workers}:${snapshot.levels}"
            if(key!=geometryKey) {
                val next=WorldBuilder.build(snapshot);scene=next;bounds=next.bounds
                upload(0,next.water);upload(1,next.terrain);upload(2,next.props);upload(3,next.clouds);upload(4,next.worker)
                geometryKey=key;selectionKey=""
                if(!manualCamera)reset(snapshot)
            }
            if(recenter) { reset(snapshot);recenter=false }
            if(selectionKey!=selected) {
                val ring=WorldMesh();UniverseGame.tile(selected)?.let { tile -> ring.ring(worldCenter(tile).copy(y=if(tile.id in snapshot.levels).16f else -.015f),.66f,.617f,0xF5D78D) }
                upload(5,ring.data());selectionKey=selected
            }
            if(animate)lastTime=(SystemClock.elapsedRealtime()-started)/1000f
            val t=lastTime;val view=camera;val matrix=view.matrix(aspect)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);glUseProgram(program)
            glUniformMatrix4fv(mvp,1,false,matrix,0);glUniform3f(eye,view.eye.x,view.eye.y,view.eye.z);glUniform1f(time,t)
            fun draw(slot:Int,transform:FloatArray=M4.identity(),water:Boolean=false) {
                if(counts[slot]==0)return
                glUniform1f(sea,if(water)1f else 0f);glUniformMatrix4fv(model,1,false,transform,0)
                glBindBuffer(GL_ARRAY_BUFFER,buffers[slot])
                glEnableVertexAttribArray(position);glVertexAttribPointer(position,3,GL_FLOAT,false,36,0)
                glEnableVertexAttribArray(color);glVertexAttribPointer(color,3,GL_FLOAT,false,36,12)
                glEnableVertexAttribArray(normal);glVertexAttribPointer(normal,3,GL_FLOAT,false,36,24)
                glDrawArrays(GL_TRIANGLES,0,counts[slot])
            }
            draw(0,water=true);draw(1);draw(2);draw(5)
            scene?.workerHomes?.let { homes ->
                repeat(minOf(snapshot.workers,5)) { i -> if(homes.isNotEmpty()) {
                    val home=homes[i%homes.size];val phase=t*.6f+i*2.0f
                    val at=home+V3(cos(phase)*.28f,.03f+abs(sin(t*4+i))*.025f,sin(phase)*.25f)
                    draw(4,M4.model(at,-phase))
                } }
            }
            draw(3,M4.model(V3(sin(t*.08f)*.16f,0f,0f)))
            if(!announced) { check(glGetError()==GL_NO_ERROR) { "OpenGL drawing error" };announced=true;ready() }
        } catch(e:Exception) { com.ascolta.app.data.AppLog.w("Universe3D","Rendering failed",e);error=true;failed() }
    }
    private fun reset(s:UniverseGame.State) {
        val owned=s.levels.keys.mapNotNull(UniverseGame::tile).map(::worldCenter)
        val target=if(owned.isEmpty())V3(0f,0f,0f) else V3(owned.map { it.x }.average().toFloat(),0f,owned.map { it.z }.average().toFloat())
        camera=WorldCamera(radius=(7f+sqrt(s.levels.size.toFloat())*1.05f).coerceAtMost(17f),target=target)
    }
}
