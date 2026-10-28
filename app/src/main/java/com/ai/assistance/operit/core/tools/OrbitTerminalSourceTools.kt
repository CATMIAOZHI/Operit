package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.terminal.data.MirrorSource
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.utils.SourceManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the terminal downloads apt / pip / npm packages from. Orbit checks the network itself
 * (IP country and how fast each side answers) and picks China mirrors or the official sources.
 * The choice is saved in the terminal settings, which the terminal writes into its config files
 * every time it starts.
 */
internal object OrbitTerminalSourceTools {
    const val NAME = "orbit_terminal_source"

    private val managers = listOf(PackageManagerType.APT, PackageManagerType.PIP, PackageManagerType.NPM)
    private val regionIds = mapOf(
        "china" to mapOf(PackageManagerType.APT to "tuna_apt", PackageManagerType.PIP to "tuna_pip", PackageManagerType.NPM to "taobao_npm"),
        "global" to mapOf(PackageManagerType.APT to "official_apt", PackageManagerType.PIP to "official_pip", PackageManagerType.NPM to "official_npm"),
    )
    private const val TIMEOUT_MS = 4000
    private const val CALL_TIMEOUT_MS = 6000L
    private const val MAX_BODY = 4096L
    private const val NOT_MAINLAND = "XX"

