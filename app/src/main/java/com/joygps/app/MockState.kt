package com.joygps.app

/**
 * Shared, in-process state between the UI (activity + floating joystick) and the
 * mock-location service. All listener calls happen on the main thread.
 */
object MockState {
    /** Top walking speed when the joystick is pushed all the way to the edge. */
    const val MAX_SPEED_KMH = 10.0
    const val MAX_SPEED_MS = MAX_SPEED_KMH / 3.6

    @Volatile var running = false
    @Volatile var overlayVisible = false

    /** Current mocked position. */
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0

    /** The point mocking started from (normally your real GPS fix). */
    @Volatile var originLat = Double.NaN
    @Volatile var originLon = Double.NaN

    @Volatile var speedKmh = 0.0
    @Volatile var bearingDeg = 0.0

    /** Joystick vector: x = east, y = north, length 0..1 (stretch of the stick). */
    @Volatile var inputX = 0f
        private set
    @Volatile var inputY = 0f
        private set

    /** One-shot error message for the UI to show. */
    @Volatile var lastError: String? = null

    private val listeners = mutableListOf<() -> Unit>()

    fun setInput(x: Float, y: Float) {
        inputX = x
        inputY = y
    }

    fun addListener(l: () -> Unit) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
