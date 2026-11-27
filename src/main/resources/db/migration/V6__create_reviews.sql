-- Reviews (maps entity com.campusfoodcourt.campus_foodcourt.reviews.Review)
-- One review per user per dish. This constraint only prevents duplicates; it does NOT prove that the user
-- ever ordered the dish. Purchase eligibility is enforced by the order workflow in a later milestone.
CREATE TABLE reviews (
    id          UUID          PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    dish_id     UUID          NOT NULL,
    rating      SMALLINT      NOT NULL,
    review_text VARCHAR(2000),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_dish FOREIGN KEY (dish_id) REFERENCES dishes (id) ON DELETE RESTRICT,
    CONSTRAINT uk_reviews_user_dish UNIQUE (user_id, dish_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_reviews_updated_after_created CHECK (updated_at >= created_at)
);

-- "Newest reviews of a dish" is served directly from this index.
CREATE INDEX idx_reviews_dish_created_at ON reviews (dish_id, created_at DESC);
