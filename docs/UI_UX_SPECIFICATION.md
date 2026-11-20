# UI/UX Specification

## Design language
* Material 3, Jetpack Compose. Teal primary (#00696E light / #4FD8DF dark), slate secondary, coral tertiary for
  "attention" states (e.g. over-limit bars, sharing banner). Dynamic colour (Material You) on Android 12+.
* Light and dark themes follow the system setting; windows background set in XML themes to avoid flashes.
* Content constrained to 640 dp (`CenteredContent`) so tablets/landscape stay readable; edge-to-edge enabled.
* Tone: calm, plain language, no surveillance vocabulary. The child is addressed directly and honestly.

## Shared patterns
| Pattern | Component | Behaviour |
|---|---|---|
| Loading | `LoadingState` | Spinner + text; announced via content description |
| Empty | `EmptyState` | Icon, title, explanation, optional primary action |
| Error | `ErrorState` | Friendly message mapped from backend code + "Try again" |
| Sensitive action | `ConfirmDialog` | Explicit title/consequence; destructive confirm in error colour |
| Feedback | Snackbar | Results of actions (sent, saved, errors) |
| Live status | `liveRegion` semantics | Screen-share status and sharing banner are announced by TalkBack |
| Charts | `BarChart` | Full data exposed as content description; limit drawn as dashed line |

## Navigation
* `AppRoot` routes by auth state & role: Splash → Auth → Parent root | Child pairing | Child root.
* **Parent bottom bar:** Home · Devices · Alerts (badge = unacknowledged count) · Settings.
* **Child bottom bar:** Home · Activity · Safety · Privacy.
* Notification deep links: alerts → Alerts/Safety; share request → Consent; usage access → Permissions.

## Screens

### Shared
**Sign in / Register** (redesigned in 1.1.0) — soft primary-container gradient backdrop; circular brand badge
with shield, title and tagline "Safety built on trust and consent"; a **Server** button (top-right, shows the current
host). Form in an elevated card with **Sign in | Create account** tabs (animated swap). Registration adds the
**Parent | Child** segmented selector (icons, animated explanation) and name. Fields have leading icons and keyboard
actions (Next → next field, Done → submit). Live "At least 12 characters" check turns primary with a tick. Errors appear
in an animated error-container banner; transport errors include a **Server settings** action. A privacy note sits under
the card. If no real server is configured, a tertiary **Connect to your server first** card is shown above the form.

**Server settings sheet** — address field (URL keyboard, Go = save), helper text (https only in release), **Test
connection** and **Save** (save only after a successful health check), **Use default**, status line announced via live
region (checking / connected / specific failure), pointer to SERVER_QUICKSTART.

**Motion** — role switches cross-fade with a slight scale; screens enter with fade + short horizontal slide; usage
bars animate to their value; status lines cross-fade.

### Parent
| Screen | Content | States |
|---|---|---|
| Dashboard | Greeting; stat cards (paired devices, open alerts); "Needs attention" (top 3 alerts); per-device cards (name, device, last seen, today's screen time, "Sharing" badge) | Pull-to-refresh; loading; empty → "Pair a device"; error with retry |
| Device list | Cards per child device; FAB "Pair device" | loading / empty / error |
| Pair device | Steps explanation; optional child name; create code → large monospace code (TalkBack reads characters individually), live countdown; realtime success screen | creating / showing / expired → new code / error |
| Device details | Name, OS, paired/last seen; today's total vs limit; top 5 apps bars; actions: Reports, App activity, View screen, Ask to check in, Limits & safety; **Remove device** (confirm dialog explaining sign-out & session end) | loading / error |
| Screen-time analytics | Segmented Week/Month; Total + Daily average; bar chart with limit line (over-limit bars in tertiary colour); top apps | loading / empty / error |
| Application activity | Segmented Today/7/30 days; ranked list: rank, label, opens, days used, last used, duration | loading / empty / error |
| Screen viewer | Explanation of consent model; Request button → "Waiting for approval" (spinner + Cancel) → "Connecting" → Live video (aspect-fit, black letterbox) with red dot "Your child can see that sharing is active" and **Stop viewing** (confirm). End states explain why (declined, expired, stopped by child, time limit, revoked, connection lost) with "Request again" | all phases |
| Alerts | Cards with title, message, severity · relative time; urgent unacknowledged in error container colour; "Mark as seen" | loading / empty / error |
| Limits & safety | Daily limit segmented (Off/1h/2h/3h/4h), limit alerts toggle, bedtime toggle (21:00–07:00) + bedtime alerts, screen-sharing allowed, retention (30/90/180/365 d), Save | loading / saving / saved snackbar / error |
| Settings | Account & family; "How Family Safety works" (what is/isn't collected); Sign out (confirm) | — |

### Child
| Screen | Content |
|---|---|
| Pairing | Instructions; code field (auto-uppercase, formats XXXX-XXXX, monospace); **"What your parents can see / can never see"** card; Connect → confirm dialog; Sign out |
| Dashboard | Greeting; sharing banner when active; family connection card with Usage access / Notifications status; "Review permissions" if missing; today's screen time; **I'm OK — check in** and **Get help** |
| Permissions | Cards: Usage access (purpose, how to turn off, parents told), Notifications (request/allow), Screen capture (per-session, explained), Background activity (battery settings) — each with current status and an action |
| Screen-sharing consent | Who is asking; bullet list: Android asks again, notification visible, stop anytime, not recorded, no sound, 30-min limit; **Approve / Decline**. Then system dialog → Connecting → "Your screen is being shared" + **Stop sharing** (confirm). End state explains outcome |
| Monitoring status | What's shared right now, last shared time, screen-sharing state, live connection; "Share activity summary now"; **Never collected** list |
| Activity summary | Today (total + top apps from on-device data), last 7 days chart "as your parents see it" |
| Safety | Check in; Report a concern (dialog: category chips + optional text, "Only what you write is sent"); **I need help** (confirm; urgent); emergency-number reminder; list of alerts about/for the child with "Check in now" for parent requests |
| Privacy | Signed-in identity; what is shared; the settings parents chose (limit, bedtime, sharing, retention); Manage permissions; **Ask to disconnect** (confirm); Sign out (confirm) |

## Accessibility checklist
* All icons that convey meaning have content descriptions; decorative icons use `null`.
* Touch targets ≥ 48 dp (Material defaults); switches carry their label as content description.
* Status changes (viewer phase, sharing banner, auth errors) use live regions.
* Headings use `semantics { heading() }` (`SectionHeader`).
* Charts expose values textually; colour is never the only signal (labels + values present).
* Text uses Material type scale (scales with system font size); layouts scroll vertically.
* Pairing code is read character-by-character.

## Responsive behaviour
* Single-column layouts with max width 640 dp, centred on large screens.
* Lists are lazy; analytics chart hides per-bar labels for > 7 bars (month view) but keeps the accessible description.
