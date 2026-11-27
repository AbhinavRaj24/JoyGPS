package com.joygps.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var lm: LocationManager
    private lateinit var tvOrigin: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var btnStart: Button
    private lateinit var btnJump: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnMaps: Button
    private lateinit var btnUseOrigin: Button
    private lateinit var joystick: JoystickView

    private val handler = Handler(Looper.getMainLooper())

    /** Latest real (non-mocked) fix. */
    private var realFix: Location? = null
    private var listeningForReal = false
    private var fillingFields = false
    private var userEditedFields = false
    private var resumed = false

    private val uiListener: () -> Unit = { refreshUi() }

    // Explicit object (not a lambda): on Android < 11 the other methods are abstract.
    private val realLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onRealFix(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lm = getSystemService(LocationManager::class.java)

        tvOrigin = findViewById(R.id.tvOrigin)
        tvStatus = findViewById(R.id.tvStatus)
        etLat = findViewById(R.id.etLat)
        etLon = findViewById(R.id.etLon)
        btnStart = findViewById(R.id.btnStart)
        btnJump = findViewById(R.id.btnJump)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnMaps = findViewById(R.id.btnMaps)
        btnUseOrigin = findViewById(R.id.btnUseOrigin)
        joystick = findViewById(R.id.joystick)

        applySystemBarInsets(findViewById(R.id.root))

        joystick.onMove = { x, y -> MockState.setInput(x, y) }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!fillingFields) userEditedFields = true
            }
        }
        etLat.addTextChangedListener(watcher)
        etLon.addTextChangedListener(watcher)

        btnStart.setOnClickListener { if (MockState.running) stopMocking() else startMocking() }
        btnJump.setOnClickListener { jumpToFields() }
        btnUseOrigin.setOnClickListener {
            val fix = realFix
            if (fix == null) toast("No real GPS fix yet") else fillFields(fix.latitude, fix.longitude)
        }
        btnOverlay.setOnClickListener { toggleOverlay() }
        btnMaps.setOnClickListener { openInMaps() }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        MockState.addListener(uiListener)
        if (!MockState.running) startRealLocationUpdates()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        MockState.removeListener(uiListener)
        stopRealLocationUpdates()
        if (!MockState.overlayVisible) MockState.setInput(0f, 0f)
    }

    // ---------------------------------------------------------------- permissions

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasLocationPermission()) {
            startRealLocationUpdates()
        } else {
            tvOrigin.text = "Location permission is needed to read your real GPS position."
        }
    }

    private fun isMockAppSelected(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showMockSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Set JoyGPS as mock location app")
            .setMessage(
                "Android only lets the app chosen in Developer options fake your location.\n\n" +
                    "1. Settings → About phone → tap \"Build number\" 7 times (enables Developer options).\n" +
                    "2. Developer options → \"Select mock location app\" → JoyGPS.\n" +
                    "3. Come back and tap Start."
            )
            .setPositiveButton("Open Developer options") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    } catch (_: Exception) {
                        toast("Open Settings manually")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- real location

    private fun startRealLocationUpdates() {
        if (!resumed || !hasLocationPermission() || MockState.running || listeningForReal) return
        try {
            // Show the last known position straight away while a fresh fix comes in.
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null && realFix == null) onRealFix(last, fresh = false)

            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, realLocationListener, Looper.getMainLooper())
                    any = true
                }
            }
            listeningForReal = any
            if (!any) tvOrigin.text = "Location is turned off. Turn it on in quick settings."
            else if (realFix == null) tvOrigin.text = "Getting GPS fix…"
        } catch (e: SecurityException) {
            tvOrigin.text = "Location permission is needed."
        }
    }

    private fun stopRealLocationUpdates() {
        if (!listeningForReal) return
        lm.removeUpdates(realLocationListener)
        listeningForReal = false
    }

    private fun onRealFix(loc: Location, fresh: Boolean = true) {
        if (MockState.running) return // never treat mocked fixes as "real"
        val prev = realFix
        // Prefer GPS fixes; accept network only if we have nothing better/recent.
        val accept = prev == null ||
            loc.provider == LocationManager.GPS_PROVIDER ||
            prev.provider != LocationManager.GPS_PROVIDER ||
            loc.time - prev.time > 15_000
        if (!accept) return
        realFix = loc

        val acc = if (loc.hasAccuracy()) String.format(Locale.US, " ±%.0f m", loc.accuracy) else ""
        val label = if (fresh) loc.provider else "last known"
        tvOrigin.text = String.format(Locale.US, "%.6f, %.6f%s  (%s)", loc.latitude, loc.longitude, acc, label)

        if (!userEditedFields) fillFields(loc.latitude, loc.longitude)
    }

    private fun fillFields(lat: Double, lon: Double) {
        fillingFields = true
        etLat.setText(String.format(Locale.US, "%.6f", lat))
        etLon.setText(String.format(Locale.US, "%.6f", lon))
        fillingFields = false
        userEditedFields = false
    }

    private fun readFields(): Pair<Double, Double>? {
        val lat = etLat.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val lon = etLon.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    // ---------------------------------------------------------------- actions

    private fun startMocking() {
        if (!hasLocationPermission()) {
            requestNeededPermissions()
            return
        }
        if (!isMockAppSelected()) {
            showMockSetupDialog()
            return
        }
        val coords = readFields()
        if (coords == null) {
            toast("Wait for a GPS fix, or enter a valid latitude/longitude")
            return
        }
        // Stop listening first so mocked fixes are never mistaken for the real one.
        stopRealLocationUpdates()

        MockState.originLat = coords.first
        MockState.originLon = coords.second
        val alt = realFix?.takeIf { it.hasAltitude() }?.altitude ?: 50.0

        startForegroundService(
            Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_START)
                .putExtra(MockLocationService.EXTRA_LAT, coords.first)
                .putExtra(MockLocationService.EXTRA_LON, coords.second)
                .putExtra(MockLocationService.EXTRA_ALT, alt)
        )
    }

    private fun stopMocking() {
        joystick.reset()
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
    }

    private fun jumpToFields() {
        if (!MockState.running) return
        val coords = readFields() ?: return toast("Enter a valid latitude/longitude")
        startForegroundService(
            Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_START)
                .putExtra(MockLocationService.EXTRA_LAT, coords.first)
                .putExtra(MockLocationService.EXTRA_LON, coords.second)
        )
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" for JoyGPS")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!MockState.running) {
            toast("Start mocking first")
            return
        }
        val action = if (MockState.overlayVisible) MockLocationService.ACTION_HIDE_OVERLAY
        else MockLocationService.ACTION_SHOW_OVERLAY
        startService(Intent(this, MockLocationService::class.java).setAction(action))
        if (action == MockLocationService.ACTION_SHOW_OVERLAY) toast("Floating joystick on — switch to any app")
    }

    private fun openInMaps() {
        val (lat, lon) = if (MockState.running) MockState.lat to MockState.lon
        else readFields() ?: return toast("No coordinates yet")
        val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(JoyGPS)", lat, lon, lat, lon))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("No maps app installed")
        }
    }

    // ---------------------------------------------------------------- UI

    private fun refreshUi() {
        val running = MockState.running

        MockState.lastError?.let {
            MockState.lastError = null
            AlertDialog.Builder(this).setTitle("JoyGPS").setMessage(it)
                .setPositiveButton("OK", null).show()
        }

        if (running) {
            stopRealLocationUpdates()
        } else if (!listeningForReal) {
            // Not mocking (just stopped, or failed to start): read the real position again
            // once the test providers have been removed.
            handler.postDelayed({ startRealLocationUpdates() }, 600)
        }

        btnStart.text = if (running) "Stop mocking" else "Start mocking"
        btnJump.isEnabled = running
        btnUseOrigin.isEnabled = realFix != null
        if (joystick.isEnabled != running) joystick.isEnabled = running
        btnOverlay.text = if (MockState.overlayVisible) "Hide floating joystick" else "Floating joystick"

        tvStatus.text = if (running) {
            val dist = FloatArray(1)
            Location.distanceBetween(MockState.originLat, MockState.originLon, MockState.lat, MockState.lon, dist)
            String.format(
                Locale.US,
                "Mocking: %.6f, %.6f\nSpeed: %.1f / %.0f km/h   Heading: %03.0f° %s\nDistance from start: %s",
                MockState.lat, MockState.lon,
                MockState.speedKmh, MockState.MAX_SPEED_KMH,
                MockState.bearingDeg, cardinal(MockState.bearingDeg),
                formatDistance(dist[0]),
            )
        } else {
            "Not mocking. Tap Start to begin at the start point, then use the joystick."
        }
    }

    private fun cardinal(deg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    private fun formatDistance(m: Float): String =
        if (m < 1000f) String.format(Locale.US, "%.0f m", m) else String.format(Locale.US, "%.2f km", m / 1000f)

    private fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_PERMS = 1
    }
}
