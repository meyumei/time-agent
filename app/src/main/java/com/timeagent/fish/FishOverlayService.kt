package com.timeagent.fish

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.timeagent.MainActivity
import com.timeagent.R
import com.timeagent.core.FishAppearance
import com.timeagent.ui.TimeAgentTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/** Only owns the global window; closing it never stops the shared fish runtime. */
class FishOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowContext: Context
    private lateinit var windows: WindowManager
    private var session: FishSession? = null
    private var root: FishOverlayTouchView? = null
    private var content: ComposeView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var windowAdded = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateWindow() }
    }

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        windowContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        windows = windowContext.getSystemService(WindowManager::class.java)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("fish-companion", "全局悬浮陪伴", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1043, openIntent(this, "chat"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close = PendingIntent.getService(this, 1044, Intent(this, FishOverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1043, Notification.Builder(this, "fish-companion").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("全局悬浮陪伴").setContentText("点击打开管家；长按悬浮角色可回到陪伴设置")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "关闭全局显示", close).build()).build())
        session = FishSession.current()
        val fish = session ?: run { stopSelf(); return }
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(fish.companion.settings, mainVisible) { _, _ -> Unit }.collect { updateWindow() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            session?.companion?.let { it.save(it.settings.value.copy(globalVisible = false)) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateWindow() {
        val fish = session ?: return
        val config = fish.companion.settings.value
        if (!config.globalVisible || !Settings.canDrawOverlays(this)) {
            if (config.globalVisible) fish.companion.save(config.copy(globalVisible = false))
            stopSelf()
            return
        }
        if (mainVisible.value || !getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            removeWindow()
            return
        }
        val density = resources.displayMetrics.density
        val side = ((if (config.appearance == FishAppearance.CHIBI) 116 else 142) * density).roundToInt()
        val height = side + (26 * density).roundToInt()
        if (root == null) {
            val frame = FishOverlayTouchView(windowContext,
                onTap = { fish.companion.event("tap"); startActivity(openIntent(this, "chat")) },
                onLongPress = { startActivity(openIntent(this, "appearance")) },
                onMove = { dx, dy -> moveWindow(dx, dy) },
                onDrop = { savePosition() })
            frame.setViewTreeLifecycleOwner(this)
            frame.setViewTreeSavedStateRegistryOwner(this)
            frame.contentDescription = "${fish.character.profile.value.name}，点击继续管家对话，长按陪伴设置，可拖动"
            if (Build.VERSION.SDK_INT < 30) frame.setOnApplyWindowInsetsListener { _, insets ->
                if (windowAdded) {
                    val latest = fish.companion.settings.value
                    val oldX = layout?.x
                    val oldY = layout?.y
                    place(latest.globalX, latest.globalY)
                    if (oldX != layout?.x || oldY != layout?.y) windows.updateViewLayout(frame, layout)
                }
                insets
            }
            val compose = ComposeView(windowContext).apply {
                setContent {
                    val settings by fish.companion.settings.collectAsStateWithLifecycle()
                    val presence by fish.companion.presence.collectAsStateWithLifecycle()
                    val voice by fish.voice.status.collectAsStateWithLifecycle()
                    val profile by fish.character.profile.collectAsStateWithLifecycle()
                    TimeAgentTheme {
                        Column(Modifier.wrapContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CharacterSprite(fish, Modifier.size(if (settings.appearance == FishAppearance.CHIBI) 116.dp else 142.dp))
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .93f)) {
                                Text("${profile.name} · ${if (voice.speaking) "正在说话" else fishStatus(presence)}",
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            frame.addView(compose)
            val params = WindowManager.LayoutParams(side, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "TimeAgentGlobalCompanion"
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            root = frame; content = compose; layout = params
            place(config.globalX, config.globalY)
            try {
                windows.addView(frame, params)
                windowAdded = true
                registry.currentState = Lifecycle.State.RESUMED
            } catch (error: WindowManager.BadTokenException) {
                disableAfterWindowError()
            } catch (error: SecurityException) {
                disableAfterWindowError()
            }
        } else {
            layout?.let { it.width = side; it.height = height }
            place(config.globalX, config.globalY)
            root?.let { windows.updateViewLayout(it, layout) }
        }
    }

    private fun disableAfterWindowError() {
        session?.companion?.let { it.save(it.settings.value.copy(globalVisible = false)) }
        stopSelf()
    }
    private fun area(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bounds = metrics.bounds
            return Rect(bounds.left + insets.left, bounds.top + insets.top, bounds.right - insets.right, bounds.bottom - insets.bottom)
        }
        val size = Point()
        @Suppress("DEPRECATION")
        windows.defaultDisplay.getRealSize(size)
        val insets = root?.rootWindowInsets
        @Suppress("DEPRECATION")
        return if (insets == null) Rect(0, 0, size.x, size.y)
            else Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                size.x - insets.systemWindowInsetRight, size.y - insets.systemWindowInsetBottom)
    }
    private fun place(x: Float, y: Float) {
        val params = layout ?: return
        val bounds = area()
        params.x = bounds.left + ((bounds.width() - params.width).coerceAtLeast(0) * x).roundToInt()
        params.y = bounds.top + ((bounds.height() - params.height).coerceAtLeast(0) * y).roundToInt()
    }
    private fun moveWindow(dx: Float, dy: Float) {
        val params = layout ?: return
        val bounds = area()
        params.x = (params.x + dx.roundToInt()).coerceIn(bounds.left, (bounds.right - params.width).coerceAtLeast(bounds.left))
        params.y = (params.y + dy.roundToInt()).coerceIn(bounds.top, (bounds.bottom - params.height).coerceAtLeast(bounds.top))
        root?.let { windows.updateViewLayout(it, params) }
    }
    private fun savePosition() {
        val params = layout ?: return
        val fish = session ?: return
        val bounds = area()
        val travelX = (bounds.width() - params.width).coerceAtLeast(1)
        val travelY = (bounds.height() - params.height).coerceAtLeast(1)
        fish.companion.save(fish.companion.settings.value.copy(
            globalX = ((params.x - bounds.left).toFloat() / travelX).coerceIn(0f, 1f),
            globalY = ((params.y - bounds.top).toFloat() / travelY).coerceIn(0f, 1f)))
    }
    private fun removeWindow() {
        savePosition()
        registry.currentState = Lifecycle.State.CREATED
        root?.let { if (windowAdded) windows.removeViewImmediate(it) }
        windowAdded = false
        content?.disposeComposition()
        root = null; content = null; layout = null
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateWindow()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        if (session != null) unregisterReceiver(screen)
        removeWindow()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DESTINATION = "com.timeagent.extra.FISH_DESTINATION"
        private const val STOP = "com.timeagent.fish.STOP_GLOBAL"
        private val mainVisible = MutableStateFlow(false)
        fun setMainVisible(visible: Boolean) { mainVisible.value = visible }
        fun sync(context: Context, fish: FishSession) {
            if (!fish.companion.settings.value.globalVisible) return
            if (Settings.canDrawOverlays(context)) context.startForegroundService(Intent(context, FishOverlayService::class.java))
            else {
                fish.companion.save(fish.companion.settings.value.copy(globalVisible = false))
                context.stopService(Intent(context, FishOverlayService::class.java))
            }
        }
        fun openIntent(context: Context, destination: String): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DESTINATION, destination)
    }
}

/** Raw screen coordinates remain stable while the window itself moves under the finger. */
internal class FishOverlayTouchView(
    context: Context, onTap: () -> Unit, onLongPress: () -> Unit,
    private val onMove: (Float, Float) -> Unit, private val onDrop: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val hold = Runnable { if (!dragging) { longPressed = true; performLongClick() } }
    init {
        isClickable = true; isLongClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onTap() }
        setOnLongClickListener { onLongPress(); true }
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; lastX = downX; downY = event.rawY; lastY = downY
                dragging = false; longPressed = false
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressed && (dragging || kotlin.math.abs(event.rawX - downX) > slop || kotlin.math.abs(event.rawY - downY) > slop)) {
                    removeCallbacks(hold); dragging = true
                    onMove(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (dragging) onDrop() else if (!longPressed) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); if (dragging) onDrop() }
        }
        return true
    }
    override fun onDetachedFromWindow() { removeCallbacks(hold); super.onDetachedFromWindow() }
}
