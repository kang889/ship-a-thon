package com.studentmemory.copilot.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.*

class PackBackComponents(val context: Context, val theme: PackBackTheme = PackBackTheme(context)) {
    fun Label(value: CharSequence, size: Float = 16f, heading: Boolean = false, color: Int = theme.ink, weight: Int = 600) = TextView(context).apply {
        text = value; theme.styleText(this, size, heading, weight, color)
    }
    fun Row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun Column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun Space(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, theme.dp(height))) }
    fun Icon(name: String, color: Int = theme.brand, size: Int = 24) = PackBackIcon(context, name, color).apply { layoutParams = LinearLayout.LayoutParams(theme.dp(size), theme.dp(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun IconTile(name: String, tone: Tone = Tone.Brand, size: Int = 44): FrameLayout = FrameLayout(context).apply {
        val colors = theme.tone(tone); background = theme.shape(colors.first, 14)
        val iconSize=if(size>=80)48 else 24
        addView(Icon(name, colors.second,iconSize), FrameLayout.LayoutParams(theme.dp(iconSize),theme.dp(iconSize),Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(theme.dp(size),theme.dp(size))
    }
    fun PrimaryButton(title: String, onClick: (() -> Unit)?): Button = Button(title, theme.brand, Color.WHITE, null, onClick)
    fun SecondaryButton(title: String, onClick: (() -> Unit)?): Button = Button(title, theme.card, theme.ink, theme.line, onClick)
    fun GhostButton(title: String, onClick: (() -> Unit)?): Button = Button(title, Color.TRANSPARENT, theme.brandText, null, onClick)
    private fun Button(title: String, fill: Int, color: Int, border: Int?, onClick: (() -> Unit)?): Button = android.widget.Button(context).apply {
        text = title; isAllCaps = false; theme.styleText(this, 16f, weight = 800, textColor = color)
        background = theme.ripple(fill, PackBackTheme.BUTTON_RADIUS, border)
        stateListAnimator = null; minimumHeight = theme.dp(56); minHeight = theme.dp(56)
        setPadding(theme.dp(16),theme.dp(10),theme.dp(16),theme.dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8); bottomMargin = theme.dp(4) }
        if (onClick != null) setOnClickListener { onClick() } else { isEnabled = false; alpha = .45f }
    }
    fun Pill(text: String, tone: Tone = Tone.Brand) = Label(text.uppercase(), 10f, color = theme.tone(tone).second, weight = 800).apply {
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        background = theme.shape(theme.tone(tone).first, 99)
        setPadding(theme.dp(9),theme.dp(4),theme.dp(9),theme.dp(4)); letterSpacing = .025f
    }
    fun Card(padding: Int = 20): LinearLayout = Column().apply {
        background = theme.shape(theme.card); theme.elevate(this)
        setPadding(theme.dp(padding),theme.dp(padding),theme.dp(padding),theme.dp(padding))
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = theme.dp(12) }
    }
    fun SectionHeader(title: String, trailing: String? = null): LinearLayout = Row().apply {
        setPadding(0,theme.dp(18),0,theme.dp(10))
        addView(Label(title.uppercase(), 12f, color=theme.muted, weight=800).apply { letterSpacing = .08f }, LinearLayout.LayoutParams(0,-2,1f))
        if (trailing != null) addView(Label(trailing,13f,color=theme.muted))
    }
    fun ItemRow(name: String, status: String, reason: String, risk: Boolean, checked: Boolean, onClick: (() -> Unit)?): LinearLayout = Row().apply {
        background = theme.ripple(if (risk) theme.riskSoft else theme.card, 20, if (risk) theme.risk else null)
        setPadding(theme.dp(14),theme.dp(14),theme.dp(14),theme.dp(14)); minimumHeight = theme.dp(76)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = theme.dp(10) }
        addView(IconTile(PackBackIcon.forItem(name), if (risk) Tone.Risk else Tone.Brand))
        val copy = Column().apply { setPadding(theme.dp(12),0,theme.dp(8),0) }
        copy.addView(Label(name,16f,weight=800))
        Space(copy,5)
        if (risk || checked) copy.addView(Pill(if (risk) "High risk" else status,if (risk) Tone.Risk else Tone.Safe))
        else copy.addView(Label(status,13f,color=theme.muted))
        if (risk && reason.isNotBlank()) { Space(copy,5); copy.addView(Label(reason,12f,color=theme.risk)) }
        addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        addView(FrameLayout(context).apply {
            background = theme.shape(if (checked) theme.safe else theme.card,99,if (checked) null else theme.line)
            if (checked) addView(Icon("check",theme.card,16),FrameLayout.LayoutParams(theme.dp(16),theme.dp(16),Gravity.CENTER))
        },LinearLayout.LayoutParams(theme.dp(24),theme.dp(24)))
        if (onClick != null) setOnClickListener { onClick() }
        contentDescription = listOf(name,status,reason).filter { it.isNotBlank() }.joinToString(". ")
    }
    fun NextClassHeroCard(title: String, time: String, room: String, countdown: String?, almost: Boolean, course: String? = null): LinearLayout = Column().apply {
        background = theme.shape(if (almost) theme.nearHero else theme.hero,26)
        setPadding(theme.dp(20),theme.dp(20),theme.dp(20),theme.dp(22))
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin=theme.dp(24); bottomMargin=theme.dp(4) }
        val top = Row(); top.addView(Label("NEXT",12f,color=Color.WHITE,weight=800).apply { letterSpacing=.08f },LinearLayout.LayoutParams(0,-2,1f))
        if (countdown != null) top.addView(Label(countdown,11f,color=Color.WHITE,weight=800).apply {
            background=theme.shape(PackBackTheme.color("#33FFFFFF"),99); setPadding(theme.dp(10),theme.dp(6),theme.dp(10),theme.dp(6))
        })
        addView(top); Space(this,24)
        if (!course.isNullOrBlank()) { addView(Label(course,13f,color=PackBackTheme.color("#DDDDFF"))); Space(this,8) }
        addView(Label(title,26f,true,Color.WHITE,700)); Space(this,18)
        val meta=Row(); meta.addView(Icon("clock",Color.WHITE,16)); meta.addView(Label(time,13f,color=Color.WHITE,weight=700).apply { setPadding(theme.dp(6),0,theme.dp(14),0) })
        if (room.isNotBlank()) { meta.addView(Icon("pin",Color.WHITE,16)); meta.addView(Label(room,13f,color=Color.WHITE,weight=700).apply { setPadding(theme.dp(4),0,0,0) },LinearLayout.LayoutParams(0,-2,1f)) }
        addView(meta)
    }
    fun BringBackCard(name: String, reason: String, highRisk: Boolean, withYou: Boolean, onClick: (() -> Unit)?): LinearLayout = Row().apply {
        background=theme.shape(if(highRisk) theme.riskSoft else theme.card,22,if(highRisk) theme.risk else theme.line)
        setPadding(theme.dp(16),theme.dp(18),theme.dp(16),theme.dp(18))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=theme.dp(12) }
        addView(IconTile(PackBackIcon.forItem(name),if(highRisk) Tone.Risk else Tone.Grey,48))
        val copy=Column(); copy.setPadding(theme.dp(12),0,theme.dp(10),0)
        copy.addView(Label(name,19f,true,weight=700)); if(highRisk) { Space(copy,5); copy.addView(Pill("High risk",Tone.Risk)) }
        Space(copy,6); copy.addView(Label(if(highRisk) reason else "With you?",13f,color=theme.secondary))
        addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        addView(Button(if(withYou) "With you" else "GOT IT",if(withYou) theme.safeSoft else Color.WHITE,
            if(withYou) theme.safe else PackBackTheme.color("#15164A"),null,onClick).apply { textSize=12f; minWidth=0; minimumWidth=0 },LinearLayout.LayoutParams(theme.dp(88),theme.dp(56)))
    }
    fun Toggle(checked: Boolean, onChanged: ((Boolean) -> Unit)?): Switch = Switch(context).apply {
        isChecked=checked; minimumHeight=theme.dp(44); minimumWidth=theme.dp(44)
        thumbTintList=android.content.res.ColorStateList.valueOf(theme.white)
        trackTintList=android.content.res.ColorStateList.valueOf(if(checked) theme.brand else theme.line)
        if(onChanged != null) setOnCheckedChangeListener { _, value -> onChanged(value) } else isEnabled=false
    }
    fun BottomNav(today: (() -> Unit)?, schedule: (() -> Unit)?, add: (() -> Unit)?, memory: (() -> Unit)?, profile: (() -> Unit)? = null): LinearLayout = Row().apply {
        setPadding(theme.dp(8),theme.dp(10),theme.dp(8),theme.dp(8)); background=theme.shape(theme.card,0); theme.elevate(this,8)
        val entries=listOf(Triple("Today","sun",today),Triple("Schedule","calendar",schedule),Triple("","plus",add),Triple("Memory","memory",memory),Triple("Profile","profile",profile))
        for((index,entry) in entries.withIndex()) {
            val cell=Column().apply { gravity=Gravity.CENTER; minimumHeight=theme.dp(56) }
            val selected=index==0
            if(index==2) {
                val tile=FrameLayout(context).apply { background=theme.ripple(theme.brand,18); theme.elevate(this,5) }
                tile.addView(Icon("plus",Color.WHITE,28),FrameLayout.LayoutParams(theme.dp(28),theme.dp(28),Gravity.CENTER))
                cell.addView(tile,LinearLayout.LayoutParams(theme.dp(56),theme.dp(56)))
            } else {
                cell.addView(Icon(entry.second,if(selected) theme.brand else theme.muted,22)); Space(cell,5)
                cell.addView(Label(entry.first,10f,color=if(selected) theme.brand else theme.muted,weight=if(selected)800 else 600))
            }
            cell.contentDescription=if(index==2) "Add event" else entry.first
            if(entry.third != null) cell.setOnClickListener { entry.third?.invoke() } else { cell.isEnabled=false; if(!selected)cell.alpha=.45f }
            addView(cell,LinearLayout.LayoutParams(0,-2,1f))
        }
        // TODO(PDF p26): no Profile route exists; do not invent navigation or settings handlers.
    }
    fun AppLogo(size: Int = 40): FrameLayout = FrameLayout(context).apply {
        background=theme.shape(theme.brand,12)
        addView(Icon("bag",Color.WHITE,26),FrameLayout.LayoutParams(theme.dp(26),theme.dp(26),Gravity.CENTER))
        addView(View(context).apply { background=theme.shape(theme.risk,99) },FrameLayout.LayoutParams(theme.dp(6),theme.dp(6),Gravity.TOP or Gravity.END).apply { topMargin=theme.dp(5); rightMargin=theme.dp(5) })
        layoutParams=LinearLayout.LayoutParams(theme.dp(size),theme.dp(size))
    }
    fun Wordmark(): TextView {
        val word=SpannableString("PackBack"); word.setSpan(ForegroundColorSpan(theme.brand),4,8,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return Label(word,24f,true,weight=800)
    }
}
