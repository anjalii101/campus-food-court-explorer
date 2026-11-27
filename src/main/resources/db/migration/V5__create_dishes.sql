-- Dishes (maps entity com.campusfoodcourt.campus_foodcourt.menu.Dish)
--   price_paise    : money as an exact integer number of paise (Rs 1 = 100 paise), never floating point
--   stock_quantity : NULL = stock is not tracked for this dish (NOT the same as 0 = sold out)
--   image_ref      : storage key of the image, not a public URL
--   deleted_at     : soft deletion; a deleted dish keeps its row so existing reviews stay valid
CREATE TABLE dishes (
    id             UUID          PRIMARY KEY,
    outlet_id      UUID          NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    description    VARCHAR(1000),
    price_paise    BIGINT        NOT NULL,
    dietary_type   VARCHAR(10)   NOT NULL,
    available      BOOLEAN       NOT NULL DEFAULT true,
    stock_quantity INTEGER,
    image_ref      VARCHAR(500),
    deleted_at     TIMESTAMPTZ,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_dishes_outlet FOREIGN KEY (outlet_id) REFERENCES outlets (id) ON DELETE RESTRICT,
    CONSTRAINT ck_dishes_price_positive CHECK (price_paise > 0),
    CONSTRAINT ck_dishes_dietary_type CHECK (dietary_type IN ('VEG', 'NON_VEG', 'EGG')),
    CONSTRAINT ck_dishes_stock_not_negative CHECK (stock_quantity IS NULL OR stock_quantity >= 0),
    CONSTRAINT ck_dishes_updated_after_created CHECK (updated_at >= created_at)
);

-- Dish names are unique case-insensitively within one outlet, among dishes that are not soft-deleted.
-- A deleted dish's name can therefore be reused for a new dish.
CREATE UNIQUE INDEX uk_dishes_outlet_name_lower_active ON dishes (outlet_id, lower(name)) WHERE deleted_at IS NULL;
