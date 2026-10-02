package ua.edu.highload.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private static final RowMapper<Customer> MAPPER = (rs, row) -> new Customer(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
            rs.getTimestamp("created_at").toInstant());
    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Customer insert(String name, String email) {
        return jdbc.sql("INSERT INTO customers (id, name, email) VALUES (:id, :name, :email) RETURNING *")
                .param("id", UUID.randomUUID()).param("name", name).param("email", email)
                .query(MAPPER).single();
    }

    public Optional<Customer> findById(UUID id) {
        return jdbc.sql("SELECT * FROM customers WHERE id = :id").param("id", id)
                .query(MAPPER).optional();
    }
}
