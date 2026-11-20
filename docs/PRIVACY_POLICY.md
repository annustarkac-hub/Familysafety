# Privacy Policy — Family Safety (template)

> This is a product privacy policy **template** describing what the software does. The operator who deploys the
> backend is the data controller and must adapt this text (identity, contact, legal bases, jurisdiction, children's
> privacy law such as COPPA/GDPR-K/UK AADC) and have it reviewed by counsel before publication.

_Last updated: 2026-11-20_

## 1. Who this is for
Family Safety helps a parent or guardian and their child stay safe online **with the child's knowledge and consent**.
The app shows the child what is shared at all times and asks the child to approve every live screen view.

## 2. What we collect

| Data | From | Purpose | Retention |
|---|---|---|---|
| Account: email, display name, role, password hash (Argon2id) | Both | Sign-in, identifying family members | Until account deletion |
| Device info: device name, manufacturer, model, Android version, app version, random installation ID | Both | Show which device is paired; session management | Until account deletion |
| App usage summaries: app package name & label, minutes in foreground, number of opens, last used time, first/last activity per day, time zone | Child (only after the child grants *Usage access*) | Screen-time reports and configured alerts for the parent and the child | Parent-configured, default 90 days (30–365) |
| Safety events: check-ins, reports (category + text the child writes), help requests, check-in requests, limit/bedtime alerts, pairing/revocation notices | Both / system | Notify family members | 180 days |
| Screen-sharing session metadata: who requested, when, approved/ended, reason | System | Show status; security audit | 90 days after end |
| Security logs: sign-ins, failed attempts, pairing, revocations, IP address | System | Protect accounts, investigate abuse | 365 days |

### What we never collect
Message or chat content, emails, call logs or call audio, SMS, keystrokes, notification contents, browsing history or
URLs, photos/files, contacts, location, microphone audio, or screen recordings. **Live screen sharing is transmitted
directly between the two devices (or through an encrypted relay) and is never recorded or stored.**

## 3. How screen sharing works
1. A parent sends a request. 2. The child sees who is asking and can approve or decline. 3. Android shows its own
screen-capture permission dialog. 4. While sharing, a notification is always visible and the child can stop at any
time. 5. Sessions end automatically after 30 minutes. Video is encrypted end-to-end with WebRTC (DTLS-SRTP); the server
only relays connection set-up messages.

## 4. Sharing
Data is visible only to members of the same family as described above. We do not sell data, show ads, or share data
with third parties except service providers that host the service (e.g. cloud hosting) under contract, or where
required by law. If a STUN/TURN provider is used, it sees IP addresses needed to connect the devices.

## 5. Children's rights and controls
A child can at any time: see what is shared (Privacy and Monitoring status screens), turn off Usage access in Android
settings (parents are told it was turned off), decline or stop screen sharing, and ask to be disconnected.

## 6. Security
TLS for all traffic, encrypted token storage on the phone (Android Keystore), hashed passwords, short-lived tokens,
rate limiting and audit logging. See SECURITY.md.

## 7. Your rights
Depending on your location you may have rights to access, correct, delete or export your data and to object to or
restrict processing. Contact the operator at `<privacy contact>`. Parents/guardians can exercise rights on behalf of
their child.

## 8. Deletion
Removing a device stops all collection from it immediately. Usage history is deleted automatically according to the
retention setting. Account deletion requests are handled by the operator (self-service deletion is on the roadmap).

## 9. Changes
We will notify users in the app before material changes take effect.

## 10. Contact
`<Operator name, address, privacy contact email>`