    val prompt = ToolPrompt(
        name = NAME,
        description = "See, check and change where the terminal downloads apt / pip / npm packages from (China mirrors or official sources). " +
            "查看、探测并切换终端的软件源（国内镜像或官方源）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / probe / set", required = true),
            ToolParameterSchema("region", description = "set: china or global; sets apt, pip and npm together", required = false),
            ToolParameterSchema("apt", description = "set: a source id from list for apt, instead of region", required = false),
            ToolParameterSchema("pip", description = "set: a source id from list for pip, instead of region", required = false),
            ToolParameterSchema("npm", description = "set: a source id from list for npm, instead of region", required = false),
        ),
        details = """
  - list: the source now used by apt, pip and npm, and every source id you can pick.
  - probe: checks this phone's network: the country of its public IP, and how fast a China mirror and an official source answer. Returns a suggested region (china / global) and why. Takes a few seconds.
  - set: saves the choice. region=china uses Tsinghua (apt, pip) and npmmirror (npm); region=global uses the official sources. Or pass apt / pip / npm with ids from list.
  - The terminal writes the saved sources into its config files each time it starts, so changes you make by hand inside the terminal are lost on restart; use set to keep them.
  - Probe before the first install in the terminal, and again when downloads are slow or fail. Decide yourself; do not ask the user. Tell them in one short line what you picked.
  - 首次在终端装东西前、或下载很慢/失败时，先 probe 再 set，自己决定，不用问用户，换完一句话告诉用户即可。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value?.trim().orEmpty() }
            val sources = SourceManager(context.applicationContext)
            val result = when (args["action"].orEmpty()) {
                "list" -> list(sources, withChoices = true)
                "probe" -> probe()
                "set" -> set(sources, args)
                else -> error("action must be list, probe or set")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun choices(sources: SourceManager, pm: PackageManagerType): List<MirrorSource> = when (pm) {
        PackageManagerType.APT -> sources.aptSources
        PackageManagerType.PIP -> sources.pipSources
        PackageManagerType.NPM -> sources.npmSources
        PackageManagerType.RUST -> sources.rustSources
    }

    private fun key(pm: PackageManagerType) = pm.name.lowercase()

    private fun list(sources: SourceManager, withChoices: Boolean): JSONObject {
        val out = JSONObject()
        managers.forEach { pm ->
            val all = choices(sources, pm)
            val id = sources.getSelectedSourceId(pm)
            val current = all.find { it.id == id }
            out.put(key(pm), JSONObject().put("id", id).put("url", current?.url ?: "").apply {
                if (withChoices) put("choices", JSONArray(all.map { JSONObject().put("id", it.id).put("url", it.url) }))
            })
        }
        val region = regionIds.entries.firstOrNull { (_, ids) -> managers.all { sources.getSelectedSourceId(it) == ids[it] } }?.key
        return out.put("region", region ?: "mixed")
            .put("applies", "Saved here; the terminal writes it into its config files each time it starts.")
    }

    private fun set(sources: SourceManager, args: Map<String, String>): JSONObject {
        val region = args["region"].orEmpty().lowercase()
        val picks: Map<PackageManagerType, String> = if (region.isNotEmpty()) {
            regionIds[region] ?: error("region must be china or global")
        } else {
            managers.mapNotNull { pm -> args[key(pm)]?.takeIf { it.isNotEmpty() }?.let { pm to it } }.toMap()
        }
        require(picks.isNotEmpty()) { "Give region (china / global) or apt / pip / npm source ids from list" }
        picks.forEach { (pm, id) ->
            require(choices(sources, pm).any { it.id == id }) { "Unknown ${key(pm)} source id: $id; use the ids list shows" }
        }
        picks.forEach { (pm, id) -> sources.setSelectedSourceId(pm, id) }
        return list(sources, withChoices = false).put("changed", JSONArray(picks.keys.map { key(it) }))
            .put("note", "Saved. A terminal that is already open keeps its old sources until it restarts; to use the new ones " +
                "right away, write the same URLs into /etc/apt/sources.list (Ubuntu noble), ~/.config/pip/pip.conf and ~/.npmrc.")
    }

    private suspend fun probe(): JSONObject = coroutineScope {
        val country = async { country() }
        val china = listOf("https://mirrors.tuna.tsinghua.edu.cn/", "https://registry.npmmirror.com/").map { async { latency(it) } }
        val global = listOf("https://pypi.org/simple/", "https://registry.npmjs.org/").map { async { latency(it) } }
        val code = country.await()
        val chinaMs = china.awaitAll().filterNotNull().minOrNull()
        val globalMs = global.awaitAll().filterNotNull().minOrNull()
        val (suggest, why) = suggest(code, chinaMs, globalMs)
        JSONObject()
            .put("ip_country", when (code) { null -> JSONObject.NULL; NOT_MAINLAND -> "outside mainland China"; else -> code })
            .put("china_mirror_ms", chinaMs ?: JSONObject.NULL)
            .put("official_ms", globalMs ?: JSONObject.NULL)
            .put("suggested_region", suggest)
            .put("why", why)
    }

    /**
     * Speed is the real path the terminal will use (a VPN can make the IP look foreign while the
     * traffic still goes out in China, or the other way round), so a side that does not answer at
     * all loses, a clear speed gap wins, and the IP country decides when both answer about as fast.
     */
    internal fun suggest(country: String?, chinaMs: Long?, globalMs: Long?): Pair<String, String> {
        if (chinaMs != null && globalMs == null) return "china" to "Only the China mirror answered"
        if (globalMs != null && chinaMs == null) return "global" to "Only the official source answered"
        if (chinaMs != null && globalMs != null) {
            if (globalMs >= chinaMs * 2) return "china" to "China mirror answered much faster (${chinaMs}ms vs ${globalMs}ms)"
            if (chinaMs >= globalMs * 2) return "global" to "Official source answered much faster (${globalMs}ms vs ${chinaMs}ms)"
        }
        if (country != null) {
            return when (country) {
                "CN" -> "china" to "Public IP is in mainland China"
                NOT_MAINLAND -> "global" to "Public IP is outside mainland China"
                else -> "global" to "Public IP is in $country"
            }
        }
        return when {
            chinaMs != null && globalMs != null -> (if (chinaMs <= globalMs) "china" else "global") to "Both answered at similar speed"
            else -> "unknown" to "No network check succeeded; the phone may be offline"
        }
    }

    /** Two-letter country of the public IP, asked of a China and two global services at once. */
    private suspend fun country(): String? = coroutineScope {
        val answers = listOf(
            async {
                // {"ret":"ok","data":{"location":["中国","广东",...]}}; Hong Kong, Macau and Taiwan are not mainland.
                val loc = fetch("https://myip.ipip.net/json")?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optJSONObject("data")?.optJSONArray("location")
                when {
                    loc == null || loc.length() == 0 -> null
                    loc.optString(0) != "中国" -> NOT_MAINLAND
                    loc.optString(1) in setOf("香港", "澳门", "台湾") -> NOT_MAINLAND
                    else -> "CN"
                }
            },
            async { fetch("https://ipinfo.io/country")?.trim()?.takeIf { it.length == 2 }?.uppercase() },
            async {
                fetch("https://api.ip.sb/geoip")?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optString("country_code")?.takeIf { it.length == 2 }?.uppercase()
            },
        ).awaitAll().filterNotNull()
        // NOT_MAINLAND carries no country; prefer a real code from the global services when there is one.
        answers.firstOrNull { it != NOT_MAINLAND } ?: answers.firstOrNull()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            // Bounds the whole request, DNS and body included, so a bad network cannot hold probe.
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * Runs a request; stopping the coroutine cancels the call and closes its connection. The outer
     * limit also covers a DNS lookup that a cancelled call cannot interrupt.
     */
    private suspend fun <T> call(request: Request, read: (Response) -> T?): T? = withTimeoutOrNull(CALL_TIMEOUT_MS + 1000) {
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val value = response.use { runCatching { read(it) }.getOrNull() }
                    if (cont.isActive) cont.resume(value)
                }
            })
        }
    }

    private suspend fun fetch(url: String): String? =
        call(Request.Builder().url(url).header("User-Agent", "curl/8").build()) { response ->
            if (!response.isSuccessful) return@call null
            // Read at most 4 KB; these answers are a few hundred bytes.
            val source = response.body?.source() ?: return@call null
            source.request(MAX_BODY)
            source.buffer.readUtf8(minOf(source.buffer.size, MAX_BODY))
        }

    private suspend fun latency(url: String): Long? {
        val start = System.nanoTime()
        // A server error means this side is not usable, so it counts as no answer.
        return call(Request.Builder().url(url).head().build()) { response ->
            if (response.code in 200..399) (System.nanoTime() - start) / 1_000_000 else null
        }
    }
}
