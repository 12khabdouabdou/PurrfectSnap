package me.eternal.purrfect.core.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import me.eternal.purrfect.core.Purrfect
import me.eternal.purrfect.core.wrapper.impl.valdi.ValdiContext
import me.eternal.purrfect.core.wrapper.impl.valdi.ValdiViewNode

private val foregroundDrawableListTag = randomTag()

@Suppress("UNCHECKED_CAST")
private fun View.getForegroundDrawables(): MutableMap<String, Drawable> {
    return getTag(foregroundDrawableListTag) as? MutableMap<String, Drawable>
        ?: mutableMapOf<String, Drawable>().also {
        setTag(foregroundDrawableListTag, it)
    }
}

private fun View.updateForegroundDrawable() {
    foreground = ShapeDrawable(object: Shape() {
        override fun draw(canvas: Canvas, paint: Paint) {
            getForegroundDrawables().forEach { (_, drawable) ->
                drawable.draw(canvas)
            }
        }
    })
}

fun View.removeForegroundDrawable(tag: String) {
    getForegroundDrawables().remove(tag)?.let {
        updateForegroundDrawable()
    }
}

fun View.addForegroundDrawable(tag: String, drawable: Drawable) {
    getForegroundDrawables()[tag] = drawable
    updateForegroundDrawable()
}

fun View.dispatchSyntheticTap(x: Float, y: Float, tapDurationMs: Long = 50L) {
    val downTime = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    dispatchTouchEvent(downEvent)
    downEvent.recycle()

    val upEvent = MotionEvent.obtain(downTime, downTime + tapDurationMs, MotionEvent.ACTION_UP, x, y, 0)
    dispatchTouchEvent(upEvent)
    upEvent.recycle()
}

fun View.triggerCloseTouchEvent(x: Float = 0f, y: Float = 0f, tapDurationMs: Long = 50L) {
    dispatchSyntheticTap(x, y, tapDurationMs)
}

fun View.triggerCloseTouchEventAtFraction(
    xFraction: Float,
    yFraction: Float = 0.5f,
    tapDurationMs: Long = 50L
) {
    val targetWidth = width.takeIf { it > 0 } ?: measuredWidth
    val targetHeight = height.takeIf { it > 0 } ?: measuredHeight
    val x = if (targetWidth > 0) targetWidth * xFraction.coerceIn(0f, 1f) else 0f
    val y = if (targetHeight > 0) targetHeight * yFraction.coerceIn(0f, 1f) else 0f
    triggerCloseTouchEvent(x, y, tapDurationMs)
}

fun Activity.triggerRootCloseTouchEvent() {
    findViewById<View>(android.R.id.content).triggerCloseTouchEvent()
}

fun ViewGroup.children(): List<View> {
    val children = mutableListOf<View>()
    for (i in 0 until childCount) {
        children.add(getChildAt(i))
    }
    return children
}

fun View.iterateParent(predicate: (View) -> Boolean) {
    var parent = this.parent as? View ?: return
    while (true) {
        if (predicate(parent)) return
        parent = parent.parent as? View ?: return
    }
}

fun View.findParent(maxIteration: Int = Int.MAX_VALUE, predicate: (View) -> Boolean): View? {
    var parent = this.parent as? View ?: return null
    var iteration = 0
    while (iteration < maxIteration) {
        if (predicate(parent)) return parent
        parent = parent.parent as? View ?: return null
        iteration++
    }
    return null
}


data class LayoutChangeParams(
    val view: View,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val oldLeft: Int,
    val oldTop: Int,
    val oldRight: Int,
    val oldBottom: Int
)

fun View.onLayoutChange(block: (LayoutChangeParams) -> Unit): View.OnLayoutChangeListener {
    return View.OnLayoutChangeListener  { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        block(LayoutChangeParams(view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom))
    }.also { addOnLayoutChangeListener (it) }
}

fun View.onAttachChange(onAttach: (View.OnAttachStateChangeListener) -> Unit = {}, onDetach: (View.OnAttachStateChangeListener) -> Unit = {}): View.OnAttachStateChangeListener {
    return object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            onAttach(this)
        }
        override fun onViewDetachedFromWindow(v: View) {
            onDetach(this)
        }
    }.also { addOnAttachStateChangeListener(it) }
}

private val layoutListenerTag = randomTag()

fun View.hideViewCompletely() {
    val alreadyHidden = visibility == View.GONE 
        && layoutParams?.width == 0 
        && layoutParams?.height == 0
    if (alreadyHidden) return

    isEnabled = false
    visibility = View.GONE
    setWillNotDraw(true)
    layoutParams = layoutParams?.apply {
        width = 0
        height = 0
    }

    // Clean up any pre-existing listener to avoid leaks
    (getTag(layoutListenerTag) as? View.OnLayoutChangeListener)?.let {
        removeOnLayoutChangeListener(it)
    }

    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (visibility != View.GONE || layoutParams?.width != 0 || layoutParams?.height != 0) {
            isEnabled = false
            visibility = View.GONE
            setWillNotDraw(true)
            layoutParams = layoutParams?.apply { width = 0; height = 0 }
        }
    }
    addOnLayoutChangeListener(listener)
    setTag(layoutListenerTag, listener)
}

fun View.showViewCompletely(
    originalWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT, 
    originalHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
) {
    (getTag(layoutListenerTag) as? View.OnLayoutChangeListener)?.let {
        removeOnLayoutChangeListener(it)
        setTag(layoutListenerTag, null)
    }

    isEnabled = true
    visibility = View.VISIBLE
    setWillNotDraw(false)
    layoutParams = layoutParams?.apply {
        width = originalWidth
        height = originalHeight
    }
}

fun View.isCompletelyHidden(): Boolean = getTag(layoutListenerTag) != null

fun View.getValdiViewNode(): ValdiViewNode? {
    val valdiView = Purrfect.classCache.valdiView ?: return null
    if (!valdiView.isInstance(this)) return null

    val viewNode = this::class.java.methods.firstOrNull {
        it.name == "getComposerViewNode" || it.name == "getValdiViewNode"
    }?.invoke(this) ?: return null

    return ValdiViewNode.fromNode(viewNode)
}

fun View.getValdiContext(): ValdiContext? {
    val valdiView = Purrfect.classCache.valdiView ?: return null
    if (!valdiView.isInstance(this)) return null

    return ValdiContext(this::class.java.methods.firstOrNull {
        it.name == "getComposerContext" || it.name == "getValdiContext"
    }?.invoke(this) ?: return null)
}

object ViewAppearanceHelper {
    fun newAlertDialogBuilder(context: Context?) = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
}
