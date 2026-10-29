-- Stage2 B3-02: deterministic built-in catalog of exactly 10,000 products.
--
-- Ids 1..20 are the V2 demo rows and stay untouched. Ids 21..10000 are
-- generated here with a fixed shape so every fresh database, every reset and
-- every script sees the same catalog:
--   name        = 实验商品-<id>
--   price       = 99.00
--   status      = ON_SALE
--   description = Stage2 B3-02 deterministic built-in product
--
-- Inventory follows ONE deterministic formula for the whole set (the existing
-- 20 rows are updated too):
--   available = MOD(product_id * 37, 501)   -- 0..500
--   reserved  = 0
-- Product 1 => 37, product 20 => 239, product 10000 => 262.
--
-- Re-running this file is a no-op: the INSERT only adds missing ids and the
-- inventory INSERT only adds missing rows. cte_max_recursion_depth is raised
-- on this session because MySQL's default (1000) is below the 9,980-step
-- sequence generated here; Flyway executes a migration on one connection.
SET SESSION cte_max_recursion_depth = 11000;

INSERT INTO catalog_product (id, name, price, status, description)
WITH RECURSIVE seq(n) AS (
    SELECT 21
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
SELECT n,
       CONCAT('实验商品-', n),
       99.00,
       'ON_SALE',
       'Stage2 B3-02 deterministic built-in product'
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM catalog_product p WHERE p.id = seq.n);

INSERT INTO inventory_stock (product_id, available, reserved)
SELECT p.id, MOD(p.id * 37, 501), 0
FROM catalog_product p
WHERE NOT EXISTS (SELECT 1 FROM inventory_stock s WHERE s.product_id = p.id);

-- Make the pre-existing demo rows follow the same formula as the generated
-- ones, so reset-lab.sh / POST /lab/cache/reset can verify the whole table
-- against a single expression.
UPDATE inventory_stock
SET available = MOD(product_id * 37, 501),
    reserved = 0;
