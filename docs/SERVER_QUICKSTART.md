# Server Quick Start

The Android app needs a Family Safety server. Parent and child phones must use **the same** server. Release builds
only connect over **HTTPS**.

If the app says *"Can't find the server"* or shows **Connect to your server first**, no reachable server is set.
Tap **Server** (top-right of the sign-in screen), enter your server's address, and tap **Save**. The app tests the
connection before saving.

## Easiest — free server on Render (no computer needed)

Follow **[FREE_SERVER_RENDER.md](FREE_SERVER_RENDER.md)**: sign up with GitHub, create a Blueprint from this repository,
wait for it to go Live, then enter the `https://…onrender.com` address in the app.

## Option A — try it from your computer (≈10 minutes)

Requirements: a computer with Docker, Java 21 and this repository.

```bash
./scripts/dev-up.sh            # creates .env with random secrets, builds and starts postgres + backend on 127.0.0.1:8080
```

Then give the server a public HTTPS address with a tunnel. Example with Cloudflare's free quick tunnel
(install `cloudflared` from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/):

```bash
cloudflared tunnel --url http://localhost:8080
# prints e.g.  https://random-words-1234.trycloudflare.com
```

On both phones: sign-in screen → **Server** → paste the `https://…trycloudflare.com` address → **Save** (it should say
*Connected to a Family Safety server*). Then register the parent, register the child and pair them.

Notes:
* The backend keeps `REQUIRE_HTTPS=true`; the tunnel terminates TLS and forwards `X-Forwarded-Proto: https`, which
  the backend accepts. This path was checked against the running backend; the Cloudflare tunnel itself was not
  exercised in the build environment.
* Quick-tunnel addresses change every time you restart `cloudflared`, so the phones then need the new address. For
  something permanent use a named tunnel or Option B.
* Your computer must stay on while the app is in use.

## Option B — a permanent server

Follow [DEPLOYMENT.md](DEPLOYMENT.md): a small VPS with Docker, a DNS name, and `docker compose up -d` (Caddy obtains
a TLS certificate automatically). Then use `https://<your-domain>` in the app, or build the APK with it as the default:
`./scripts/build-apk.sh -Pfamilysafety.apiBaseUrl=https://<your-domain>/`.

## Option C — debug build on the same Wi-Fi (developers)

Debug builds (`./gradlew installDebug`) also accept `http://` addresses, so you can enter `http://<computer-LAN-IP>:8080`
in **Server**. Start the backend with `./scripts/dev-up.sh --emulator` (sets `REQUIRE_HTTPS=false`) and allow port
8080 through the computer's firewall. Never use plain HTTP for real family data.
