-- Family Safety: initial schema
-- All timestamps are UTC (timestamptz). Primary keys are application-generated UUIDs.
-- Screen recordings are NOT stored: screen_sharing_sessions holds session metadata only.

create table users (
    id                     uuid primary key,
    email                  varchar(254) not null,
    password_hash          varchar(255) not null,
    display_name           varchar(80)  not null,
    role                   varchar(16)  not null check (role in ('PARENT', 'CHILD')),
    status                 varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    failed_login_attempts  integer      not null default 0,
    locked_until           timestamptz,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now()
);
create unique index ux_users_email_lower on users (lower(email));

create table families (
    id             uuid primary key,
    name           varchar(80) not null,
    owner_user_id  uuid        not null references users (id) on delete cascade,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_families_owner on families (owner_user_id);

create table family_members (
    id           uuid primary key,
    family_id    uuid        not null references families (id) on delete cascade,
    user_id      uuid        not null references users (id) on delete cascade,
    member_role  varchar(16) not null check (member_role in ('PARENT', 'CHILD')),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint ux_family_members_family_user unique (family_id, user_id)
);
create index ix_family_members_user on family_members (user_id);
-- A child account can belong to at most one family at a time.
create unique index ux_family_members_child_single_family on family_members (user_id) where member_role = 'CHILD';

create table devices (
    id               uuid primary key,
    user_id          uuid         not null references users (id) on delete cascade,
    installation_id  varchar(64)  not null,
    device_name      varchar(80)  not null,
    manufacturer     varchar(80),
    model            varchar(80),
    os_version       varchar(32),
    app_version      varchar(32),
    status           varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
    last_seen_at     timestamptz,
    revoked_at       timestamptz,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    constraint ux_devices_user_installation unique (user_id, installation_id)
);
create index ix_devices_user on devices (user_id);

create table pairing_tokens (
    id                     uuid primary key,
    family_id              uuid        not null references families (id) on delete cascade,
    created_by_user_id     uuid        not null references users (id) on delete cascade,
    code_hash              varchar(64) not null,
    child_label            varchar(80),
    expires_at             timestamptz not null,
    consumed_at            timestamptz,
    consumed_by_device_id  uuid references devices (id) on delete set null,
    revoked_at             timestamptz,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    constraint ux_pairing_tokens_code_hash unique (code_hash)
);
create index ix_pairing_tokens_family on pairing_tokens (family_id);
create index ix_pairing_tokens_expires on pairing_tokens (expires_at);

create table device_pairings (
    id                  uuid primary key,
    family_id           uuid        not null references families (id) on delete cascade,
    device_id           uuid        not null references devices (id) on delete cascade,
    child_user_id       uuid        not null references users (id) on delete cascade,
    paired_by_user_id   uuid        references users (id) on delete set null,
    pairing_token_id    uuid        references pairing_tokens (id) on delete set null,
    status              varchar(16) not null check (status in ('ACTIVE', 'REVOKED')),
    paired_at           timestamptz not null,
    revoked_at          timestamptz,
    revoked_by_user_id  uuid        references users (id) on delete set null,
    revocation_reason   varchar(64),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
create index ix_device_pairings_family on device_pairings (family_id, status);
-- Only one active pairing per device.
create unique index ux_device_pairings_active_device on device_pairings (device_id) where status = 'ACTIVE';

create table app_usage_records (
    id              uuid primary key,
    device_id       uuid         not null references devices (id) on delete cascade,
    usage_date      date         not null,
    package_name    varchar(255) not null,
    app_label       varchar(255),
    foreground_ms   bigint       not null check (foreground_ms >= 0 and foreground_ms <= 86400000),
    launch_count    integer      not null default 0 check (launch_count >= 0),
    last_used_at    timestamptz,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint ux_app_usage_device_date_pkg unique (device_id, usage_date, package_name)
);
create index ix_app_usage_device_date on app_usage_records (device_id, usage_date);

create table daily_usage_summaries (
    id                    uuid primary key,
    device_id             uuid        not null references devices (id) on delete cascade,
    usage_date            date        not null,
    total_foreground_ms   bigint      not null check (total_foreground_ms >= 0 and total_foreground_ms <= 86400000),
    app_count             integer     not null default 0,
    first_activity_at     timestamptz,
    last_activity_at      timestamptz,
    timezone              varchar(64),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint ux_daily_usage_device_date unique (device_id, usage_date)
);

create table screen_sharing_sessions (
    id                    uuid primary key,
    family_id             uuid        not null references families (id) on delete cascade,
    device_id             uuid        not null references devices (id) on delete cascade,
    requested_by_user_id  uuid        references users (id) on delete set null,
    status                varchar(16) not null check (status in ('REQUESTED', 'APPROVED', 'ACTIVE', 'REJECTED', 'STOPPED', 'EXPIRED', 'FAILED')),
    requested_at          timestamptz not null,
    request_expires_at    timestamptz not null,
    approved_at           timestamptz,
    started_at            timestamptz,
    ended_at              timestamptz,
    max_end_at            timestamptz,
    end_reason            varchar(64),
    ended_by_user_id      uuid        references users (id) on delete set null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);
create index ix_screen_sessions_device_status on screen_sharing_sessions (device_id, status);
create index ix_screen_sessions_family on screen_sharing_sessions (family_id, requested_at desc);
-- At most one open (requested/approved/active) session per device.
create unique index ux_screen_sessions_open_device on screen_sharing_sessions (device_id)
    where status in ('REQUESTED', 'APPROVED', 'ACTIVE');

create table safety_alerts (
    id                       uuid primary key,
    family_id                uuid          not null references families (id) on delete cascade,
    device_id                uuid          references devices (id) on delete set null,
    created_by_user_id       uuid          references users (id) on delete set null,
    alert_type               varchar(32)   not null,
    severity                 varchar(16)   not null check (severity in ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    audience                 varchar(16)   not null check (audience in ('PARENTS', 'CHILD')),
    title                    varchar(120)  not null,
    message                  varchar(1000),
    dedupe_key               varchar(160),
    acknowledged_at          timestamptz,
    acknowledged_by_user_id  uuid          references users (id) on delete set null,
    created_at               timestamptz   not null default now(),
    updated_at               timestamptz   not null default now(),
    constraint ux_safety_alerts_dedupe unique (dedupe_key)
);
create index ix_safety_alerts_family_created on safety_alerts (family_id, created_at desc);
create index ix_safety_alerts_device_created on safety_alerts (device_id, created_at desc);

create table safety_settings (
    id                         uuid primary key,
    family_id                  uuid        not null references families (id) on delete cascade,
    device_id                  uuid        not null references devices (id) on delete cascade,
    daily_limit_minutes        integer     check (daily_limit_minutes between 15 and 1440),
    bedtime_start              time,
    bedtime_end                time,
    limit_alerts_enabled       boolean     not null default true,
    bedtime_alerts_enabled     boolean     not null default false,
    screen_sharing_enabled     boolean     not null default true,
    usage_retention_days       integer     not null default 90 check (usage_retention_days between 7 and 365),
    created_at                 timestamptz not null default now(),
    updated_at                 timestamptz not null default now(),
    constraint ux_safety_settings_device unique (device_id)
);
create index ix_safety_settings_family on safety_settings (family_id);

create table authentication_sessions (
    id                      uuid primary key,
    user_id                 uuid        not null references users (id) on delete cascade,
    device_id               uuid        references devices (id) on delete cascade,
    token_family_id         uuid        not null,
    refresh_token_hash      varchar(64) not null,
    status                  varchar(16) not null check (status in ('ACTIVE', 'ROTATED', 'REVOKED')),
    expires_at              timestamptz not null,
    absolute_expires_at     timestamptz not null,
    last_used_at            timestamptz,
    replaced_by_session_id  uuid,
    revoked_at              timestamptz,
    revoke_reason           varchar(64),
    ip_address              varchar(64),
    user_agent              varchar(255),
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    constraint ux_auth_sessions_refresh_hash unique (refresh_token_hash)
);
create index ix_auth_sessions_user on authentication_sessions (user_id, status);
create index ix_auth_sessions_device on authentication_sessions (device_id, status);
create index ix_auth_sessions_token_family on authentication_sessions (token_family_id);
create index ix_auth_sessions_expires on authentication_sessions (expires_at);

-- Audit logs intentionally have no foreign keys so that history survives account deletion.
create table audit_logs (
    id               uuid primary key,
    actor_user_id    uuid,
    actor_device_id  uuid,
    action           varchar(64)  not null,
    target_type      varchar(32),
    target_id        varchar(64),
    outcome          varchar(16)  not null check (outcome in ('SUCCESS', 'FAILURE', 'DENIED')),
    ip_address       varchar(64),
    details          jsonb,
    created_at       timestamptz  not null default now()
);
create index ix_audit_logs_created on audit_logs (created_at);
create index ix_audit_logs_actor on audit_logs (actor_user_id, created_at desc);
create index ix_audit_logs_action on audit_logs (action, created_at desc);
