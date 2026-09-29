-- Existing invalid rows must be repaired explicitly before this migration can succeed.
alter table slot
    alter column doctor_id set not null,
    alter column start_time set not null,
    alter column end_time set not null,
    alter column timezone set not null,
    alter column status set not null,
    alter column version set not null,
    alter column created_at set not null,
    alter column updated_at set not null;

alter table hold
    alter column slot_id set not null,
    alter column hold_by_id set not null,
    alter column status set not null,
    alter column expires_at set not null,
    alter column version set not null,
    alter column created_at set not null,
    alter column updated_at set not null;

alter table appointment
    alter column patient_id set not null,
    alter column booked_by_id set not null,
    alter column booked_slot_id set not null,
    alter column status set not null,
    alter column created_at set not null,
    alter column updated_at set not null;

alter table slot add constraint fk_slot_doctor_id foreign key (doctor_id) references doctor(id);
alter table hold add constraint fk_hold_slot_id foreign key (slot_id) references slot(id);
alter table hold add constraint fk_hold_hold_by_id foreign key (hold_by_id) references patient(id);
alter table appointment add constraint fk_appointment_booked_slot_id foreign key (booked_slot_id) references slot(id);
alter table appointment add constraint fk_appointment_patient_id foreign key (patient_id) references patient(id);

alter table slot add constraint ck_slot_time_order check (end_time > start_time);
alter table slot add constraint ck_slot_status check (status in ('OPEN', 'HELD', 'BOOKED', 'CANCELED'));
alter table hold add constraint ck_hold_status check (status in ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELED'));
alter table appointment add constraint ck_appointment_status check (status in ('CONFIRMED', 'CANCELLED'));
