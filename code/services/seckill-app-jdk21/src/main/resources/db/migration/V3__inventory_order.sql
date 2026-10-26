-- Inventory and order tables (Task 13). Flyway owns the schema; Hibernate
-- runs in validate mode. The stock table starts at exactly 100 units per
-- seeded catalog product; order rows are unique by business number and by the
-- idempotency key that makes POST /api/orders replay-safe.
CREATE TABLE inventory_stock (
    product_id BIGINT NOT NULL,
    available INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id),
    CONSTRAINT chk_inventory_available CHECK (available >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE order_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_idempotency (idempotency_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO inventory_stock (product_id, available)
SELECT id, 100
FROM catalog_product;
