create table refresh_token (
    id uuid primary key,
    session_id uuid not null,
    token_hash varchar(64) not null,
    created_at timestamptz(6) not null,
    expires_at timestamptz(6) not null,
    consumed_at timestamptz(6),
    version bigint not null default 0,

    constraint fk_refresh_token_session foreign key (session_id) references login_session(id),
    constraint uq_refresh_token_hash unique (token_hash),
    constraint ck_refresh_token_hash check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_refresh_token_expiry check (expires_at > created_at),
    constraint ck_refresh_token_consumption check (
        consumed_at is null or (consumed_at >= created_at and consumed_at < expires_at)
    ),
    constraint ck_refresh_token_version check (version >= 0)
);

-- Retain every consumed hash for replay detection. Only the current credential is unconsumed.
-- Rotation must flush consumption before inserting its successor, within the same transaction.
create unique index uq_refresh_token_unconsumed_session on refresh_token(session_id)
    where consumed_at is null;

-- Includes history for session cleanup; the partial unique index covers only current credentials.
create index idx_refresh_token_session on refresh_token(session_id);
