package com.joygps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Foreground service that owns the mock location providers and moves the mocked
 * position according to the joystick input in [MockState].
 */
class MockLocationService : Service() {

    private lateinit var lm: LocationManager
    private lateinit var nm: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val activeProviders = mutableListOf<String>()
    private var lastTickNanos = 0L
    private var altitudeM = 50.0
    private var tickCount = 0
    private var overlay: JoystickOverlay? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (MockState.running) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while JoyGPS is mocking your location"
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            else -> if (!MockState.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay(refresh = false)
        overlay = null
        teardownProviders()
        MockState.running = false
        MockState.speedKmh = 0.0
        MockState.setInput(0f, 0f)
        MockState.notifyListeners()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun handleStart(intent: Intent) {
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        altitudeM = intent.getDoubleExtra(EXTRA_ALT, altitudeM)

        // Must be called promptly after startForegroundService().
        if (!goForeground()) {
            stopSelf()
            return
        }
        if (lat.isNaN() || lon.isNaN()) {
            if (!MockState.running) fail("No start coordinates were given.")
            return
        }

        MockState.lat = lat
        MockState.lon = lon

        if (MockState.running) {
            // Already mocking: this is a "jump to coordinates".
            pushLocation(MockState.speedKmh / 3.6)
            MockState.notifyListeners()
            updateNotification()
            return
        }

        if (!setupProviders()) {
            fail(
                "JoyGPS isn't allowed to mock location.\n\n" +
                    "Open Developer options → Select mock location app → JoyGPS, then try again."
            )
            return
        }

        MockState.running = true
        MockState.speedKmh = 0.0
        MockState.setInput(0f, 0f)
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        handler.removeCallbacks(tick)
        handler.post(tick)
        MockState.notifyListeners()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        MockState.lastError = message
        MockState.running = false
        MockState.notifyListeners()
        stopSelf()
    }

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        MockState.lastError = "Couldn't start the location service: ${e.message}"
        MockState.notifyListeners()
        false
    }

    // ---------------------------------------------------------------- providers

