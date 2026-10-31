package com.timeagent.health

import android.content.Context
import android.content.Intent

/** One-way nudges to Gadgetbridge via its Intent API (must be enabled in Gadgetbridge settings). */
object Nudge {
    fun triggerExport(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(GadgetbridgeImporter.TRIGGER_EXPORT_ACTION).setPackage(GadgetbridgeImporter.ORIGIN_PACKAGE))
        }
    }

    fun triggerActivitySync(context: Context, deviceMac: String?) {
        runCatching {
            val intent = Intent(GadgetbridgeImporter.ACTIVITY_SYNC_ACTION).setPackage(GadgetbridgeImporter.ORIGIN_PACKAGE)
            if (!deviceMac.isNullOrBlank()) intent.putExtra("device", deviceMac)
            context.sendBroadcast(intent)
        }
    }
}
