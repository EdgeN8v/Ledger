package com.ledger.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private var view: ComposeView? = null
    private var wm: WindowManager? = null
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun dismiss() {
        timeoutJob?.cancel(); timeoutJob = null
        val v = view
        view = null
        if (v != null) try { wm?.removeView(v) } catch (_: Exception) {}
        owner?.stop(); owner = null
        wm = null
    }

    /** onPick fires once, with the category (and sub, if any) the user tapped; the popup then closes itself. */
    fun show(c: Context, amount: String, shop: String, categories: List<CategoryDto>, onPick: (categoryId: Int, sub: String?) -> Unit) {
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
        val composeView = ComposeView(appCtx)
        composeView.setViewTreeLifecycleOwner(o)
        composeView.setViewTreeViewModelStoreOwner(o)
        composeView.setViewTreeSavedStateRegistryOwner(o)
        composeView.setContent {
            MaterialTheme {
                PaymentPopupCard(
                    amount = amount, shop = shop, categories = categories,
                    onPick = { catId, sub -> onPick(catId, sub); dismiss() },
                    onDismiss = { dismiss() }
                )
            }
        }
        owner = o; view = composeView; wm = windowManager
        try {
            windowManager.addView(composeView, lp)
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
    onPick: (categoryId: Int, sub: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selCat by remember { mutableStateOf<Int?>(null) }
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
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            CategoryGrid(
                items = categories.filter { it.tx_type == "expense" },
                selectedId = selCat, txType = "expense",
                onPick = { id ->
                    val cat = categories.find { it.id == id }
                    if (cat != null && cat.subs.isNullOrEmpty()) onPick(id, null) else selCat = id
                }
            )
            if (subs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subs.forEach { s ->
                        FilterChip(selected = false, onClick = { onPick(selCat!!, s) }, label = { Text(s) })
                    }
                }
                TextButton(onClick = { onPick(selCat!!, null) }, modifier = Modifier.padding(top = 4.dp)) { Text("不选细分, 就这个类别") }
            }
        }
    }
}
