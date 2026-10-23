package com.ledger.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.graphics.Canvas
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnAttach
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ledger.app.data.CategoryDto
import com.ledger.app.ui.CategoryGrid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "支付成功" popup: appears right over WeChat/Alipay/whatever shopping app you're in, the moment the payment
 * page is read, so you can pick a category while the receipt is still on screen instead of hunting for it in the
 * app later (modelled on 好好记账's bill-detail popup). Needs "display over other apps"
 * (Settings.canDrawOverlays) - without it, the payment is still captured and categorised normally in the
 * background, just without the popup.
 */
object PaymentPopup {
    fun canShow(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun requestPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private class Owner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        fun start() {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private var owner: Owner? = null
    private var view: View? = null
    private var rScope: CoroutineScope? = null
    private var wm: WindowManager? = null
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        CoroutineExceptionHandler { _, e -> android.util.Log.w("PaymentPopup", "popup job failed", e) })

    fun dismiss() {
        timeoutJob?.cancel(); timeoutJob = null
        val v = view
        view = null
        if (v != null) try { wm?.removeView(v) } catch (_: Exception) {}
        owner?.stop(); owner = null
        rScope?.cancel(); rScope = null
        wm = null
    }

    /** Catches whatever the popup's views throw while measuring, laying out, drawing or handling a touch. */
    private class Guard(c: Context, val fail: (Throwable) -> Unit) : FrameLayout(c) {
        private var broken = false
        private inline fun <T> guard(fallback: T, block: () -> T): T =
            if (broken) fallback else try { block() } catch (e: Throwable) { broken = true; fail(e); fallback }
        override fun onMeasure(w: Int, h: Int) =
            guard(Unit) { super.onMeasure(w, h) }.also { if (broken) setMeasuredDimension(0, 0) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = guard(Unit) { super.onLayout(changed, l, t, r, b) }
        override fun dispatchDraw(canvas: Canvas) = guard(Unit) { super.dispatchDraw(canvas) }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = guard(false) { super.dispatchTouchEvent(ev) }
    }

    /** onPick fires once, with the category (and sub, if any) the user tapped; the popup then closes itself.
     *  suggestedCategoryId/suggestedSub (a learned shop rule, see screenpay.suggest_category) pre-select the
     *  grid so a known shop needs only one tap - the one on "确认" - instead of hunting the category out. */
    fun show(
        c: Context, amount: String, shop: String, categories: List<CategoryDto>,
        suggestedCategoryId: Int? = null, suggestedSub: String? = null,
        onPick: (categoryId: Int, sub: String?) -> Unit
    ) {
        dismiss()
        if (!canShow(c)) return
        val appCtx = c.applicationContext
        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        val o = Owner().also { it.start() }
        // the popup runs inside the accessibility service's process: a bug in it must close the popup, never
        // crash the process (that would unbind the service and stop capture). Recomposition and effects run on
        // our own Recomposer whose errors land in a handler; measure/layout/draw/touch go through Guard below;
        // the first composition is done by hand inside a try once the view is attached.
        val fail = { e: Throwable ->
            android.util.Log.w("PaymentPopup", "popup failed, closing it", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post { dismiss() }
            Unit
        }
        val rScope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob() + CoroutineExceptionHandler { _, e -> fail(e) })
        val recomposer = Recomposer(rScope.coroutineContext)
        rScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val composeView = ComposeView(appCtx)
        composeView.setParentCompositionContext(recomposer)
        val frame = Guard(appCtx, fail)
        frame.addView(composeView)
        frame.setViewTreeLifecycleOwner(o)
        frame.setViewTreeViewModelStoreOwner(o)
        frame.setViewTreeSavedStateRegistryOwner(o)
        composeView.doOnAttach {
            try {
                composeView.setContent {
                    MaterialTheme {
                        PaymentPopupCard(
                            amount = amount, shop = shop, categories = categories,
                            initialCategoryId = suggestedCategoryId, initialSub = suggestedSub,
                            onPick = { catId, sub -> onPick(catId, sub); dismiss() },
                            onDismiss = { dismiss() }
                        )
                    }
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }
        owner = o; view = frame; wm = windowManager; this.rScope = rScope
        try {
            windowManager.addView(frame, lp)
        } catch (_: Exception) {
            dismiss(); return
        }
        timeoutJob = scope.launch { delay(25_000); dismiss() }   // don't leave a stray overlay stuck on screen forever
    }
}

@Composable
private fun PaymentPopupCard(
    amount: String,
    shop: String,
    categories: List<CategoryDto>,
    initialCategoryId: Int?,
    initialSub: String?,
    onPick: (categoryId: Int, sub: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selCat by remember { mutableStateOf(initialCategoryId) }
    var selSub by remember { mutableStateOf(initialSub) }
    val selected = categories.find { it.id == selCat }
    val subs = selected?.subs.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("¥$amount", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (shop.isNotBlank()) Text(shop, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // a known shop arrives pre-selected: one tap on "确认" and you're done. An unfamiliar one starts
                // blank, so "关闭" just dismisses without guessing at something uncertain.
                if (selCat != null) {
                    Button(onClick = { onPick(selCat!!, selSub) }) { Text("确认") }
                } else {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            CategoryGrid(
                items = categories.filter { it.tx_type == "expense" },
                selectedId = selCat, txType = "expense",
                onPick = { id -> if (id != selCat) { selCat = id; selSub = null } }
            )
            if (subs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subs.forEach { s ->
                        FilterChip(selected = s == selSub, onClick = { selSub = if (selSub == s) null else s }, label = { Text(s) })
                    }
                }
            }
        }
    }
}