    private fun providerNames(): List<String> {
        val names = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) names += LocationManager.FUSED_PROVIDER
        return names
    }

    /** Returns false if the app isn't selected as the mock location app. */
    @Suppress("DEPRECATION")
    private fun setupProviders(): Boolean {
        activeProviders.clear()
        for (name in providerNames()) {
            try {
                try {
                    lm.removeTestProvider(name)
                } catch (_: Exception) {
                    // Not registered yet: fine.
                }
                lm.addTestProvider(
                    name,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ name == LocationManager.GPS_PROVIDER,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
                lm.setTestProviderEnabled(name, true)
                activeProviders += name
            } catch (e: SecurityException) {
                Log.w(TAG, "Not the selected mock location app", e)
                teardownProviders()
                return false
            } catch (e: Exception) {
                // Some devices don't allow overriding every provider; keep going with the rest.
                Log.w(TAG, "Couldn't mock provider $name", e)
            }
        }
        return activeProviders.isNotEmpty()
    }

    private fun teardownProviders() {
        for (name in activeProviders) {
            try {
                lm.setTestProviderEnabled(name, false)
            } catch (_: Exception) {
            }
            try {
                lm.removeTestProvider(name)
            } catch (_: Exception) {
            }
        }
        activeProviders.clear()
    }

    // ---------------------------------------------------------------- movement

    private fun step() {
        if (!MockState.running) return

        val now = SystemClock.elapsedRealtimeNanos()
        val dt = ((now - lastTickNanos) / 1e9).coerceIn(0.0, 1.0)
        lastTickNanos = now

        val x = MockState.inputX.toDouble()
        val y = MockState.inputY.toDouble()
        val stretch = hypot(x, y).coerceAtMost(1.0)
        // Speed is proportional to how far the stick is pushed: 0 at centre, 10 km/h at the rim.
        val speedMs = stretch * MockState.MAX_SPEED_MS

        if (stretch > 0.0) {
            val bearing = atan2(x, y) // radians, 0 = north, clockwise
            MockState.bearingDeg = (Math.toDegrees(bearing) + 360.0) % 360.0

            val distance = speedMs * dt
            val latRad = Math.toRadians(MockState.lat)
            val dLat = distance * cos(bearing) / EARTH_RADIUS_M
            val dLon = distance * sin(bearing) / (EARTH_RADIUS_M * cos(latRad).coerceAtLeast(1e-6))

            MockState.lat = (MockState.lat + Math.toDegrees(dLat)).coerceIn(-89.9999, 89.9999)
            var newLon = MockState.lon + Math.toDegrees(dLon)
            if (newLon > 180.0) newLon -= 360.0
            if (newLon < -180.0) newLon += 360.0
            MockState.lon = newLon
        }
        MockState.speedKmh = speedMs * 3.6

        if (!pushLocation(speedMs)) return

        overlay?.update()
        MockState.notifyListeners()
        if (++tickCount % TICKS_PER_NOTIFICATION == 0) updateNotification()
    }

    /** Sends the current position to every mocked provider. Returns false if mocking was revoked. */
    private fun pushLocation(speedMs: Double): Boolean {
        val nowMs = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        for (name in activeProviders.toList()) {
            val loc = Location(name).apply {
                latitude = MockState.lat
                longitude = MockState.lon
                altitude = altitudeM
                accuracy = 3f
                speed = speedMs.toFloat()
                bearing = MockState.bearingDeg.toFloat()
                time = nowMs
                elapsedRealtimeNanos = nowNanos
                verticalAccuracyMeters = 4f
                speedAccuracyMetersPerSecond = 0.3f
                bearingAccuracyDegrees = 5f
            }
            try {
                lm.setTestProviderLocation(name, loc)
            } catch (e: SecurityException) {
                fail("Mock location permission was revoked (Developer options → Select mock location app).")
                return false
            } catch (e: Exception) {
                Log.w(TAG, "setTestProviderLocation($name) failed", e)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- floating joystick

    private fun showOverlay() {
        if (!MockState.running) return
        if (!Settings.canDrawOverlays(this)) {
            MockState.lastError = "Allow \"Display over other apps\" for JoyGPS to use the floating joystick."
            MockState.notifyListeners()
            return
        }
        val o = overlay ?: JoystickOverlay(this) { hideOverlay() }.also { overlay = it }
        o.show()
        MockState.overlayVisible = true
        updateNotification()
        MockState.notifyListeners()
    }

    private fun hideOverlay(refresh: Boolean = true) {
        overlay?.hide()
        MockState.overlayVisible = false
        if (refresh) {
            updateNotification()
            MockState.notifyListeners()
        }
    }

    // ---------------------------------------------------------------- notification

    private fun updateNotification() {
        if (MockState.running) nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val toggleOverlay = PendingIntent.getService(
            this, 2,
            Intent(this, MockLocationService::class.java)
                .setAction(if (MockState.overlayVisible) ACTION_HIDE_OVERLAY else ACTION_SHOW_OVERLAY),
            flags,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_joystick)
        val text = String.format(
            Locale.US, "%.6f, %.6f · %.1f km/h", MockState.lat, MockState.lon, MockState.speedKmh
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_joystick)
            .setContentTitle("Mock GPS active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
            .addAction(
                Notification.Action.Builder(
                    icon,
                    if (MockState.overlayVisible) "Hide joystick" else "Show joystick",
                    toggleOverlay,
                ).build()
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.joygps.app.action.START"
        const val ACTION_STOP = "com.joygps.app.action.STOP"
        const val ACTION_SHOW_OVERLAY = "com.joygps.app.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.joygps.app.action.HIDE_OVERLAY"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALT = "alt"

        private const val TAG = "JoyGPS"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 200L // 5 location updates per second
        private const val TICKS_PER_NOTIFICATION = 5
        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
