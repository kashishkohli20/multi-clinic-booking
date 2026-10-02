create table login_session (
    id uuid primary key,
    user_id uuid not null,
    created_at timestamptz(6) not null,
    last_activity_at timestamptz(6) not null,
    expires_at timestamptz(6) not null,
    revoked_at timestamptz(6),
    version bigint not null default 0,

    constraint fk_login_session_user foreign key (user_id) references app_user(id),
    constraint ck_login_session_expiry check (expires_at > created_at),
    constraint ck_login_session_activity check (
        last_activity_at >= created_at and last_activity_at < expires_at
    ),
    constraint ck_login_session_revocation check (revoked_at is null or revoked_at >= created_at),
    constraint ck_login_session_version check (version >= 0)
);

create index idx_login_session_user on login_session(user_id);
