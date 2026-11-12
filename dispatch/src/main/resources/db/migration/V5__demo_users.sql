-- Demo reference data (spec section 7). Fictional services and users.
-- Password for all demo users: demo1234 (BCrypt, cost 10). Delivery requests are not seeded here.

INSERT INTO service (id, code, name, created_at) VALUES
    ('0195f000-0000-7000-8000-000000000001', 'NORTH', 'Служба Север', now()),
    ('0195f000-0000-7000-8000-000000000002', 'SOUTH', 'Служба Юг',    now());

INSERT INTO app_user (id, username, display_name, password_hash, role, service_id, created_at) VALUES
    ('0195f000-0000-7000-8000-000000000101', 'north.initiator', 'Инициатор (Север)',
     '$2y$10$DIKUWySSLg3JNb/mmdf6dewV95wbQmWTWd9RsmEI.CLbmll9uGPAC', 'INITIATOR',
     '0195f000-0000-7000-8000-000000000001', now()),
    ('0195f000-0000-7000-8000-000000000102', 'north.operator', 'Оператор (Север)',
     '$2y$10$DIKUWySSLg3JNb/mmdf6dewV95wbQmWTWd9RsmEI.CLbmll9uGPAC', 'OPERATOR',
     '0195f000-0000-7000-8000-000000000001', now()),
    ('0195f000-0000-7000-8000-000000000201', 'south.initiator', 'Инициатор (Юг)',
     '$2y$10$DIKUWySSLg3JNb/mmdf6dewV95wbQmWTWd9RsmEI.CLbmll9uGPAC', 'INITIATOR',
     '0195f000-0000-7000-8000-000000000002', now());
