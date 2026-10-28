# LiveStream (Kotlin + Jetpack Compose)

- Watch tab: plays any HLS (.m3u8) URL with Media3 ExoPlayer.
- Go Live tab: camera + mic -> RTMP using RootEncoder.

Open the folder in Android Studio, sync, run. For broadcasting you need an RTMP
ingest, e.g. Twitch / YouTube Live (copy server URL + key as rtmp://server/app/key)
or your own nginx-rtmp / MediaMTX server. Viewers use that service's HLS playback URL.
