-- set app_user email as unique and not null
alter table app_user
    alter column email set not null;

alter table app_user
    add constraint ck_app_user_email_not_blank
    check (email ~ '[^[:space:]]');

create unique index uq_app_user_email_lower
    on app_user (lower(email));
