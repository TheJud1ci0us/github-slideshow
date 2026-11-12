package com.ascolta.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.ascolta.app.data.FishGame.Species
import com.ascolta.app.data.FishGame.Tier
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A low-poly fish drawn in real 3D, straight from its Blender mesh (assets/fish/<name>.json,
 * written by tools/fish/make_fish.py): vertices tied to a four-bone spine, coloured triangles.
 * Every frame the spine is bent for the swim, the fish is turned to its heading, and its
 * triangles are lit, sorted back to front and drawn in one call. No 3D engine needed.
 */
internal class FishModel(json: JSONObject, private val species: Species) {
    private val count = json.getJSONArray("b").length()
    private val vx = FloatArray(count); private val vy = FloatArray(count); private val vz = FloatArray(count)
    private val bone = IntArray(count); private val frac = FloatArray(count)
    private val triangles: Int
    private val tri: IntArray
    private val material: IntArray
    private val keys: List<String>
    private val base: IntArray
    private val glow: BooleanArray
    private val joints = FloatArray(4)
    private val swim = json.getDouble("swim").toFloat()
    private val centerX: Float; private val centerZ: Float
    /** The model's size (its longer side, with a margin): a drawing of size s shows this much. */
    private val extent: Float
    private val alt: IntArray by lazy { IntArray(base.size) { second(it) } }
    private val gold: IntArray by lazy { IntArray(base.size) { golden(it) } }

    // Reused every frame (drawing happens on one thread).
    private val px = FloatArray(count); private val py = FloatArray(count); private val pz = FloatArray(count)
    private val order: LongArray
    private val points: FloatArray
    private val shades: IntArray
    private val paint = Paint()

    init {
        val v = json.getJSONArray("v"); val b = json.getJSONArray("b"); val f = json.getJSONArray("f")
        for (i in 0 until count) {
            vx[i] = v.getDouble(i * 3).toFloat(); vy[i] = v.getDouble(i * 3 + 1).toFloat(); vz[i] = v.getDouble(i * 3 + 2).toFloat()
            bone[i] = b.getInt(i); frac[i] = f.getDouble(i).toFloat()
        }
        val t = json.getJSONArray("t")
        triangles = t.length() / 4
        tri = IntArray(triangles * 3); material = IntArray(triangles)
        for (i in 0 until triangles) {
            tri[i * 3] = t.getInt(i * 4); tri[i * 3 + 1] = t.getInt(i * 4 + 1); tri[i * 3 + 2] = t.getInt(i * 4 + 2); material[i] = t.getInt(i * 4 + 3)
        }
        val c = json.getJSONArray("colors"); val k = json.getJSONArray("keys"); val g = json.getJSONArray("glow")
        keys = List(k.length()) { k.getString(it) }
        base = IntArray(c.length()) { 0xFF000000.toInt() or c.getString(it).removePrefix("#").toInt(16) }
        glow = BooleanArray(g.length()) { g.getInt(it) == 1 }
        val j = json.getJSONArray("joints")
        for (i in 0 until 4) joints[i] = j.getDouble(i).toFloat()
        centerX = (vx.min() + vx.max()) / 2; centerZ = (vz.min() + vz.max()) / 2
        extent = max(vx.max() - vx.min(), vz.max() - vz.min()) * 1.10f + 0.12f
        order = LongArray(triangles); points = FloatArray(triangles * 6); shades = IntArray(triangles * 6)
    }

    private fun rgb(r: Float, g: Float, b: Float) =
        0xFF000000.toInt() or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

