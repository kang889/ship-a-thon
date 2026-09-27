package com.studentmemory.copilot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Rounded 24-unit, 1.8-stroke line icons; no platform-dependent emoji glyphs. */
class PackBackIcon(context: Context, private val name: String, private val tint: Int) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24, size / 24)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun line(vararg xy: Float) {
            val path = Path(); path.moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i+1]); canvas.drawPath(path, p)
        }
        fun box(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) { canvas.drawRoundRect(RectF(l,t,r,b), radius, radius, p) }
        when(name) {
            "check" -> line(5f,12f,10f,17f,19f,7f)
            "plus" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "back" -> line(14f,5f,7f,12f,14f,19f)
            "chevron" -> line(9f,5f,16f,12f,9f,19f)
            "clock" -> { canvas.drawCircle(12f,12f,9f,p); line(12f,7f,12f,12f,16f,14f) }
            "pin" -> { val path=Path(); path.moveTo(12f,22f); path.cubicTo(0f,12f,4f,3f,12f,3f); path.cubicTo(20f,3f,24f,12f,12f,22f); canvas.drawPath(path,p); canvas.drawCircle(12f,10f,2.5f,p) }
            "laptop" -> { box(4f,4f,20f,16f,1f); line(2f,20f,22f,20f); line(4f,16f,2f,20f); line(20f,16f,22f,20f) }
            "charger" -> { line(8f,2f,8f,7f); line(16f,2f,16f,7f); box(6f,7f,18f,15f,3f); line(12f,15f,12f,22f) }
            "calculator" -> { box(5f,2f,19f,22f); box(8f,5f,16f,9f,0f); for (x in listOf(8f,12f,16f)) for (y in listOf(13f,17f)) canvas.drawPoint(x,y,p) }
            "umbrella" -> { val path=Path(); path.moveTo(3f,12f); path.cubicTo(3f,0f,21f,0f,21f,12f); path.close(); canvas.drawPath(path,p); line(12f,12f,12f,20f); canvas.drawArc(RectF(8f,18f,12f,22f),0f,180f,false,p) }
            "bottle" -> { box(9f,2f,15f,6f,0f); val path=Path(); path.moveTo(9f,6f); path.lineTo(7f,10f); path.lineTo(7f,21f); path.lineTo(17f,21f); path.lineTo(17f,10f); path.lineTo(15f,6f); canvas.drawPath(path,p) }
            "calendar" -> { box(3f,5f,21f,21f); line(7f,2f,7f,8f); line(17f,2f,17f,8f); line(3f,10f,21f,10f) }
            "memory" -> { line(2f,8f,12f,2f,22f,8f,12f,14f,2f,8f); line(3f,13f,12f,19f,21f,13f); line(3f,17f,12f,23f,21f,17f) }
            "profile" -> { canvas.drawCircle(12f,7f,4f,p); canvas.drawArc(RectF(4f,13f,20f,29f),180f,180f,false,p) }
            "sun" -> { canvas.drawCircle(12f,12f,4f,p); for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,1f,12f,3f); canvas.restore() } }
            "sparkles" -> { line(12f,2f,14.5f,8.5f,21f,11f,14.5f,13.5f,12f,20f,9.5f,13.5f,3f,11f,9.5f,8.5f,12f,2f); line(20f,2f,20f,6f); line(18f,4f,22f,4f) }
            "document" -> { line(6f,2f,14f,2f,19f,7f,19f,22f,5f,22f,5f,2f); line(14f,2f,14f,7f,19f,7f); line(9f,12f,15f,12f); line(9f,16f,15f,16f) }
            "image" -> { box(2f,3f,22f,21f); canvas.drawCircle(8f,8f,2f,p); line(3f,18f,10f,11f,15f,16f,18f,13f,22f,17f) }
            "message" -> { val path=Path(); path.moveTo(4f,3f); path.lineTo(20f,3f); path.lineTo(20f,17f); path.lineTo(10f,17f); path.lineTo(4f,22f); path.close(); canvas.drawPath(path,p) }
            "edit" -> { line(4f,16f,16f,4f,20f,8f,8f,20f,3f,21f,4f,16f); line(14f,6f,18f,10f) }
            "info" -> { canvas.drawCircle(12f,12f,9f,p); line(12f,11f,12f,17f); canvas.drawPoint(12f,7f,p) }
            "upload" -> { line(12f,16f,12f,2f); line(7f,7f,12f,2f,17f,7f); line(3f,15f,3f,22f,21f,22f,21f,15f) }
            else -> { // Backpack with return arrow, used for app identity and uncategorized items.
                box(5f,7f,19f,22f,4f); canvas.drawArc(RectF(8f,1f,16f,12f),180f,180f,false,p)
                line(15f,14f,10f,14f,8f,16f,10f,18f); line(8f,16f,16f,16f)
            }
        }
        canvas.restore()
    }
    companion object {
        fun forItem(name: String): String {
            val value = name.lowercase()
            return when {
                "laptop" in value -> "laptop"
                "charg" in value || "cable" in value || "adapter" in value -> "charger"
                "calcul" in value -> "calculator"
                "umbrella" in value -> "umbrella"
                "bottle" in value -> "bottle"
                else -> "bag"
            }
        }
    }
}
