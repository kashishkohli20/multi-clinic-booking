-- A soft delete hides a row; only a lifecycle transition releases its reservation.
-- Deliberately include deleted rows in both indexes. Existing duplicates must be
-- resolved explicitly before applying this migration.
create unique index uq_hold_active_slot on hold (slot_id) where status = 'ACTIVE';
create unique index uq_appointment_confirmed_slot on appointment (booked_slot_id)
    where status = 'CONFIRMED';
