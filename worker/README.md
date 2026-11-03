# Setup (all free, no server)
1. livekit.io -> sign up -> create project -> Settings > Keys. Note API key, secret, and the wss:// URL.
2. dash.cloudflare.com -> Workers & Pages -> Create -> Hello World -> Deploy -> Edit code -> paste worker.js -> Deploy.
3. Worker Settings > Variables and Secrets, add:
   LK_KEY = your API key
   LK_SECRET = your API secret (type: Secret)
   LK_WS = wss://YOUR-PROJECT.livekit.cloud
   LK_HOST = https://YOUR-PROJECT.livekit.cloud
4. In the app, set Server URL to your worker address (https://NAME.SUBDOMAIN.workers.dev).

# Accounts: email + password + emailed code (optional)
Without these steps the app works as guest only.
A. Account storage: Cloudflare -> Storage & Databases -> KV -> Create namespace. Worker -> Settings -> Bindings -> Add -> KV namespace, variable name DB.
B. Email sending (free, no domain needed): brevo.com -> sign up -> Senders, Domains -> add and verify one sender email address (the code goes from it).
   Then SMTP & API -> API Keys -> Generate. In the Worker add variables: BREVO_KEY = that API key (type Secret), MAIL_FROM = the verified sender email.
C. Redeploy worker.js. In the app tap the 👤 button on Home -> Register (email, password, display name) -> enter the 6-digit code from your email.
