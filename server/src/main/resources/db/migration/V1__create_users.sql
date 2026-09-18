-- Operators (FR-01) and their granular permissions (BR-01).

CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    name          varchar(120) NOT NULL,
    email         varchar(255) NOT NULL,
    password_hash varchar(100) NOT NULL,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL
);

-- BR-02: operator email is unique. Also the login lookup.
CREATE UNIQUE INDEX ux_users_email ON users (email);

CREATE TABLE user_permissions (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission varchar(40) NOT NULL,
    CONSTRAINT pk_user_permissions PRIMARY KEY (user_id, permission)
);
