-- Before authentication, booked_by_id contained a patient UUID. Preserve that
-- historical value rather than reinterpreting it as an account ID or discarding it.
alter table appointment rename column booked_by_id to legacy_booked_by_patient_id;
alter table appointment alter column legacy_booked_by_patient_id drop not null;
alter table appointment add column booked_by_id uuid;

-- Include soft-deleted profiles/accounts: these are historical attribution links.
update appointment a
set booked_by_id = p.user_id
from patient p
where p.id = a.legacy_booked_by_patient_id;

-- Unknown historical actors and system-created demo fixtures may remain null.
-- Authenticated confirmation always supplies the verified account ID.
alter table appointment add constraint fk_appointment_booked_by_account
    foreign key (booked_by_id) references app_user(id);
