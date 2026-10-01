-- Existing and newly created accounts remain disabled until explicitly enabled.
alter table app_user
    add column enabled boolean not null default false;
