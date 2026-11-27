-- Outlets (maps entity com.campusfoodcourt.campus_foodcourt.outlets.Outlet)
-- One outlet per vendor user. Outlets are created only through the vendor approval workflow (later milestone).
CREATE TABLE outlets (
    id              UUID          PRIMARY KEY,
    vendor_user_id  BIGINT        NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    description     VARCHAR(1000),
    location        VARCHAR(200)  NOT NULL,
    contact_phone   VARCHAR(20),
    contact_email   VARCHAR(255),
    status          VARCHAR(20)   NOT NULL,
    manually_closed BOOLEAN       NOT NULL DEFAULT false,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_outlets_vendor_user FOREIGN KEY (vendor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_outlets_vendor_user_id UNIQUE (vendor_user_id),
    CONSTRAINT ck_outlets_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_outlets_updated_after_created CHECK (updated_at >= created_at)
);

-- Outlet names are unique case-insensitively ("Chai Point" and "chai point" cannot both exist).
CREATE UNIQUE INDEX uk_outlets_name_lower ON outlets (lower(name));

-- Weekly opening hours: at most ONE interval per ISO weekday (1 = Monday ... 7 = Sunday).
-- A missing row means the outlet is closed on that day. Times are wall-clock times in Asia/Kolkata.
-- Overnight intervals (closing after midnight) and split shifts are deliberately not supported.
-- Hours belong to their outlet, so they are removed with it (the outlet itself cannot be deleted
-- while it still has dishes, see V5).
CREATE TABLE outlet_operating_hours (
    outlet_id   UUID      NOT NULL,
    day_of_week SMALLINT  NOT NULL,
    opens_at    TIME      NOT NULL,
    closes_at   TIME      NOT NULL,
    CONSTRAINT pk_outlet_operating_hours PRIMARY KEY (outlet_id, day_of_week),
    CONSTRAINT fk_outlet_operating_hours_outlet FOREIGN KEY (outlet_id) REFERENCES outlets (id) ON DELETE CASCADE,
    CONSTRAINT ck_outlet_operating_hours_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_outlet_operating_hours_closes_after_opens CHECK (closes_at > opens_at)
);
