package ua.edu.highload.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private static final RowMapper<OrderModels.Summary> SUMMARY = (rs, row) -> new OrderModels.Summary(
            rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
            OrderModels.Status.valueOf(rs.getString("status")), rs.getBigDecimal("total"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<OrderModels.Item> ITEM = (rs, row) -> new OrderModels.Item(
            rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getString("product_name"), rs.getBigDecimal("unit_price"), rs.getInt("quantity"));
    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OrderModels.Summary insert(UUID customerId, BigDecimal total) {
        return jdbc.sql("""
                INSERT INTO orders (id, customer_id, status, total)
                VALUES (:id, :customerId, 'PLACED', :total) RETURNING *
                """).param("id", UUID.randomUUID()).param("customerId", customerId).param("total", total)
                .query(SUMMARY).single();
    }

    public void insertItem(UUID orderId, OrderModels.Item item) {
        jdbc.sql("""
                INSERT INTO order_items (id, order_id, product_id, product_name, unit_price, quantity)
                VALUES (:id, :orderId, :productId, :name, :price, :quantity)
                """).param("id", item.id()).param("orderId", orderId).param("productId", item.productId())
                .param("name", item.productName()).param("price", item.unitPrice())
                .param("quantity", item.quantity()).update();
    }

    public Optional<OrderModels.Summary> findById(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id").param("id", id).query(SUMMARY).optional();
    }

    public Optional<OrderModels.Summary> lockById(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id FOR UPDATE").param("id", id)
                .query(SUMMARY).optional();
    }

    public List<OrderModels.Item> items(UUID orderId) {
        return jdbc.sql("SELECT * FROM order_items WHERE order_id = :id ORDER BY product_id")
                .param("id", orderId).query(ITEM).list();
    }

    public List<OrderModels.Summary> list(UUID customerId, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM orders WHERE customer_id = :customerId
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """).param("customerId", customerId).param("limit", limit).param("offset", offset)
                .query(SUMMARY).list();
    }

    public OrderModels.Summary cancel(UUID id) {
        return jdbc.sql("UPDATE orders SET status = 'CANCELLED' WHERE id = :id RETURNING *")
                .param("id", id).query(SUMMARY).single();
    }
}
