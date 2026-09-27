package com.studentmemory.copilot.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/** Presentation adapter retaining the existing AlertDialog callback and dismissal contract. */
object PackBackDialog {
    const val BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE
    const val BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE
    const val BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL
    enum class Layout { Sheet, Schedule, Event, Import, Memory, Pro }
    class Builder(private val context: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var custom: View? = null
        private var items: Array<out CharSequence>? = null
        private var itemClick: DialogInterface.OnClickListener? = null
        private var cards: List<String>? = null
        private var layout = Layout.Sheet
        private val buttons = linkedMapOf<Int, Pair<String, DialogInterface.OnClickListener?>>()
        fun setTitle(value: CharSequence?) = apply { title=value }
        fun setMessage(value: CharSequence?) = apply { message=value }
        fun setView(value: View) = apply { custom=value }
        fun setItems(value: Array<out CharSequence>, listener: DialogInterface.OnClickListener?) = apply { items=value; itemClick=listener }
        fun setPositiveButton(value: String, listener: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_POSITIVE]=value to listener }
        fun setNegativeButton(value: String, listener: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_NEGATIVE]=value to listener }
        fun setNeutralButton(value: String, listener: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_NEUTRAL]=value to listener }
        fun presentation(value: Layout) = apply { layout=value }
        fun setCards(value: List<String>) = apply { cards=value }
        fun create(): BottomSheet = BottomSheet(context,title,message,custom,items,itemClick,buttons,cards,layout)
        fun show(): BottomSheet = create().also { it.show() }
    }
    class BottomSheet internal constructor(
        context: Context, title: CharSequence?, message: CharSequence?, custom: View?,
        items: Array<out CharSequence>?, itemClick: DialogInterface.OnClickListener?,
        buttons: Map<Int,Pair<String,DialogInterface.OnClickListener?>>, cards: List<String>?, private val layout: Layout
    ): Dialog(context) {
        private val theme=PackBackTheme(context)
        private val ui=PackBackComponents(context,theme)
        private val buttonViews=mutableMapOf<Int,Button>()
        init {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val root=ui.Column().apply { background=theme.shape(theme.background,28); setPadding(theme.dp(20),theme.dp(10),theme.dp(20),theme.dp(20)) }
            root.addView(View(context).apply { background=theme.shape(theme.line,99) },LinearLayout.LayoutParams(theme.dp(40),theme.dp(5)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=theme.dp(22) })
            if (layout==Layout.Pro) {
                root.addView(ui.AppLogo(48)); ui.Space(root,20); root.addView(ui.Pill("PackBack Pro",Tone.Brand)); ui.Space(root,12)
            }
            if (title != null) { root.addView(ui.Label(title,if(layout==Layout.Sheet)24f else 30f,true,weight=700)); ui.Space(root,16) }
            val body=ui.Column()
            if (message != null) { body.addView(ui.Label(message,16f,color=theme.secondary)); ui.Space(body,18) }
            if (cards != null) for (copy in cards) {
                val card=ui.Card(); val lines=copy.lines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) card.addView(ui.Label(lines.first(),20f,true,weight=700))
                for (line in lines.drop(1)) { ui.Space(card,8); card.addView(ui.Label(line,13f,color=theme.secondary)) }
                body.addView(card)
            }
            if (custom != null) { styleInputs(custom); body.addView(custom) }
            if (items != null) {
                for ((index,label) in items.withIndex()) {
                    val row=ui.Card(16).apply { minimumHeight=theme.dp(64); background=theme.ripple(theme.card,22,theme.line) }
                    val line=ui.Row()
                    val icon=when(layout) { Layout.Schedule -> "calendar"; Layout.Memory -> "memory"; Layout.Import -> "sparkles"; Layout.Pro -> "bag"; else -> "chevron" }
                    line.addView(ui.IconTile(icon,if(layout==Layout.Memory || layout==Layout.Import) Tone.AI else Tone.Brand))
                    val copy=ui.Column().apply { setPadding(theme.dp(12),0,theme.dp(6),0) }
                    val parts=label.toString().lines()
                    copy.addView(ui.Label(parts.first(),16f,weight=800))
                    for (part in parts.drop(1)) { ui.Space(copy,5); copy.addView(ui.Label(part,13f,color=theme.muted)) }
                    line.addView(copy,LinearLayout.LayoutParams(0,-2,1f)); line.addView(ui.Icon("chevron",theme.muted,18))
                    row.addView(line); row.setOnClickListener { itemClick?.onClick(this,index); dismiss() }; body.addView(row)
                }
                if (items.isEmpty() && layout==Layout.Memory) {
                    ui.Space(body,40)
                    body.addView(ui.IconTile("memory",Tone.AI,88).apply { layoutParams=LinearLayout.LayoutParams(theme.dp(88),theme.dp(88)).apply { gravity=Gravity.CENTER_HORIZONTAL } })
                    ui.Space(body,20)
                    body.addView(ui.Label("Nothing found yet.",24f,true,weight=700).apply { gravity=Gravity.CENTER })
                    ui.Space(body,14)
                    body.addView(ui.Label("Try another search or save a lecturer message.",16f,color=theme.secondary).apply { gravity=Gravity.CENTER })
                    ui.Space(body,40)
                }
            }
            val scroll=ScrollView(context).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; addView(body) }
            val available=(context.resources.displayMetrics.heightPixels*.90).toInt()-theme.dp(140+buttons.size*68+if(layout==Layout.Pro)100 else 0)
            val maxBody=minOf((context.resources.displayMetrics.heightPixels*.65).toInt(),available).coerceAtLeast(theme.dp(80))
            body.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels-theme.dp(40),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            root.addView(scroll,LinearLayout.LayoutParams(-1,minOf(body.measuredHeight,maxBody)))
            if (layout==Layout.Import) { ui.Space(root,10); root.addView(ui.Label("Nothing is added until you confirm.",12f,color=theme.muted).apply { gravity=Gravity.CENTER }) }
            // Keep all original buttons, callbacks and native auto-dismiss semantics. The editor's
            // existing onShow handler may override Save using getButton, exactly as before.
            for (which in listOf(BUTTON_POSITIVE,BUTTON_NEUTRAL,BUTTON_NEGATIVE)) {
                val spec=buttons[which] ?: continue
                val action={ spec.second?.onClick(this,which); dismiss() }
                val button=when(which) {
                    BUTTON_POSITIVE -> ui.PrimaryButton(spec.first,action)
                    BUTTON_NEUTRAL -> ui.SecondaryButton(spec.first,action)
                    else -> ui.GhostButton(spec.first,action)
                }
                buttonViews[which]=button; root.addView(button)
            }
            setContentView(root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setGravity(Gravity.BOTTOM)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(.42f)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setCanceledOnTouchOutside(true)
        }
        fun getButton(which: Int): Button = buttonViews.getValue(which)
        override fun show() {
            super.show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.navigationBarColor=theme.background
        }
        private fun styleInputs(view: View) {
            when(view) {
                is EditText -> {
                    theme.styleText(view,16f)
                    view.setHintTextColor(theme.muted)
                    view.background=theme.shape(theme.card,18,theme.line)
                    view.setPadding(theme.dp(16),theme.dp(14),theme.dp(16),theme.dp(14))
                    view.minimumHeight=theme.dp(56)
                }
                is CompoundButton -> { theme.styleText(view,14f); view.buttonTintList=ColorStateList.valueOf(theme.brand); view.minimumHeight=theme.dp(48) }
                is Spinner -> { view.background=theme.shape(theme.card,18,theme.line); view.minimumHeight=theme.dp(56); view.setPadding(theme.dp(12),0,theme.dp(12),0) }
                is TextView -> theme.styleText(view,view.textSize/context.resources.displayMetrics.scaledDensity,textColor=theme.ink)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) styleInputs(view.getChildAt(i))
        }
    }
}
