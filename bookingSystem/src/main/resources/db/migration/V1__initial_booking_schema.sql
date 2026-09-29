-- Baseline matches existing entity mappings; further domain constraints follow separately.

create table app_user (
    id uuid primary key,
    name varchar(255),
    email varchar(255),
    password varchar(255),
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table clinic (
    id uuid primary key,
    name varchar(255),
    description varchar(255),
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table doctor (
    id uuid primary key,
    user_id uuid unique references app_user(id),
    name varchar(255),
    description varchar(255),
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table patient (
    id uuid primary key,
    user_id uuid unique references app_user(id),
    name varchar(255),
    description varchar(255),
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table slot (
    id uuid primary key,
    doctor_id uuid,
    clinic_id uuid not null references clinic(id),
    start_time timestamptz(6),
    end_time timestamptz(6),
    timezone varchar(255),
    status varchar(255),
    version bigint,
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table hold (
    id uuid primary key,
    slot_id uuid,
    hold_by_id uuid,
    status varchar(255),
    expires_at timestamptz(6),
    version bigint,
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);

create table appointment (
    id uuid primary key,
    patient_id uuid,
    booked_by_id uuid,
    booked_slot_id uuid,
    status varchar(255),
    canceled_by_id uuid,
    canceled_reason varchar(255),
    canceled_at timestamptz(6),
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false
);