    /** The second colouring: hues turned; greys (and the whole shark and anglerfish) tinted. */
    private fun second(i: Int): Int {
        val key = keys[i]
        if (key == "eye" || key == "pupil") return base[i]
        val (shift, tint) = SECOND.getValue(species)
        val r = (base[i] shr 16 and 255) / 255f; val g = (base[i] shr 8 and 255) / 255f; val b = (base[i] and 255) / 255f
        val hi = max(r, max(g, b)); val lo = min(r, min(g, b)); val d = hi - lo
        var h = when {
            d == 0f -> 0f
            hi == r -> ((g - b) / d + 6f) % 6f
            hi == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        var s = if (hi == 0f) 0f else d / hi
        var v = hi
        if (tint != null && key != "white" && key != "tooth" && (s < 0.45f || species == Species.SHARK || species == Species.ANGLERFISH)) {
            h = tint; s = 0.6f; v = min(1f, v * 1.35f + 0.05f)
        } else h = (h + shift) % 1f
        val x = h * 6f; val n = x.toInt() % 6; val fr = x - x.toInt()
        val p = v * (1 - s); val q = v * (1 - s * fr); val u = v * (1 - s * (1 - fr))
        return when (n) { 0 -> rgb(v, u, p); 1 -> rgb(q, v, p); 2 -> rgb(p, v, u); 3 -> rgb(p, q, v); 4 -> rgb(u, p, v); else -> rgb(v, p, q) }
    }

    /** Gold: the pattern stays, as lighter and darker gold. */
    private fun golden(i: Int): Int {
        if (keys[i] == "eye" || keys[i] == "pupil") return base[i]
        val lum = (0.2126f * (base[i] shr 16 and 255) + 0.7152f * (base[i] shr 8 and 255) + 0.0722f * (base[i] and 255)) / 255f
        val k = 0.66f + 0.5f * lum
        return rgb(1.0f * k, 0.78f * k, 0.16f * k)
    }

    /**
     * Draws the fish in a square of [size] pixels around ([cx], [cy]). [yaw] is its heading
     * (0: nose to the right, π: to the left, in between: towards or away from the viewer).
     * [fog] with [fogAmount] fades far fish into the water; [flat] draws a plain silhouette.
     */
    fun draw(
        canvas: Canvas, tier: Tier, cx: Float, cy: Float, size: Float, yaw: Float, time: Float, phase: Float,
        fog: Int = 0, fogAmount: Float = 0f, flat: Int? = null, effects: Boolean = true,
    ) {
        val colors = when (tier) { Tier.COMMON, Tier.RARE -> base; Tier.UNCOMMON, Tier.VERY_RARE -> alt; Tier.LEGENDARY -> gold }
        // The spine: each bone turns a little more than the one before, a beat later.
        var c0 = 1f; var s0 = 0f; var tx0 = 0f; var ty0 = 0f
        for (i in 0 until 4) {
            val a = swim * AMP[i] * sin(6.2832f * (time + phase) - LAG[i])
            val c = cos(a); val s = sin(a); val j = joints[i]
            val tx = j - c * j; val ty = -s * j
            val nc = c0 * c - s0 * s; val ns = s0 * c + c0 * s
            val ntx = c0 * tx - s0 * ty + tx0; val nty = s0 * tx + c0 * ty + ty0
            c0 = nc; s0 = ns; tx0 = ntx; ty0 = nty
            bc[i] = c0; bs[i] = s0; btx[i] = tx0; bty[i] = ty0
        }
        val cosY = cos(yaw); val sinY = sin(yaw)
        val scale = size / extent
        for (i in 0 until count) {
            val k = bone[i]
            var x = bc[k] * vx[i] - bs[k] * vy[i] + btx[k]
            var y = bs[k] * vx[i] + bc[k] * vy[i] + bty[k]
            val f = frac[i]
            if (f > 0f) {
                val x2 = bc[k + 1] * vx[i] - bs[k + 1] * vy[i] + btx[k + 1]
                val y2 = bs[k + 1] * vx[i] + bc[k + 1] * vy[i] + bty[k + 1]
                x += (x2 - x) * f; y += (y2 - y) * f
            }
            x -= centerX
            val z = vz[i] - centerZ
            val wx = x * cosY - y * sinY
            val wy = x * sinY + y * cosY
            // Seen slightly from above.
            px[i] = wx; py[i] = wy * TILT_COS - z * TILT_SIN; pz[i] = z * TILT_COS + wy * TILT_SIN
        }
        for (t in 0 until triangles) {
            val depth = py[tri[t * 3]] + py[tri[t * 3 + 1]] + py[tri[t * 3 + 2]]
            order[t] = ((-depth * 20_000f).toLong() shl 32) or t.toLong()
        }
        order.sort() // far triangles first
        val shine = effects && (tier == Tier.RARE || tier == Tier.LEGENDARY)
        val band = centerX + extent * (((time * 0.55f + phase) % 1.4f) / 1.4f * 1.6f - 0.8f)
        val fr = fog shr 16 and 255; val fg = fog shr 8 and 255; val fb = fog and 255
        var o = 0
        for (n in 0 until triangles) {
            val t = (order[n] and 0xFFFFFFFFL).toInt()
            val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
            var color: Int
            if (flat != null) color = flat else {
                val ux = px[b] - px[a]; val uy = py[b] - py[a]; val uz = pz[b] - pz[a]
                val wx = px[c] - px[a]; val wy = py[c] - py[a]; val wz = pz[c] - pz[a]
                var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
                // The side turned to the viewer is the one that's lit.
                val face = if (ny > 0f) -1f / len else 1f / len
                nx *= face; ny *= face; nz *= face
                val m = material[t]
                val diffuse = max(0f, nx * -0.38f + ny * -0.62f + nz * 0.68f)
                var light = if (glow[m]) 1.25f else 0.80f + 0.42f * diffuse
                var white = 0f
                if (tier == Tier.LEGENDARY && effects) {
                    val h = max(0f, nx * -0.21f + ny * -0.90f + nz * 0.38f)
                    white = h * h * h * h * h * h * 0.55f
                }
                if (shine) white = max(white, (1f - abs((vx[a] + vx[b] + vx[c]) / 3f - band) / (extent * 0.1f)).coerceAtLeast(0f) * 0.7f)
                val base = colors[m]
                var r = (base shr 16 and 255) * light; var g = (base shr 8 and 255) * light; var bl = (base and 255) * light
                r += (255f - r) * white; g += (255f - g) * white; bl += (255f - bl) * white
                if (fogAmount > 0f) { r += (fr - r) * fogAmount; g += (fg - g) * fogAmount; bl += (fb - bl) * fogAmount }
                color = 0xFF000000.toInt() or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
            }
            points[o * 2] = cx + px[a] * scale; points[o * 2 + 1] = cy - pz[a] * scale; shades[o++] = color
            points[o * 2] = cx + px[b] * scale; points[o * 2 + 1] = cy - pz[b] * scale; shades[o++] = color
            points[o * 2] = cx + px[c] * scale; points[o * 2 + 1] = cy - pz[c] * scale; shades[o++] = color
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, triangles * 6, points, 0, null, 0, shades, 0, null, 0, 0, paint)
    }

    private val bc = FloatArray(4); private val bs = FloatArray(4); private val btx = FloatArray(4); private val bty = FloatArray(4)

    companion object {
        private val AMP = floatArrayOf(-0.05f, 0.09f, 0.20f, 0.30f)
        private val LAG = floatArrayOf(0f, 0.5f, 1.2f, 1.9f)
        private const val TILT_COS = 0.9877f
        private const val TILT_SIN = 0.1564f
        /** Second colouring: hue turned by this much; greys take the tint hue (null: stay grey). */
        private val SECOND: Map<Species, Pair<Float, Float?>> = mapOf(
            Species.CLOWNFISH to (0.52f to null), Species.BLUETANG to (0.35f to null), Species.GOLDFISH to (0.90f to null),
            Species.ANGELFISH to (0.42f to 0.58f), Species.PUFFERFISH to (0.30f to null), Species.SHARK to (0f to 0.52f),
            Species.BETTA to (0.60f to null), Species.NEONTETRA to (0.30f to 0.75f), Species.BUTTERFLYFISH to (0.47f to null),
            Species.ANGLERFISH to (0f to 0f),
        )
        private val loaded = HashMap<Species, FishModel>()

        @Synchronized
        fun of(context: Context, species: Species): FishModel = loaded.getOrPut(species) {
            val text = context.assets.open("fish/${species.name.lowercase()}.json").use { it.readBytes().decodeToString() }
            FishModel(JSONObject(text), species)
        }
    }
}
