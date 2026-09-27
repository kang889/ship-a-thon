package com.studentmemory.copilot.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.TextView

/** All PackBack presentation tokens. Never owns product state or decisions. */
class PackBackTheme(val context: Context, val night: Boolean = false) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = color(if (night) "#15164A" else if (dark) "#0E0F24" else "#F4F5FA")
    val card = color(if (night) "#272852" else if (dark) "#1A1B38" else "#FFFFFF")
    val ink = color(if (night || dark) "#F2F3FA" else "#12132B")
    val secondary = color(if (night || dark) "#CECEE5" else "#44475F")
    val muted = color(if (night || dark) "#ADAFCF" else "#62657D")
    val line = color(if (night || dark) "#393B66" else "#E4E6F0")
    val brand = color(if (dark) "#7C7FFF" else "#3436D6")
    val brandSoft = color(if (dark || night) "#292951" else "#ECECFD")
    val brandText = color(if (dark || night) "#B6B8FF" else "#2426A8")
    val safe = color(if (dark || night) "#6FD39E" else "#16734A")
    val safeSoft = color(if (dark || night) "#243E3F" else "#E1F2E8")
    val warning = color(if (dark) "#FFC266" else "#8F5300")
    val warningSoft = color(if (dark) "#443323" else "#FFF0D6")
    val risk = color(if (dark || night) "#FF8A6B" else "#C23C1B")
    val riskSoft = color(if (dark || night) "#40252B" else "#FDE7DF")
    val ai = color(if (dark) "#B39CFF" else "#6541C9")
    val aiSoft = color(if (dark) "#302749" else "#EFEAFD")
    val white = Color.WHITE
    val hero = color(if (dark) "#08091C" else "#3436D6")
    val nearHero = color(if (dark) "#08091C" else "#15164A")
    val shadow = color("#12132B")

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(fill: Int, radius: Int = CARD_RADIUS, border: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    fun ripple(fill: Int, radius: Int = BUTTON_RADIUS, border: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(color("#203436D6")), shape(fill, radius, border), shape(white, radius))
    fun styleText(view: TextView, size: Float = BODY, heading: Boolean = false, weight: Int = 600, textColor: Int = ink) {
        view.textSize = size
        view.typeface = font(context, heading)
        view.fontVariationSettings = "'wght' $weight"
        view.setTextColor(textColor)
        view.includeFontPadding = false
        view.setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun elevate(view: View, elevation: Int = 2) {
        view.elevation = dp(elevation).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = color("#151632")
            view.outlineSpotShadowColor = color("#151632")
        }
    }
    fun tone(name: Tone): Pair<Int, Int> = when (name) {
        Tone.Safe -> safeSoft to safe
        Tone.Warning -> warningSoft to warning
        Tone.Risk -> riskSoft to risk
        Tone.AI -> aiSoft to ai
        Tone.Brand -> brandSoft to brandText
        Tone.Grey -> background to muted
    }
    companion object {
        const val HERO = 44f
        const val H1 = 32f
        const val H2 = 24f
        const val BODY = 16f
        const val META = 13f
        const val EYEBROW = 12f
        const val CARD_RADIUS = 22
        const val ITEM_RADIUS = 20
        const val HERO_RADIUS = 26
        const val BUTTON_RADIUS = 18
        const val BUTTON_HEIGHT = 56
        private var headingFont: Typeface? = null
        private var bodyFont: Typeface? = null
        private fun font(context: Context, heading: Boolean): Typeface {
            if (heading && headingFont == null) headingFont = Typeface.createFromAsset(context.assets, "fonts/bricolage_grotesque.ttf")
            if (!heading && bodyFont == null) bodyFont = Typeface.createFromAsset(context.assets, "fonts/manrope.ttf")
            return if (heading) headingFont!! else bodyFont!!
        }
        fun color(value: String): Int = Color.parseColor(value)
    }
}
enum class Tone { Safe, Warning, Risk, AI, Brand, Grey }
