-- demo profile schema: the H2 equivalent of the JDK 21 shell's Flyway V1/V3
-- tables (AUTO_INCREMENT and the MySQL engine/charset clauses dropped; the
-- columns, types and the available >= 0 check are unchanged). Only used when
-- APP_PROFILE=demo; the mysql profile reuses the Flyway-owned tables as-is.
CREATE TABLE IF NOT EXISTS catalog_product (
    id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    description VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS inventory_stock (
    product_id BIGINT NOT NULL,
    available INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id),
    CONSTRAINT chk_inventory_available CHECK (available >= 0)
);
