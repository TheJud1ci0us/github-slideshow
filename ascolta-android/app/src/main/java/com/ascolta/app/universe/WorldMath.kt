package com.ascolta.app.universe

import kotlin.math.*

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(b: V3) = V3(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: V3) = V3(x - b.x, y - b.y, z - b.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(b: V3) = x * b.x + y * b.y + z * b.z
    fun cross(b: V3) = V3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun normalized(): V3 { val n = sqrt(dot(this)).coerceAtLeast(0.00001f); return this * (1f / n) }
}
object M4 {
    fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16).also { out ->
        for (c in 0..3) for (r in 0..3) for (k in 0..3) out[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k]
    }
    fun model(position: V3, yaw: Float = 0f): FloatArray {
        val c = cos(yaw); val s = sin(yaw)
        return floatArrayOf(c,0f,-s,0f, 0f,1f,0f,0f, s,0f,c,0f, position.x,position.y,position.z,1f)
    }
    fun perspective(aspect: Float, fov: Float = 43f): FloatArray {
        val f = 1f / tan(fov * PI.toFloat() / 360f); val near = .1f; val far = 80f
        return floatArrayOf(f / aspect.coerceAtLeast(.1f),0f,0f,0f, 0f,f,0f,0f, 0f,0f,(far+near)/(near-far),-1f, 0f,0f,2*far*near/(near-far),0f)
    }
    fun view(eye: V3, target: V3): FloatArray {
        val f = (target - eye).normalized(); val r = f.cross(V3(0f,1f,0f)).normalized(); val u = r.cross(f)
        return floatArrayOf(r.x,u.x,-f.x,0f, r.y,u.y,-f.y,0f, r.z,u.z,-f.z,0f, -r.dot(eye),-u.dot(eye),f.dot(eye),1f)
    }
}
data class WorldCamera(val yaw: Float = .65f, val elevation: Float = .86f, val radius: Float = 8.8f, val target: V3 = V3(.3f,0f,.1f)) {
    val eye: V3 get() = target + V3(sin(yaw) * cos(elevation), sin(elevation), cos(yaw) * cos(elevation)) * radius
    fun matrix(aspect: Float) = M4.multiply(M4.perspective(aspect), M4.view(eye, target))
    fun ray(x: Float, y: Float, width: Int, height: Int): V3 {
        val f = (target - eye).normalized(); val r = f.cross(V3(0f,1f,0f)).normalized(); val u = r.cross(f)
        val scale = tan(43f * PI.toFloat() / 360f)
        return (f + r * ((2*x/width.coerceAtLeast(1)-1) * width.coerceAtLeast(1).toFloat()/height.coerceAtLeast(1) * scale) + u * ((1-2*y/height.coerceAtLeast(1))*scale)).normalized()
    }
}
data class WorldBounds(val tile: UniverseGame.Tile, val low: V3, val high: V3) {
    fun hit(origin: V3, direction: V3): Float? {
        var near = 0f; var far = Float.POSITIVE_INFINITY
        val o = floatArrayOf(origin.x,origin.y,origin.z); val d = floatArrayOf(direction.x,direction.y,direction.z)
        val lo = floatArrayOf(low.x,low.y,low.z); val hi = floatArrayOf(high.x,high.y,high.z)
        for (i in 0..2) {
            if (abs(d[i]) < .000001f) { if (o[i] < lo[i] || o[i] > hi[i]) return null }
            else {
                val a = (lo[i]-o[i])/d[i]; val b = (hi[i]-o[i])/d[i]
                near = max(near,min(a,b)); far = min(far,max(a,b)); if (far < near) return null
            }
        }
        return near.takeIf { far >= 0 }
    }
}
fun worldCenter(tile: UniverseGame.Tile) = V3(sqrt(3f) * .66f * (tile.q + tile.r / 2f), .12f, .99f * tile.r)
