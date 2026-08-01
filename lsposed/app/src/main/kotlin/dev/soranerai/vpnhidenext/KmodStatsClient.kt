package dev.soranerai.vpnhidenext

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.IOException

/** Client for the daemon-owned, session-scoped statistics history. */
internal object KmodStatsClient {
    private const val SOCKET_NAME = "vpnhide.stats.v1"

    fun getStats(): KmodStatsResponse = request("GET_STATS")

    fun clearHistory(): KmodStatsResponse = request("CLEAR_HISTORY")

    private fun request(command: String): KmodStatsResponse {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(command).append('\n')
                writer.flush()
            }
            val payload = socket.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (payload.isBlank()) throw IOException("statistics daemon returned an empty response")
            return KmodStatsResponse.fromJson(JSONObject(payload))
        } finally {
            socket.close()
        }
    }
}

internal data class KmodStatsResponse(
    val sessionId: String,
    val sequence: Long,
    val resolutionSec: Int,
    val retentionSec: Int,
    val dropped: Boolean,
    val droppedIntervals: Long,
    val oldestTimestampMs: Long,
    val newestTimestampMs: Long,
    val points: List<KmodStatsPoint>,
) {
    companion object {
        fun fromJson(json: JSONObject): KmodStatsResponse {
            val pointsJson = json.optJSONArray("points")
            val points = buildList {
                if (pointsJson != null) {
                    for (i in 0 until pointsJson.length()) {
                        val point = pointsJson.optJSONObject(i) ?: continue
                        val uidsJson = point.optJSONArray("uids")
                        val uids = buildList {
                            if (uidsJson != null) {
                                for (j in 0 until uidsJson.length()) {
                                    val uid = uidsJson.optJSONObject(j) ?: continue
                                    add(
                                        KmodUidStats(
                                            uid = uid.optInt("uid", -1),
                                            ioctl = uid.optLong("ioctl"),
                                            netlink = uid.optLong("netlink"),
                                            proc = uid.optLong("proc"),
                                            sockopt = uid.optLong("sockopt"),
                                            connect = uid.optLong("connect"),
                                            getname = uid.optLong("getname"),
                                            port = uid.optLong("port"),
                                        ),
                                    )
                                }
                            }
                        }.filter { it.uid >= 0 }
                        add(
                            KmodStatsPoint(
                                timestampMs = point.optLong("timestampMs"),
                                gap = point.optBoolean("gap", false),
                                uids = uids,
                            ),
                        )
                    }
                }
            }
            return KmodStatsResponse(
                sessionId = json.optString("sessionId", "unknown"),
                sequence = json.optLong("sequence", 0),
                resolutionSec = json.optInt("resolutionSec", 60),
                retentionSec = json.optInt("retentionSec", 86400),
                dropped = json.optBoolean("dropped", false),
                droppedIntervals = json.optLong("droppedIntervals", 0),
                oldestTimestampMs = json.optLong("oldestTimestampMs", 0),
                newestTimestampMs = json.optLong("newestTimestampMs", 0),
                points = points,
            )
        }
    }
}

internal data class KmodStatsPoint(
    val timestampMs: Long,
    val gap: Boolean,
    val uids: List<KmodUidStats>,
)

internal data class KmodUidStats(
    val uid: Int,
    val ioctl: Long,
    val netlink: Long,
    val proc: Long,
    val sockopt: Long,
    val connect: Long,
    val getname: Long,
    val port: Long,
) {
    fun values(): Map<String, Long> =
        mapOf(
            "ioctl" to ioctl,
            "netlink" to netlink,
            "proc" to proc,
            "sockopt" to sockopt,
            "connect" to connect,
            "getname" to getname,
        )

    fun total(): Long = ioctl + netlink + proc + sockopt + connect + getname
}
