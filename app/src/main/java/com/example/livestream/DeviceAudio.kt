package com.example.livestream

// Shares the phone's own sound (music, videos, games) together with the shared screen.
// LiveKit's ScreenAudioCapturer is looked up by name so a different library version cannot break the build.
// Returns "ok" or a short reason it did not start.
fun startDeviceAudio(screenTrack: Any?, gain: Float = 1.0f): String {
    if (screenTrack == null) return "no screen track"
    if (android.os.Build.VERSION.SDK_INT < 29) return "needs Android 10 or newer"
    return try {
        val cls = Class.forName("io.livekit.android.audio.ScreenAudioCapturer")
        val create = cls.methods.firstOrNull { it.name == "createFromScreenShareTrack" && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val capturer = if (create != null) create.invoke(null, screenTrack) else {
            val comp = cls.getField("Companion").get(null)
            comp.javaClass.methods.first { it.name == "createFromScreenShareTrack" }.invoke(comp, screenTrack)
        }
        if (capturer == null) return "capturer not created"
        try { capturer.javaClass.getMethod("setGain", Float::class.javaPrimitiveType).invoke(capturer, gain) } catch (e: Exception) {}
        "ok"
    } catch (e: ClassNotFoundException) {
        "not in this LiveKit version"
    } catch (e: Exception) {
        (e.cause ?: e).let { it.javaClass.simpleName + ": " + it.message }
    }
}
