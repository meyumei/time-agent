package com.timeagent.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Materializes the guest resolver. System DNS is preferred; the pinned fallback keeps
 * offline/first-boot behavior deterministic. The file is written without following an
 * existing rootfs symlink such as /etc/resolv.conf -> /run/....
 */
internal object AndroidDns {
    fun write(context: Context, rootfs: File) {
        val servers = systemServers(context).ifEmpty { listOf("1.1.1.1") }.take(3)
        val text = buildString {
            for (server in servers) {
                append("nameserver ").append(server).append('\n')
            }
        }
        RuntimeFiles.writeNoFollow(rootfs, "etc/resolv.conf", text.toByteArray(Charsets.UTF_8), 0x1A4 /* 0644 */)
    }

    private fun systemServers(context: Context): List<String> =
        try {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return emptyList()
            val network = manager.activeNetwork ?: return emptyList()
            val properties = manager.getLinkProperties(network) ?: return emptyList()
            properties.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { address ->
                    address.isNotBlank() &&
                        address.none { it.isWhitespace() } &&
                        !address.contains('%')
                }
                .distinct()
                .take(3)
        } catch (_: RuntimeException) {
            emptyList()
        }
}
