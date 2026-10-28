# Low-latency backend (MediaMTX)

    docker compose up -d

Publish from the app:   rtmp://SERVER_IP:1935/live/mystream
LL-HLS (~2-3 s):        http://SERVER_IP:8888/live/mystream/index.m3u8
WebRTC (<1 s, browsers): http://SERVER_IP:8889/live/mystream  (WHEP)

Open ports 1935, 8888, 8889 (TCP) and 8189 (UDP). For production put it
behind HTTPS and add publish/read credentials (see MediaMTX docs: authInternalUsers).
