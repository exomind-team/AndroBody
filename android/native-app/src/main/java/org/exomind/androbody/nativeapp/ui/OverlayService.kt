package org.exomind.androbody.nativeapp.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.exomind.androbody.nativeapp.MainActivity
import org.exomind.androbody.nativeapp.BuildConfig
import org.exomind.androbody.nativeapp.R
import org.exomind.androbody.nativeapp.graph
import org.exomind.androbody.nativeapp.runtime.NativeState
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import kotlin.math.abs
import kotlin.math.roundToInt

/** User-started floating assistant card. It shows app-owned state only; no screen capture or accessibility service. */
class OverlayService : Service() {
    private var overlayView: FrameLayout? = null
    private var badgeView: ComposeView? = null
    private var composeView: ComposeView? = null
    private var bubbleControl: Button? = null
    private lateinit var windows: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var appContext: Context? = null
    private val viewOwner = OverlayViewOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private var collapsedPositionX: Int? = null
    private var collapsedPositionY: Int? = null
    private var expandedCardMoved = false

    override fun onCreate() {
        super.onCreate()
        viewOwner.create()
        logOverlay("service_created", RuntimeLogLevel.INFO, EvidenceKind.RESULT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logOverlay("service_start_requested", RuntimeLogLevel.INFO, EvidenceKind.ACTUAL_ATTEMPT,
            fields = mapOf("startId" to startId.toString(), "stopAction" to (intent?.action == STOP).toString()))
        if (intent?.action == STOP) {
            logOverlay("service_stop_requested", RuntimeLogLevel.INFO, EvidenceKind.ACTUAL_ATTEMPT,
                CapabilityFeasibility.SUBMITTED, mapOf("startId" to startId.toString()))
            stopSelf(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            logOverlay("overlay_permission_missing", RuntimeLogLevel.WARN, EvidenceKind.PREFLIGHT,
                CapabilityFeasibility.NEEDS_PERMISSION, mapOf("startId" to startId.toString()))
            stopSelf(); return START_NOT_STICKY
        }
        viewOwner.start()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮助手", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 10, Intent(this, OverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_morph)
            .setContentTitle("AndroBody 助手").setContentText("点击悬浮入口查看最新状态")
            .setContentIntent(open).setOngoing(true).addAction(0, "关闭悬浮入口", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(102, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(102, notice)
            logOverlay("foreground_started", RuntimeLogLevel.INFO, EvidenceKind.PLATFORM_RESPONSE,
                CapabilityFeasibility.AVAILABLE, mapOf("apiLevel" to Build.VERSION.SDK_INT.toString()))
        } catch (failure: Exception) {
            logOverlay("foreground_start_failed", RuntimeLogLevel.ERROR, EvidenceKind.PLATFORM_RESPONSE,
                overlayFailureFeasibility(failure), mapOf("exceptionClass" to failure.javaClass.name))
            throw failure
        }
        if (overlayView == null) showCard()
        return START_NOT_STICKY
    }

    private fun showCard() {
        windows = getSystemService(WindowManager::class.java)
        appContext = applicationContext
        expanded.value = false
        val density = resources.displayMetrics.density
        val diameter = dp(58)
        val metrics = resources.displayMetrics
        val placement = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val hasSavedPlacement = placement.contains(X_DP) && placement.contains(Y_DP)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (hasSavedPlacement) (placement.getFloat(X_DP, 0f) * density).roundToInt()
                else (metrics.widthPixels - diameter - dp(12)).coerceAtLeast(0)
            y = if (hasSavedPlacement) (placement.getFloat(Y_DP, 0f) * density).roundToInt() else dp(200)
        }

        val badge = ComposeView(this).apply {
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                val app = appContext!!.graph
                val preferences by app.preferences.state.collectAsState()
                val state by app.native.state.collectAsState()
                MorphTheme(preferences.theme) {
                    FloatingAssistantBadge(state)
                }
            }
        }
        val content = ComposeView(this).apply {
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = View.GONE
            setContent {
                val app = appContext!!.graph
                val preferences by app.preferences.state.collectAsState()
                val state by app.native.state.collectAsState()
                MorphTheme(preferences.theme) {
                    FloatingAssistantCard(
                        state = state,
                        toggle = { setExpanded(false) },
                        open = {
                            startActivity(Intent(this@OverlayService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        },
                        close = { stopSelf() },
                        stop = { app.native.cancel() },
                        dragHandle = ::createDragHandle,
                    )
                }
            }
        }
        val hitTarget = Button(this).apply {
            text = ""
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            minWidth = 0
            minHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = bubbleDescription()
            setOnClickListener { setExpanded(true) }
            setOnTouchListener(::handleOverlayTouch)
        }
        val root = FrameLayout(this).apply {
            // WindowRecomposer resolves the owner from the window root, not from an isolated child ComposeView.
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(hitTarget, FrameLayout.LayoutParams(diameter, diameter))
        }
        try {
            windows.addView(root, params)
            overlayView = root
            badgeView = badge
            composeView = content
            bubbleControl = hitTarget
            active.value = true
            logOverlay("overlay_window_attached", RuntimeLogLevel.INFO, EvidenceKind.PLATFORM_RESPONSE,
                CapabilityFeasibility.AVAILABLE, mapOf("savedPosition" to hasSavedPlacement.toString()))
            serviceScope.launch {
                appContext!!.graph.native.state.collect {
                    hitTarget.contentDescription = bubbleDescription(it)
                    if (hitTarget.isShown) hitTarget.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                }
            }
        } catch (failure: Exception) {
            logOverlay("overlay_window_attach_failed", RuntimeLogLevel.ERROR, EvidenceKind.PLATFORM_RESPONSE,
                overlayFailureFeasibility(failure), mapOf("exceptionClass" to failure.javaClass.name))
            active.value = false
            stopSelf()
        }
    }

    /** Drag movement is a preview; only release persists one final position. Cancel restores the start. */
    private fun handleOverlayTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                debugOverlay("touch-down view=${view.javaClass.simpleName} expanded=${expanded.value}")
                downRawX = event.rawX
                downRawY = event.rawY
                originX = params.x
                originY = params.y
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val crossedTouchSlop = !dragging &&
                    (abs(event.rawX - downRawX) > ViewConfiguration.get(this).scaledTouchSlop ||
                        abs(event.rawY - downRawY) > ViewConfiguration.get(this).scaledTouchSlop)
                if (crossedTouchSlop) {
                    dragging = true
                    debugOverlay("drag-start x=$originX y=$originY")
                }
                if (dragging) {
                    updatePosition(originX + (event.rawX - downRawX).roundToInt(),
                        originY + (event.rawY - downRawY).roundToInt())
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                dragging = false
                debugOverlay("touch-up dragged=$wasDragging expanded=${expanded.value}")
                if (wasDragging) {
                    if (expanded.value) expandedCardMoved = true
                    savePosition()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                if (wasDragging) {
                    updatePosition(originX, originY)
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    private fun updatePosition(x: Int, y: Int) {
        val root = overlayView ?: return
        val metrics = resources.displayMetrics
        val fallbackWidth = if (expanded.value) dp(360) else dp(58)
        val fallbackHeight = if (expanded.value) dp(460) else dp(58)
        val width = root.width.takeIf { it > 0 } ?: fallbackWidth
        val height = root.height.takeIf { it > 0 } ?: fallbackHeight
        params.x = x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        runCatching { windows.updateViewLayout(root, params) }.onFailure {
            logOverlay("overlay_window_update_failed", RuntimeLogLevel.ERROR, EvidenceKind.PLATFORM_RESPONSE,
                overlayFailureFeasibility(it), mapOf("exceptionClass" to it.javaClass.name))
            stopSelf()
        }
    }

    private fun overlayFailureFeasibility(failure: Throwable): CapabilityFeasibility = when {
        failure is SecurityException || failure is WindowManager.BadTokenException -> CapabilityFeasibility.BLOCKED_OBSERVED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && failure is ForegroundServiceStartNotAllowedException ->
            CapabilityFeasibility.BLOCKED_OBSERVED
        else -> CapabilityFeasibility.FAILED
    }

    private fun savePosition() {
        val density = resources.displayMetrics.density
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putFloat(X_DP, params.x / density)
            .putFloat(Y_DP, params.y / density)
            .apply()
    }

    private fun setExpanded(value: Boolean) {
        debugOverlay("set-expanded requested=$value current=${expanded.value} attached=${overlayView?.isAttachedToWindow}")
        if (expanded.value == value) return
        if (value) {
            collapsedPositionX = params.x
            collapsedPositionY = params.y
            expandedCardMoved = false
        }
        val restoreBubblePosition = !value && !expandedCardMoved
        val bubbleX = collapsedPositionX
        val bubbleY = collapsedPositionY
        expanded.value = value
        val hitTarget = bubbleControl ?: return
        val badge = badgeView ?: return
        val content = composeView ?: return
        val root = overlayView ?: return
        hitTarget.visibility = if (value) View.GONE else View.VISIBLE
        badge.visibility = if (value) View.GONE else View.VISIBLE
        content.visibility = if (value) View.VISIBLE else View.GONE
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.requestLayout()
        badge.requestLayout()
        content.requestLayout()
        root.post {
            if (overlayView !== root || !root.isAttachedToWindow) return@post
            runCatching { windows.updateViewLayout(root, params) }.onFailure { stopSelf() }
            root.postOnAnimation {
                if (overlayView !== root || !root.isAttachedToWindow) return@postOnAnimation
                if (value) {
                    val rightEdge = (collapsedPositionX ?: params.x) + dp(58)
                    val preferredX = if (rightEdge > resources.displayMetrics.widthPixels / 2)
                        rightEdge - root.width else collapsedPositionX ?: params.x
                    updatePosition(preferredX, collapsedPositionY ?: params.y)
                } else if (restoreBubblePosition && bubbleX != null && bubbleY != null) {
                    updatePosition(bubbleX, bubbleY)
                } else updatePosition(params.x, params.y)
            }
        }
    }

    private fun createDragHandle(context: Context): View = Button(context).apply {
        text = ""
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "拖动或收起悬浮助手"
        setOnClickListener { setExpanded(false) }
        setOnTouchListener(::handleOverlayTouch)
    }

    private fun bubbleDescription(state: NativeState = appContext?.graph?.native?.state?.value ?: NativeState()): String =
        "AndroBody 助手，${state.phase}；点按查看最新消息"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun debugOverlay(message: String) {
        if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, message)
        logOverlay("gesture_state", RuntimeLogLevel.DEBUG, EvidenceKind.RESULT, fields = mapOf("detail" to message))
    }

    private fun logOverlay(stage: String, level: RuntimeLogLevel, evidence: EvidenceKind,
        feasibility: CapabilityFeasibility? = null, fields: Map<String, String> = emptyMap()) {
        runCatching { applicationContext.graph.runtimeLog.record("overlay", stage, origin = "overlay",
            level = level, evidenceKind = evidence, feasibility = feasibility, fields = fields) }
    }

    override fun onDestroy() {
        logOverlay("service_destroyed", RuntimeLogLevel.INFO, EvidenceKind.RECOVERY,
            CapabilityFeasibility.UNKNOWN, mapOf("windowAttached" to (overlayView?.isAttachedToWindow == true).toString()))
        overlayView?.let { runCatching { windows.removeView(it) } }
        overlayView = null
        badgeView = null
        composeView = null
        bubbleControl = null
        appContext = null
        serviceScope.cancel()
        active.value = false
        expanded.value = false
        viewOwner.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STOP = "org.exomind.andromorph.native.STOP_OVERLAY"
        private const val CHANNEL = "native-overlay"
        private const val PREFERENCES = "native-overlay-placement"
        private const val X_DP = "x-dp"
        private const val Y_DP = "y-dp"
        private const val DEBUG_TAG = "AndroMorphOverlay"
        val active = MutableStateFlow(false)
        val expanded = MutableStateFlow(false)
    }
}

private class OverlayViewOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun start() { lifecycleRegistry.currentState = Lifecycle.State.STARTED }

    fun destroy() { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
}

@Composable private fun FloatingAssistantBadge(state: NativeState) {
    val statusColor = when {
        state.error != null || state.interrupted -> MaterialTheme.colorScheme.error
        state.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier.size(58.dp),
        shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(9.dp).clip(CircleShape).background(statusColor))
        }
    }
}

@Composable private fun FloatingAssistantCard(
    state: NativeState,
    toggle: () -> Unit,
    open: () -> Unit,
    close: () -> Unit,
    stop: () -> Unit,
    dragHandle: (Context) -> View,
) {
    val statusColor = when {
        state.error != null || state.interrupted -> MaterialTheme.colorScheme.error
        state.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val latest = state.liveReply?.takeIf { it.isNotBlank() }
        ?: state.entries.lastOrNull { it.role == "assistant" }?.text.orEmpty()
    Surface(
        modifier = Modifier.widthIn(min = 250.dp, max = 360.dp).padding(4.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp, shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    AndroidView(factory = dragHandle, modifier = Modifier.fillMaxSize())
                    Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("AndroBody", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                        Text(state.phase, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                IconButton(onClick = toggle) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起悬浮卡片")
                }
                IconButton(onClick = close) {
                    Icon(Icons.Default.Close, contentDescription = "关闭悬浮入口")
                }
            }
            HorizontalDivider()
            if (latest.isBlank()) {
                Text(if (state.running) "等待助手首段回复" else "尚无助手回复",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("最新回复", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    MarkdownText(latest, Modifier.fillMaxWidth())
                }
                if (!state.running && state.liveReply != null)
                    Text("部分回复 · 未完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.running) TextButton(onClick = stop) {
                    Text("■ 停止")
                }
                TextButton(onClick = open) {
                    Text("打开助手")
                }
            }
        }
    }
}
