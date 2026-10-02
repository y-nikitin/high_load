-- Run only in the dedicated baseline project, never against the user's existing dataset.
INSERT INTO customers(id, name, email)
VALUES ('00000000-0000-0000-0000-000000000001', 'Baseline Customer', 'baseline@example.test');

INSERT INTO products(id, sku, name, description, price, stock)
SELECT md5('baseline-product-' || n)::uuid, 'BASELINE-' || n,
       'Baseline product ' || n, repeat('Deterministic catalog data. ', 20),
       10.00, 1000000000
FROM generate_series(1, 100) AS n;
