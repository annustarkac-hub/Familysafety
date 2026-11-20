# Android Manifest & Permissions

Merged from `app/src/main/AndroidManifest.xml`, `data/src/main/AndroidManifest.xml` and
`feature-child/src/main/AndroidManifest.xml`. Application ID: `com.familysafety.app` (debug: `.debug` suffix).
`minSdk 26`, `targetSdk 35`.

## Permissions

| Permission | Type | Used by | Purpose | Granted how / revocable |
|---|---|---|---|---|
| `INTERNET` | normal | both roles | REST API, WebSocket, WebRTC media | install-time |
| `ACCESS_NETWORK_STATE` | normal | both | WorkManager network constraint | install-time |
| `POST_NOTIFICATIONS` | runtime (API 33+) | both | Safety alerts, screen-share requests, "sharing active", usage-access warnings | Requested at first launch; revocable in Settings. App checks before every notify. |
| `PACKAGE_USAGE_STATS` | special app-op ("Usage access") | child | Per-app foreground time via UsageStatsManager | **User enables in Settings → Usage access** (deep-linked from the Permissions screen). Never auto-granted. Revoking stops collection; parents are told once per day. Declared with `tools:ignore="ProtectedPermissions"` because it is a protected permission by design. |
| `FOREGROUND_SERVICE` | normal | child | Run the capture service in the foreground | install-time |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | normal (API 34+) | child | Required for a foreground service of type `mediaProjection` | install-time; the capture itself still needs per-session MediaProjection consent |
| `RECEIVE_BOOT_COMPLETED` | normal | both | WorkManager reschedules periodic sync after reboot (declared by WorkManager, listed for transparency) | install-time |
| `WAKE_LOCK` | normal | — | Added by WorkManager's merged manifest | install-time |
| `com.familysafety.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature (self-defined) | — | Added by androidx.core to protect non-exported dynamic receivers | automatic; not usable by other apps |

The list above was checked against the merged release manifest
(`android/app/build/intermediates/merged_manifest/release/.../AndroidManifest.xml`).

**Not requested (by design):** `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, `RECORD_AUDIO`, `READ_CONTACTS`, location
permissions, `BIND_ACCESSIBILITY_SERVICE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `QUERY_ALL_PACKAGES`,
`SYSTEM_ALERT_WINDOW`, device-admin. Screen capture uses MediaProjection, which always shows Android's consent dialog.

## Package visibility
`<queries>` for `MAIN/LAUNCHER` and `MAIN/HOME` intents lets the app resolve labels of launchable apps and identify the
launcher (excluded from usage totals) without `QUERY_ALL_PACKAGES`.

## Components

| Component | Exported | Notes |
|---|---|---|
| `.MainActivity` | yes (launcher) | `singleTop`; receives `com.familysafety.app.DESTINATION` extra from notifications |
| `feature.child.ScreenCaptureService` | **no** | `foregroundServiceType="mediaProjection"`; starts only with a MediaProjection result for the currently approved session; ongoing notification with Stop |
| `androidx.startup.InitializationProvider` | no | WorkManager's default initializer removed (Hilt worker factory via `Configuration.Provider`) |
| WorkManager / Room internal components | no | From libraries |

## Application attributes
| Attribute | Value | Why |
|---|---|---|
| `allowBackup` | false | Tokens are bound to a device Keystore key; no cloud backup of family data |
| `fullBackupContent` | false | (API ≤ 30) |
| `dataExtractionRules` | `@xml/data_extraction_rules` (exclude everything) | (API 31+) cloud backup and device transfer |
| `networkSecurityConfig` | release: cleartext off, system CAs only; debug overlay: cleartext only to 10.0.2.2 / localhost / 127.0.0.1 | TLS-only in release |
| `supportsRtl` | true | |

## Notification channels
| ID | Importance | Content |
|---|---|---|
| `safety_alerts` | high | Check-ins, help requests, limit/bedtime alerts |
| `screen_share_requests` | high | "<Parent> wants to see your screen" (times out with the request) |
| `screen_share_active` | low (ongoing) | "Sharing your screen" + Stop action, for the whole session |
| `monitoring_status` | default | Usage access turned off |
