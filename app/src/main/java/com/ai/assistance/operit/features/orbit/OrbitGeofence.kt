package com.ai.assistance.operit.features.orbit

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.*
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.*

/** A location task keeps its definition in source; no new task table or database migration. */
data class OrbitGeofenceDefinition(
    val name: String, val latitude: Double, val longitude: Double,
    val radius: Double = 200.0, val transition: String = "ENTER",
) {
    init {
        require(name.isNotBlank() && name.length <= 80) { "location.name must have 1 to 80 characters" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "location.latitude must be -90 to 90" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "location.longitude must be -180 to 180" }
        require(radius.isFinite() && radius in 100.0..10_000.0) { "location.radius must be 100 to 10000 metres" }
        require(transition in setOf("ENTER", "EXIT")) { "location.transition must be ENTER or EXIT" }
    }

    val outerRadius: Double get() = radius + max(50.0, radius * 0.2)
    fun json(): JSONObject = JSONObject().put("name", name).put("latitude", latitude)
        .put("longitude", longitude).put("radius", radius).put("transition", transition)

    fun distance(latitude: Double, longitude: Double): Double {
        val lat = Math.toRadians(latitude - this.latitude)
        val lon = Math.toRadians(longitude - this.longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(this.latitude)) *
            cos(Math.toRadians(latitude)) * sin(lon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** In the gap, or with an inaccurate fix, keep the old state instead of bouncing at the edge. */
    fun side(distance: Double, accuracy: Double): Boolean? = when {
        !distance.isFinite() || !accuracy.isFinite() || accuracy < 0 -> null
        distance + accuracy <= radius -> true
        distance - accuracy >= outerRadius -> false
        else -> null
    }

    companion object {
        fun parse(source: String): OrbitGeofenceDefinition {
            val json = JSONObject(source)
            return OrbitGeofenceDefinition(json.getString("name"), json.getDouble("latitude"),
                json.getDouble("longitude"), json.optDouble("radius", 200.0),
                json.optString("transition", "ENTER").uppercase())
        }
    }
}

/**
 * Android's system proximity alerts work without Play services. Two radii provide hysteresis;
 * after a boundary event a bounded fresh fix confirms it, at least a minute later. Idle tasks
 * do not poll GPS. The periodic work only restores registration/permissions after a reboot.
 */
object OrbitGeofence {
    const val KIND = "GEOFENCE"
    internal const val CONFIRM_MS = 60_000L
    private const val MAINTENANCE = "orbit_geofence_restore"
    private val lock = Mutex()
    private val registered = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs(context: Context) = context.getSharedPreferences("orbit_geofences", Context.MODE_PRIVATE)
    private fun key(task: OrbitTaskEntity) = "${task.id}/${task.version}"
    private fun confirmation(taskId: String) = "orbit_geofence_confirm_$taskId"

    fun permissionProblem(context: Context): String? {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return context.getString(R.string.orbit_geofence_needs_precise)
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            return context.getString(R.string.orbit_geofence_needs_background)
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !androidx.core.location.LocationManagerCompat.isLocationEnabled(manager))
            return context.getString(R.string.orbit_geofence_needs_location)
        return null
    }

    fun status(context: Context, task: OrbitTaskEntity): String? =
        if (task.state != "ACTIVE") null else permissionProblem(context)
            ?: prefs(context).getString("problem:${task.id}", null)
            ?: if (key(task) !in prefs(context).getStringSet("registered", emptySet()).orEmpty())
                context.getString(R.string.orbit_geofence_unavailable) else null

    fun observeStatus(context: Context, task: OrbitTaskEntity) = callbackFlow<String?> {
        val store = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == "registered" || changed == "problem:${task.id}") trySend(status(context, task))
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        trySend(status(context, task))
        awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    suspend fun source(context: Context, raw: String): String {
        val json = JSONObject(raw)
        require(json.opt("current") == null || json.opt("current") is Boolean) { "location.current must be true or false" }
        if (json.optBoolean("current", false)) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                context.getString(R.string.orbit_geofence_needs_precise)
            }
            require(!json.has("latitude") && !json.has("longitude")) { "Use current=true or latitude/longitude, not both" }
            val fix = currentLocation(context, System.currentTimeMillis())
                ?: error(context.getString(R.string.orbit_geofence_no_fix))
            json.put("latitude", fix.latitude).put("longitude", fix.longitude)
        }
        return OrbitGeofenceDefinition.parse(json.toString()).json().toString()
    }

    private suspend fun wanted(context: Context): List<OrbitTaskEntity> {
        val active = com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context)
            .current().active?.takeUnless { it.deleting } ?: return emptyList()
        val dao = OrbitTaskRepository(context).dao
        return dao.tasks(active.id).filter { it.kind == KIND && it.state == "ACTIVE" &&
            (it.goalId.isEmpty() || dao.goal(it.goalId)?.state == "ACTIVE") }
    }

    @SuppressLint("MissingPermission")
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        val needsBaseline = wanted(context).any { readState(context, it.id)?.optString("source") != it.source }
        // GPS can take 30 seconds. Never hold the boundary lock while waiting: an existing
        // fence's broadcast has only a few seconds to record its transition.
        val fix = if (needsBaseline && permissionProblem(context) == null)
            currentLocation(context, System.currentTimeMillis()) else null
        lock.withLock {
        val app = context.applicationContext
        val store = prefs(app)
        val manager = app.getSystemService(LocationManager::class.java) ?: return@withLock
        val work = WorkManager.getInstance(app)
        // Re-read after the fix: a task can be paused/edited, or the active Orbit switched meanwhile.
        val wanted = wanted(app)
        val wantedKeys = wanted.map(::key).toSet()
        // Saved keys let a new process remove the previous process's obsolete registrations too.
        val previous = store.getStringSet("registered", emptySet()).orEmpty().toSet() + registered
        for (old in previous - wantedKeys) {
            remove(app, manager, old)
            val id = old.substringBefore('/')
            work.cancelUniqueWork(confirmation(id))
            if (wanted.none { it.id == id }) store.edit().remove("state:$id").remove("problem:$id").apply()
            else readState(app, id)?.let { state ->
                state.remove("candidate")
                store.edit().putString("state:$id", state.toString()).apply()
            }
        }
        if (wanted.isEmpty()) {
            store.edit().putStringSet("registered", emptySet()).apply()
            work.cancelUniqueWork(MAINTENANCE)
            return@withLock
        }
        work.enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrbitGeofenceRestoreWorker>(15, TimeUnit.MINUTES).build())
        val live = mutableSetOf<String>()
        for (task in wanted) {
            val token = key(task)
            try {
                check(permissionProblem(app) == null) { permissionProblem(app).orEmpty() }
                val definition = OrbitGeofenceDefinition.parse(task.source)
                var state = readState(app, task.id)
                if (state == null || state.optString("source") != task.source) {
                    val baseline = fix ?: error(app.getString(R.string.orbit_geofence_no_fix))
                    // Creation/resume establishes a baseline. Already being home is not an arrival.
                    state = JSONObject().put("source", task.source)
                        .put("inside", definition.distance(baseline.latitude, baseline.longitude) <= definition.radius)
                    check(store.edit().putString("state:${task.id}", state.toString()).commit())
                }
                if (state.has("candidate") && state.optLong("candidateVersion") != task.version) {
                    state.remove("candidate")
                    store.edit().putString("state:${task.id}", state.toString()).apply()
                }
                if (token !in registered) {
                    manager.addProximityAlert(definition.latitude, definition.longitude,
                        definition.radius.toFloat(), -1L, pending(app, token, "inner"))
                    manager.addProximityAlert(definition.latitude, definition.longitude,
                        definition.outerRadius.toFloat(), -1L, pending(app, token, "outer"))
                    registered += token
                }
                live += token
                store.edit().remove("problem:${task.id}").apply()
                // WorkManager and preferences cannot share a transaction. Recover a candidate
                // saved just before the process died, even when its first enqueue never persisted.
                enqueueConfirmation(app, task, state, ExistingWorkPolicy.KEEP)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                remove(app, manager, token)
                work.cancelUniqueWork(confirmation(task.id))
                store.edit().remove("state:${task.id}").putString("problem:${task.id}",
                    e.message ?: app.getString(R.string.orbit_geofence_unavailable)).apply()
            }
        }
        store.edit().putStringSet("registered", live).apply()
        }
    }

    private fun readState(context: Context, id: String): JSONObject? =
        prefs(context).getString("state:$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun pending(context: Context, token: String, ring: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, OrbitGeofenceReceiver::class.java)
            .setAction("com.ai.assistance.operit.ORBIT_GEOFENCE")
            .setData(Uri.parse("orbit-geofence://boundary/$token/$ring")),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    @SuppressLint("MissingPermission")
    private fun remove(context: Context, manager: LocationManager, token: String) {
        for (ring in listOf("inner", "outer")) {
            val intent = pending(context, token, ring)
            runCatching { manager.removeProximityAlert(intent) }
            // Revoked location permission can refuse removal; cancelling the token still
            // invalidates callbacks and lets the system discard the old registration.
            intent.cancel()
        }
        registered -= token
    }

    /** The receiver only queues work; it never starts a model turn or holds a broadcast for GPS. */
    fun receive(context: Context, intent: Intent, finish: () -> Unit) {
        scope.launch {
            try { withTimeout(8_000) { boundary(context.applicationContext, intent) } }
            catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("OrbitGeofence", "Boundary callback could not be recorded")
            }
            finally { finish() }
        }
    }

    private suspend fun boundary(context: Context, intent: Intent) = lock.withLock {
        val parts = intent.data?.pathSegments ?: return@withLock
        if (parts.size != 3 || !intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return@withLock
        val id = parts[0]
        val task = OrbitTaskRepository(context).dao.task(id) ?: return@withLock
        if (task.kind != KIND || task.state != "ACTIVE" || task.version.toString() != parts[1]) return@withLock
        val state = readState(context, id)?.takeIf { it.optString("source") == task.source } ?: return@withLock
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val candidate: Boolean? = when {
            parts[2] == "inner" && entering -> true
            parts[2] == "outer" && !entering -> false
            else -> null
        }
        val work = WorkManager.getInstance(context)
        if (candidate == null) {
            // Leaving the inner ring cancels an unconfirmed arrival; entering the outer ring
            // cancels an unconfirmed departure. Neither changes the established side.
            val cancelling = (parts[2] == "inner" && !entering && state.optString("candidate") == "true") ||
                (parts[2] == "outer" && entering && state.optString("candidate") == "false")
            if (cancelling) {
                state.remove("candidate")
                prefs(context).edit().putString("state:$id", state.toString()).commit()
                work.cancelUniqueWork(confirmation(id))
            }
            return@withLock
        }
        if (candidate == state.optBoolean("inside")) return@withLock
        if (state.optString("candidate") == candidate.toString()) {
            enqueueConfirmation(context, task, state, ExistingWorkPolicy.KEEP)
            return@withLock
        }
        val stamp = System.currentTimeMillis()
        state.put("candidate", candidate.toString()).put("at", stamp).put("candidateVersion", task.version)
        check(prefs(context).edit().putString("state:$id", state.toString()).commit())
        enqueueConfirmation(context, task, state, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueConfirmation(context: Context, task: OrbitTaskEntity, state: JSONObject, policy: ExistingWorkPolicy) {
        if (!state.has("candidate") || state.optLong("candidateVersion") != task.version) return
        val stamp = state.optLong("at")
        WorkManager.getInstance(context).enqueueUniqueWork(confirmation(task.id), policy,
            OneTimeWorkRequestBuilder<OrbitGeofenceConfirmWorker>()
                .setInputData(workDataOf("task" to task.id, "version" to task.version, "at" to stamp))
                .setInitialDelay((stamp + CONFIRM_MS - System.currentTimeMillis()).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS).build())
    }

    internal suspend fun confirm(context: Context, id: String, version: Long, stamp: Long): Boolean {
        if (permissionProblem(context) != null) return true
        val eligible = OrbitTaskRepository(context).dao.task(id)
        if (eligible?.kind != KIND || eligible.state != "ACTIVE" || eligible.version != version) return true
        val initial = readState(context, id)
        if (initial?.optLong("at") != stamp || !initial.has("candidate") || initial.optLong("candidateVersion") != version) return true
        if (System.currentTimeMillis() < stamp + CONFIRM_MS) return false
        val fix = currentLocation(context, stamp + CONFIRM_MS) ?: return false
        return lock.withLock {
            val repository = OrbitTaskRepository(context)
            val task = repository.dao.task(id) ?: return@withLock true
            if (task.kind != KIND || task.state != "ACTIVE" || task.version != version || permissionProblem(context) != null)
                return@withLock true
            val settings = com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
            if (settings.activeOrbitId != task.orbitId || settings.active?.deleting != false) return@withLock true
            if (task.goalId.isNotEmpty() && repository.dao.goal(task.goalId)?.state != "ACTIVE") return@withLock true
            val state = readState(context, id) ?: return@withLock true
            if (state.optString("source") != task.source || state.optLong("at") != stamp || !state.has("candidate") ||
                state.optLong("candidateVersion") != version)
                return@withLock true
            val candidate = state.getString("candidate").toBoolean()
            val definition = OrbitGeofenceDefinition.parse(task.source)
            val actual = definition.side(definition.distance(fix.latitude, fix.longitude), fix.accuracy.toDouble())
            if (actual == null) return@withLock false
            if (actual == candidate && candidate != state.optBoolean("inside")) {
                if ((definition.transition == "ENTER") == candidate) {
                    // Durable queue first, preference second: replay after a crash has the same
                    // event id and cannot deliver the same transition twice.
                    repository.dao.enqueueGeofence(id, version, stamp.toString(), System.currentTimeMillis())
                }
                state.put("inside", candidate)
            }
            state.remove("candidate")
            check(prefs(context).edit().putString("state:$id", state.toString()).commit())
            true
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context, after: Long): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        fun usable(location: Location): Boolean = location.hasAccuracy() && location.accuracy <= 100f && location.time >= after &&
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..120_000_000_000L
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter(::usable).maxByOrNull { it.elapsedRealtimeNanos }?.let { return it }
        if (providers.isEmpty()) return null
        return withTimeoutOrNull(30_000) {
            withContext(Dispatchers.Main.immediate) {
                var registeredListener: LocationListener? = null
                try {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (usable(location) && continuation.isActive) continuation.resume(location)
                            }
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        }
                        registeredListener = listener
                        var started = false
                        for (provider in providers) {
                            if (!continuation.isActive) break
                            runCatching {
                                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                                started = true
                            }
                        }
                        if (!started && continuation.isActive) continuation.resume(null)
                    }
                } finally {
                    // Cleanup and registration share Main, so cancellation cannot remove the
                    // listener just before a provider registers it again.
                    registeredListener?.let { listener ->
                        runCatching { manager.removeUpdates(listener) }
                    }
                }
            }
        }
    }
}

class OrbitGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        OrbitGeofence.receive(context, intent) { pending.finish() }
    }
}

class OrbitGeofenceConfirmWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val confirmed = OrbitGeofence.confirm(applicationContext, inputData.getString("task") ?: return Result.success(),
            inputData.getLong("version", -1), inputData.getLong("at", -1))
        if (!confirmed) return Result.retry()
        OrbitTaskScheduler.reschedule(applicationContext)
        return Result.success()
    }
}

class OrbitGeofenceRestoreWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        OrbitTaskScheduler.reschedule(applicationContext)
        return Result.success()
    }
}
