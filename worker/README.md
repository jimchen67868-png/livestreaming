# Setup (all free, no server)
1. livekit.io -> sign up -> create project -> Settings > Keys. Note API key, secret, and the wss:// URL.
2. dash.cloudflare.com -> Workers & Pages -> Create -> Hello World -> Deploy -> Edit code -> paste worker.js -> Deploy.
3. Worker Settings > Variables and Secrets, add:
   LK_KEY = your API key
   LK_SECRET = your API secret (type: Secret)
   LK_WS = wss://YOUR-PROJECT.livekit.cloud
   LK_HOST = https://YOUR-PROJECT.livekit.cloud
4. In the app, set Server URL to your worker address (https://NAME.SUBDOMAIN.workers.dev).
