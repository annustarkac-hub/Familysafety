# Free server on Render (no computer needed)

This creates your own Family Safety server with HTTPS on [Render](https://render.com), using the `render.yaml`
blueprint in this repository. You can do it from a phone browser. Time: about 10–15 minutes (most of it is Render
building the server).

## Steps

1. Open **https://render.com** → **Get Started** → **Sign up with GitHub** (use the GitHub account that owns this
   repository). No credit card is needed for the free plan.
2. When Render asks for repository access, allow access to this repository (`familysafety`).
3. In the Render dashboard: **New +** → **Blueprint**.
4. Choose the `familysafety` repository.
   * Choose branch **`claude/family-safety-android-app-frwqc1`** (or `main`, once that branch has been merged).
5. Render shows what it will create: **family-safety-api** (web service, Free) and **family-safety-db** (PostgreSQL,
   Free). Click **Apply** / **Deploy Blueprint**.
6. Wait until **family-safety-api** shows **Live** (first build ≈ 5–10 minutes).
7. Open the service; copy its address at the top, e.g. `https://family-safety-api-ab12.onrender.com`.
   Check it in the browser: `https://family-safety-api-ab12.onrender.com/actuator/health` should show `{"status":"UP"…}`.
8. In the app, on **both phones**: sign-in screen → **Server** (top right) → paste the address → **Save**.
   It should say *Connected to a Family Safety server*.
9. Parent phone: **Create account → Parent**. Child phone: **Create account → Child**. Then pair them
   (parent: Devices → Pair device → code; child: enter the code).

Secrets (`JWT_SECRET`, `PAIRING_PEPPER`) are generated randomly by Render; the database password is created by Render.
Nothing secret is stored in the repository.

## Things to know about the free plan

| Limitation | Effect | What to do |
|---|---|---|
| Server **sleeps after ~15 min without traffic** | The first request afterwards takes up to ~1 minute (the app waits up to 75 s and says so) | Just wait the first time; or upgrade the web service to a paid instance |
| **Free PostgreSQL expires** after a limited period (Render's policy; currently 30 days) | Accounts and data are deleted when it expires | Upgrade the database to a paid plan, or point `DATABASE_HOST/PORT/NAME/USERNAME/PASSWORD` at another PostgreSQL (e.g. a free Neon database) in the service's Environment settings |
| Limited CPU/RAM (512 MB) | Fine for one family (tested at ~300 MB) | — |
| Realtime events only while the server is awake | Screen-share requests need the server awake (it wakes on the first app request) | — |

Check Render's current pricing and limits on their website; they can change.

## Troubleshooting

* **Deploy failed** → open the service's **Logs**. A Gradle "Dependency verification failed" message means the
  repository's checksum file is out of date (see BUILD_GUIDE.md). A database connection error usually resolves once
  the database finishes provisioning — use **Manual Deploy → Deploy latest commit**.
* **App says the server refused / not found** → re-check the address (must be the `https://…onrender.com` one, no
  `/actuator/health` at the end).
* **"The server requires HTTPS"** → use `https://`, not `http://`.

## What was verified

The exact container from `backend/Dockerfile` was built from an empty cache and run with the same settings as this
blueprint (database host/port/name split, `REQUIRE_HTTPS=true`, `FORWARD_HEADERS_STRATEGY=framework`, 512 MB memory
limit). All 10 steps of `scripts/e2e-smoke.py --forwarded-https` passed and plain HTTP was refused. Render itself could
not be accessed from the build environment, so the blueprint has not been deployed on Render by us.
