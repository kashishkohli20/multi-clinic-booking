-- create clinic membership
create table clinic_membership (
    id uuid primary key,
    user_id uuid not null references app_user(id),
    clinic_id uuid not null references clinic(id),
    role varchar(32) not null,
    active boolean not null default false,
    created_at timestamptz(6),
    updated_at timestamptz(6),
    deleted boolean not null default false,

    constraint ck_clinic_membership_role check (role in ('DOCTOR', 'CLINIC_ADMIN')),
    constraint ck_uq_clinic_membership_user_clinic_role unique (user_id, clinic_id, role)
);