package org.jscratch.runtime

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A costume or backdrop. Positions and rotation centers from the project are in
 * "costume units"; after loading, [image] holds pixels at [imageResolution]
 * pixels per unit with the rotation center converted to pixel coordinates.
 */
class Costume(
    val name: String,
    val resource: String,
    val rotationCenterX: Double,
    val rotationCenterY: Double,
    val bitmapResolution: Double,
) {
    var image: BufferedImage? = null
        set(value) {
            field = value
            silhouette = value?.let { Silhouette(it) }
        }

    /** Opaque pixels of [image], for collision and tight bounds. */
    var silhouette: Silhouette? = null
        private set
    var imageRotationCenterX: Double = rotationCenterX
    var imageRotationCenterY: Double = rotationCenterY
    var imageResolution: Double = if (bitmapResolution > 0) bitmapResolution else 1.0

    /** Reuses [other]'s silhouette so it is built once per image. */
    fun shareSilhouette(other: Costume) {
        silhouette = other.silhouette
    }

    private var radius: Double = -1.0

    /**
     * Distance in costume units from the rotation center to the farthest opaque pixel
     * corner (or image corner when nothing is opaque). No rotation moves a pixel further.
     */
    fun radius(): Double {
        if (radius >= 0) return radius
        val img = image ?: return 0.0
        var pts = silhouette?.hull ?: DoubleArray(0)
        if (pts.isEmpty()) {
            val w = img.width.toDouble()
            val h = img.height.toDouble()
            pts = doubleArrayOf(0.0, 0.0, w, 0.0, 0.0, h, w, h)
        }
        var max = 0.0
        for (i in 0 until pts.size / 2) {
            max = Math.max(max, Math.hypot(pts[i * 2] - imageRotationCenterX, pts[i * 2 + 1] - imageRotationCenterY))
        }
        radius = max / imageResolution
        return radius
    }

    val isSvg: Boolean get() = resource.endsWith(".svg", ignoreCase = true)

    /** Logical width in Scratch stage units. */
    val width: Double get() = (image?.width ?: 0) / imageResolution
    val height: Double get() = (image?.height ?: 0) / imageResolution
}

class SoundAsset(val name: String, val resource: String)

/** Loads costume images from the JAR, once per resource. */
class Assets {
    private val cache = HashMap<String, Costume>()

    fun load(costume: Costume) {
        val cached = cache[costume.resource]
        if (cached != null) {
            costume.image = cached.image
            costume.shareSilhouette(cached)
            costume.imageRotationCenterX = cached.imageRotationCenterX
            costume.imageRotationCenterY = cached.imageRotationCenterY
            costume.imageResolution = cached.imageResolution
            return
        }

        val bytes = readResource("assets/" + costume.resource)
        try {
            if (bytes == null) {
                blank(costume)
            } else if (costume.isSvg) {
                loadSvg(costume, bytes)
            } else {
                loadBitmap(costume, bytes)
            }
        } catch (e: Exception) {
            Log.warn("Could not load costume '${costume.name}' (${costume.resource}): $e")
            blank(costume)
        }
        cache[costume.resource] = costume
    }

    private fun blank(costume: Costume) {
        costume.image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        costume.imageResolution = 1.0
        costume.imageRotationCenterX = 0.0
        costume.imageRotationCenterY = 0.0
    }

    private fun loadBitmap(costume: Costume, bytes: ByteArray) {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalStateException("unreadable bitmap")
        costume.image = toArgb(img)
        costume.imageResolution = if (costume.bitmapResolution > 0) costume.bitmapResolution else 1.0
        costume.imageRotationCenterX = costume.rotationCenterX
        costume.imageRotationCenterY = costume.rotationCenterY
    }

    private fun loadSvg(costume: Costume, bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        val doc: SVGDocument =
            SVGLoader().load(ByteArrayInputStream(bytes), null, LoaderContext.createDefault())
                ?: throw IllegalStateException("unparseable svg")
        val box = svgViewBox(text, doc)
        val w = box[2]
        val h = box[3]
        if (w <= 0 || h <= 0) {
            blank(costume)
            return
        }

        val pw = Math.max(1, Math.ceil(w * SVG_SCALE).toInt())
        val ph = Math.max(1, Math.ceil(h * SVG_SCALE).toInt())
        val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        doc.render(null, g, ViewBox(0f, 0f, (w * SVG_SCALE).toFloat(), (h * SVG_SCALE).toFloat()))
        g.dispose()
        costume.image = img
        costume.imageResolution = SVG_SCALE
        // Scratch rotation centers for vector costumes are relative to the viewBox origin.
        costume.imageRotationCenterX = (costume.rotationCenterX - box[0]) * SVG_SCALE
        costume.imageRotationCenterY = (costume.rotationCenterY - box[1]) * SVG_SCALE
    }

    /** Returns [x, y, width, height] of the SVG's viewBox, falling back to width/height. */
    private fun svgViewBox(text: String, doc: SVGDocument): DoubleArray {
        val head = text.substring(0, Math.min(text.length, 4000))
        val vb =
            Regex(
                """viewBox\s*=\s*["']\s*([-\d.eE+]+)[\s,]+([-\d.eE+]+)[\s,]+([-\d.eE+]+)[\s,]+([-\d.eE+]+)\s*["']""",
            ).find(head)
        if (vb != null) {
            val v = (1..4).map { vb.groupValues[it].toDoubleOrNull() ?: 0.0 }
            return doubleArrayOf(v[0], v[1], v[2], v[3])
        }

        val size = doc.size()
        return doubleArrayOf(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
    }

    private fun toArgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_ARGB) return img
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    companion object {
        /** Vector costumes are rasterized at twice the stage resolution so scaled windows stay crisp. */
        const val SVG_SCALE = 2.0

        fun readResource(path: String): ByteArray? {
            val stream = Assets::class.java.classLoader.getResourceAsStream(path) ?: return null
            return stream.use { it.readBytes() }
        }
    }
}
