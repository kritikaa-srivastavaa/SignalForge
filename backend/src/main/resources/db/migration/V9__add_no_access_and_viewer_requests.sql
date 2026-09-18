-- Existing members keep their roles; only future accounts default to NO_ACCESS.
ALTER TABLE app_users DROP CONSTRAINT ck_app_users_role;
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_role
    CHECK (role IN ('NO_ACCESS', 'VIEWER', 'OPERATOR', 'ADMIN'));
ALTER TABLE app_users ALTER COLUMN role SET DEFAULT 'NO_ACCESS';

ALTER TABLE access_requests DROP CONSTRAINT access_requests_requested_role_check;
ALTER TABLE access_requests ADD CONSTRAINT access_requests_requested_role_check
    CHECK (requested_role IN ('VIEWER', 'OPERATOR'));
