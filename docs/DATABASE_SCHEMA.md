# Database Schema

PostgreSQL 16, managed by Flyway (`backend/src/main/resources/db/migration`). Hibernate runs with
`ddl-auto=validate`, so the entity model and migrations must match (verified at every startup and in tests).
All primary keys are application-generated UUIDs; all timestamps are `timestamptz` (UTC); every mutable table has
`created_at` / `updated_at`.

## Entity-relationship overview

```
users 1─┬─* families (owner)            families 1─* family_members *─1 users
        ├─* devices 1─* device_pairings *─1 families
        ├─* authentication_sessions *─1 devices
        └─* pairing_tokens (created_by) *─1 families
devices 1─* app_usage_records
devices 1─* daily_usage_summaries
devices 1─1 safety_settings *─1 families
devices 1─* screen_sharing_sessions *─1 families
families 1─* safety_alerts *─0..1 devices
audit_logs (no FKs — survives deletions)
```

## Tables

### users
| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| email | varchar(254) | **unique on `lower(email)`** |
| password_hash | varchar(255) | Argon2id encoded string |
| display_name | varchar(80) | |
| role | varchar(16) | CHECK PARENT/CHILD |
| status | varchar(16) | ACTIVE/DISABLED |
| failed_login_attempts, locked_until | int, timestamptz | lockout |

### families
`id`, `name`, `owner_user_id → users ON DELETE CASCADE`. Index on owner.

### family_members
`family_id → families CASCADE`, `user_id → users CASCADE`, `member_role`. **Unique (family_id, user_id)**;
**partial unique (user_id) WHERE member_role='CHILD'** — a child belongs to one family at a time.

### devices
`user_id → users CASCADE`, `installation_id` (random per install), name/manufacturer/model/os/app version,
`status` ACTIVE/REVOKED, `last_seen_at`, `revoked_at`. **Unique (user_id, installation_id)**.

### pairing_tokens
`family_id → families CASCADE`, `created_by_user_id → users CASCADE`, `code_hash` (HMAC-SHA256 hex, **unique**),
`child_label`, `expires_at`, `consumed_at`, `consumed_by_device_id → devices SET NULL`, `revoked_at`.
Indexes: family, expires_at. The plaintext code is never stored.

### device_pairings
`family_id`, `device_id`, `child_user_id` (CASCADE), `paired_by_user_id`/`revoked_by_user_id`/`pairing_token_id`
(SET NULL), `status` ACTIVE/REVOKED, `paired_at`, `revoked_at`, `revocation_reason`.
**Partial unique (device_id) WHERE status='ACTIVE'**. Revoked rows are kept as history.

### app_usage_records
`device_id → devices CASCADE`, `usage_date`, `package_name`, `app_label`, `foreground_ms` (0..86 400 000),
`launch_count`, `last_used_at`. **Unique (device_id, usage_date, package_name)**; index (device_id, usage_date).

### daily_usage_summaries
`device_id`, `usage_date` (**unique pair**), `total_foreground_ms` (0..86 400 000), `app_count`,
`first_activity_at`, `last_activity_at`, `timezone`.

### screen_sharing_sessions
`family_id`, `device_id` (CASCADE), `requested_by_user_id`/`ended_by_user_id` (SET NULL), `status`
(REQUESTED/APPROVED/ACTIVE/REJECTED/STOPPED/EXPIRED/FAILED), `requested_at`, `request_expires_at`, `approved_at`,
`started_at`, `ended_at`, `max_end_at`, `end_reason`.
**Partial unique (device_id) WHERE status IN (REQUESTED, APPROVED, ACTIVE)**. Indexes (device_id, status),
(family_id, requested_at desc). **Metadata only — no media, frames or recordings are stored.**

### safety_alerts
`family_id` (CASCADE), `device_id`/`created_by_user_id`/`acknowledged_by_user_id` (SET NULL), `alert_type`,
`severity` (INFO..CRITICAL), `audience` (PARENTS/CHILD), `title` (120), `message` (1000), `dedupe_key` (**unique**),
`acknowledged_at`. Indexes (family_id, created_at desc), (device_id, created_at desc).

### safety_settings
`family_id`, `device_id` (**unique**, CASCADE), `daily_limit_minutes` (15..1440 or NULL), `bedtime_start/end`,
`limit_alerts_enabled`, `bedtime_alerts_enabled`, `screen_sharing_enabled`, `usage_retention_days` (7..365, default 90).

### authentication_sessions
`user_id` (CASCADE), `device_id` (CASCADE), `token_family_id` (rotation chain), `refresh_token_hash` (SHA-256 hex,
**unique**), `status` ACTIVE/ROTATED/REVOKED, `expires_at`, `absolute_expires_at`, `last_used_at`,
`replaced_by_session_id`, `revoked_at`, `revoke_reason`, `ip_address`, `user_agent`.
Indexes: (user_id,status), (device_id,status), token_family_id, expires_at.

### audit_logs
`actor_user_id`, `actor_device_id`, `action`, `target_type`, `target_id`, `outcome` (SUCCESS/FAILURE/DENIED),
`ip_address`, `details` (jsonb, never secrets), `created_at`. Indexes: created_at, (actor_user_id, created_at),
(action, created_at). No foreign keys by design.

## Retention policies (`RetentionService`, daily 03:17 UTC)

| Data | Retention |
|---|---|
| app_usage_records, daily_usage_summaries | per device `usage_retention_days` (default 90, parent-configurable 30–365 in app, 7–365 via API) |
| pairing_tokens | deleted 1 day after expiry |
| authentication_sessions | deleted 7 days after refresh expiry |
| screen_sharing_sessions | 90 days after end |
| safety_alerts | 180 days |
| audit_logs | 365 days |

## Deletion & revocation behaviour
* **Device revocation** keeps history (pairing row → REVOKED) but revokes all sessions; usage data remains until
  retention removes it (parents can still see past reports only while paired — after revocation the device is no
  longer visible to them, as access requires an ACTIVE pairing).
* **Deleting a user** (operator action) cascades to their devices, sessions, memberships, usage and settings; alerts
  keep the family but lose actor references (SET NULL); audit logs are retained.
* **Deleting a family** cascades to memberships, pairings, tokens, sessions, alerts and settings.
