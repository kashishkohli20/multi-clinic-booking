create table doctor_clinic_access_request (
    id uuid primary key,
    doctor_id uuid not null,
    clinic_id uuid not null,
    status varchar(24) not null default 'PENDING',
    requested_at timestamptz(6) not null,
    decided_at timestamptz(6),
    decided_by_id uuid,
    version bigint not null default 0,

    constraint fk_doctor_clinic_request_doctor foreign key (doctor_id) references doctor(id),
    constraint fk_doctor_clinic_request_clinic foreign key (clinic_id) references clinic(id),
    constraint fk_doctor_clinic_request_decider foreign key (decided_by_id) references app_user(id),
    constraint ck_doctor_clinic_request_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_doctor_clinic_request_decision check (
        (status = 'PENDING' and decided_at is null and decided_by_id is null)
        or
        (status in ('APPROVED', 'REJECTED') and decided_at is not null
            and decided_by_id is not null and decided_at >= requested_at)
    ),
    constraint ck_doctor_clinic_request_version check (version >= 0)
);

-- A new request is a new row; previous decisions remain available as history.
create unique index uq_doctor_clinic_request_pending
    on doctor_clinic_access_request(doctor_id, clinic_id) where status = 'PENDING';

-- Clinic review and doctor history queries will always be scoped by their caller.
create index idx_doctor_clinic_request_clinic on doctor_clinic_access_request(clinic_id, status, requested_at, id);
create index idx_doctor_clinic_request_doctor on doctor_clinic_access_request(doctor_id, requested_at, id);
