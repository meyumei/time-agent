package com.timeagent.fish

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.timeagent.MainActivity
import kotlinx.coroutines.*

class FishRuntimeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fish-runtime","内置角色运行",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1042,Notification.Builder(this,"fish-runtime")
            .setSmallIcon(com.timeagent.R.drawable.ic_launcher).setContentTitle("时间 Agent 内置角色")
            .setContentText("运行环境正在准备或运行，可在管家页查看与停止")
            .setContentIntent(open).setOngoing(true).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"timeagent:fish").apply {
            setReferenceCounted(false)
            acquire()
        }
        FishSession.current()?.let { session -> scope.launch {
            session.runtime.state.collect { state -> if(state.error!=null || state.stage=="已停止") stopSelf() }
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        FishSession.current()?.stop()
        super.onDestroy()
    }
}
