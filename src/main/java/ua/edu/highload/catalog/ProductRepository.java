package ua.edu.highload.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
    private static final RowMapper<Product> MAPPER = (rs, row) -> new Product(
            rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"),
            rs.getString("description"), rs.getBigDecimal("price"), rs.getLong("stock"),
            rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
    private final JdbcClient jdbc;
    private final CatalogCache cache;

    public ProductRepository(JdbcClient jdbc, CatalogCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public Product insert(ProductRequests.Create request) {
        Product product = jdbc.sql("""
                INSERT INTO products (id, sku, name, description, price, stock)
                VALUES (:id, :sku, :name, :description, :price, :stock) RETURNING *
                """)
                .param("id", UUID.randomUUID()).param("sku", request.sku().toUpperCase(Locale.ROOT))
                .param("name", request.name().strip()).param("description", request.description().strip())
                .param("price", request.price()).param("stock", request.stock()).query(MAPPER).single();
        cache.afterWrite();
        return product;
    }

    public Optional<Product> findById(UUID id) {
        return jdbc.sql("SELECT * FROM products WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Product> list(int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM products WHERE active = TRUE
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    /** PostgreSQL acquires all product locks in the same order for checkout and cancellation. */
    public List<Product> lockAll(Collection<UUID> ids) {
        return jdbc.sql("SELECT * FROM products WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids).query(MAPPER).list();
    }

    public Product update(UUID id, ProductRequests.Update request) {
        Product product = jdbc.sql("""
                UPDATE products SET name = :name, description = :description, price = :price
                WHERE id = :id RETURNING *
                """).param("id", id).param("name", request.name().strip())
                .param("description", request.description().strip()).param("price", request.price())
                .query(MAPPER).single();
        cache.afterWrite();
        return product;
    }

    public Product changeStock(UUID id, long delta) {
        Product product = jdbc.sql("UPDATE products SET stock = stock + :delta WHERE id = :id RETURNING *")
                .param("id", id).param("delta", delta).query(MAPPER).single();
        cache.afterWrite();
        return product;
    }

    public void deactivate(UUID id) {
        jdbc.sql("UPDATE products SET active = FALSE WHERE id = :id").param("id", id).update();
        cache.afterWrite();
    }
}
