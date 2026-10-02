package ua.edu.highload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.highload.catalog.ProductRequests;
import ua.edu.highload.catalog.ProductService;
import ua.edu.highload.common.ApiException;
import ua.edu.highload.customer.CreateCustomerRequest;
import ua.edu.highload.customer.CustomerService;
import ua.edu.highload.order.OrderModels;
import ua.edu.highload.order.OrderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderApiIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.4-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired OrderService orders;

    @BeforeEach
    void clearTestDatabase() {
        jdbc.execute("TRUNCATE order_items, orders, products, customers");
    }

    @Test
    void customerAndCatalogCrudProvideLocationPaginationAndSoftDelete() throws Exception {
        JsonNode customer = request(post("/api/customers"),
                Map.of("name", " Olena ", "email", "OLENA@example.com"), 201);
        mvc.perform(get("/api/customers/" + customer.get("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Olena"))
                .andExpect(jsonPath("$.email").value("olena@example.com"));
        JsonNode product = request(post("/api/products"), productBody("usb-cable", "99.90", 10), 201);
        String id = product.get("id").asText();
        assertThat(product.get("sku").asText()).isEqualTo("USB-CABLE");
        request(post("/api/products"), productBody("another-cable", "49.50", 20), 201);
        mvc.perform(get("/api/products?size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
        request(put("/api/products/" + id),
                Map.of("name", "New cable", "description", "Updated", "price", "129.90"), 200);
        request(post("/api/products/" + id + "/restock"), Map.of("quantity", 5), 200);
        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(15)).andExpect(jsonPath("$.price").value(129.90));
        mvc.perform(delete("/api/products/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/products/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void checkoutUsesServerPricesAndKeepsHistoryAfterCatalogChanges() throws Exception {
        UUID customerId = customer();
        UUID first = product("ITEM-1", "19.95", 10);
        UUID second = product("ITEM-2", "0.10", 10);
        JsonNode order = request(post("/api/orders"), Map.of("customerId", customerId, "items", List.of(
                Map.of("productId", first, "quantity", 2), Map.of("productId", second, "quantity", 3))), 201);
        UUID id = UUID.fromString(order.get("id").asText());
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("40.20");
        assertThat(products.get(first).stock()).isEqualTo(8);
        assertThat(products.get(second).stock()).isEqualTo(7);
        products.update(first, new ProductRequests.Update("Changed name", "", new BigDecimal("1000.00")));
        products.delete(first);
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(40.20)).andExpect(jsonPath("$.items.length()").value(2));
        assertThat(orders.get(id).items().stream().filter(i -> i.productId().equals(first)).findFirst().orElseThrow()
                .unitPrice()).isEqualByComparingTo("19.95");
        mvc.perform(get("/api/orders").param("customerId", customerId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString()));
    }

    @Test
    void insufficientStockDoesNotPartiallyWriteOrderOrDeductOtherProducts() throws Exception {
        UUID customerId = customer();
        UUID first = product("ENOUGH", "10.00", 10);
        UUID second = product("EMPTY", "10.00", 0);
        request(post("/api/orders"), Map.of("customerId", customerId, "items", List.of(
                Map.of("productId", first, "quantity", 2), Map.of("productId", second, "quantity", 1))), 409);
        assertThat(products.get(first).stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    void databaseFailureRollsBackAlreadyInsertedOrderAndStockChange() throws Exception {
        UUID customerId = customer();
        UUID productId = product("ROLLBACK", "10.00", 10);
        // Force a failure at the final insert, after the order and stock UPDATE have executed.
        jdbc.execute("ALTER TABLE order_items ADD CONSTRAINT test_reject_quantity CHECK (quantity <> 2)");
        try {
            request(post("/api/orders"), checkout(customerId, productId, 2), 409);
            assertThat(products.get(productId).stock()).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE order_items DROP CONSTRAINT test_reject_quantity");
        }
    }

    @Test
    void cancellationReturnsInventoryOnlyOnceEvenForDeletedProducts() throws Exception {
        UUID customerId = customer();
        UUID productId = product("CANCEL", "12.50", 5);
        UUID orderId = orders.create(createRequest(customerId, productId, 3)).id();
        products.delete(productId);
        mvc.perform(post("/api/orders/" + orderId + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/" + orderId + "/cancel")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Long.class, productId))
                .isEqualTo(5);
    }

    @Test
    void concurrentCheckoutsCannotOversell() throws Exception {
        UUID customerId = customer();
        UUID productId = product("LIMITED", "99.99", 5);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        orders.create(createRequest(customerId, productId, 1));
                        return true;
                    } catch (ApiException exception) {
                        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(products.get(productId).stock()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(5);
    }

    @Test
    void concurrentCancellationsCannotReturnStockTwice() throws Exception {
        UUID productId = product("RETURN", "5.00", 5);
        UUID id = orders.create(createRequest(customer(), productId, 2)).id();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<OrderModels.Details>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return orders.cancel(id);
                }));
            }
            start.countDown();
            for (var result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).status()).isEqualTo(OrderModels.Status.CANCELLED);
            }
        }
        assertThat(products.get(productId).stock()).isEqualTo(5);
    }

    @Test
    void invalidInputProducesStructured400Responses() throws Exception {
        JsonNode error = request(post("/api/products"), productBody("BAD", "-1.00", 10), 400);
        assertThat(error.get("errors").isArray()).isTrue();
        request(post("/api/customers"), Map.of("name", " ", "email", "invalid"), 400);
        request(post("/api/orders"), Map.of("customerId", UUID.randomUUID(), "items", List.of()), 400);
        request(post("/api/orders"), checkout(UUID.randomUUID(), UUID.randomUUID(), 0), 400);
        request(post("/api/orders"), Map.of("customerId", UUID.randomUUID(), "items", List.of(
                Map.of("productId", UUID.randomUUID(), "quantity", 1.5))), 400);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + UUID.randomUUID() + "\",\"items\":[null]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/products/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?page=-1")).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailAndSkuProduce409() throws Exception {
        request(post("/api/customers"), Map.of("name", "One", "email", "test@example.com"), 201);
        request(post("/api/customers"), Map.of("name", "Two", "email", "TEST@example.com"), 409);
        request(post("/api/products"), productBody("SAME", "1.00", 1), 201);
        request(post("/api/products"), productBody("same", "2.00", 1), 409);
    }

    @Test
    void missingResourcesAndInactiveProductsAreRejected() throws Exception {
        mvc.perform(get("/api/customers/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
        UUID customerId = customer();
        request(post("/api/orders"), checkout(customerId, UUID.randomUUID(), 1), 404);
        UUID productId = product("INACTIVE", "1.00", 10);
        request(post("/api/orders"), checkout(UUID.randomUUID(), productId, 1), 404);
        products.delete(productId);
        request(post("/api/orders"), checkout(customerId, productId, 1), 409);
    }

    @Test
    void duplicatedItemsAndClientSuppliedPriceAreRejected() throws Exception {
        UUID customerId = customer();
        UUID productId = product("DUPLICATE", "20.00", 10);
        var item = Map.of("productId", productId, "quantity", 1);
        request(post("/api/orders"), Map.of("customerId", customerId, "items", List.of(item, item)), 400);
        request(post("/api/orders"), Map.of("customerId", customerId, "items", List.of(
                Map.of("productId", productId, "quantity", 1, "price", "0.01"))), 400);
        assertThat(products.get(productId).stock()).isEqualTo(10);
    }

    @Test
    void migrationsHealthAndOpenApiAreAvailable() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(1);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/products/{id}'].delete.responses['204']").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    private JsonNode request(MockHttpServletRequestBuilder builder, Object body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsByteArray());
        if (expectedStatus == 201) {
            assertThat(result.getResponse().getHeader("Location")).endsWith(response.get("id").asText());
        }
        return response;
    }

    private UUID customer() {
        return customers.create(new CreateCustomerRequest("Test Customer", "test@example.com")).id();
    }

    private UUID product(String sku, String price, long stock) {
        return products.create(new ProductRequests.Create(sku, "Test product", "",
                new BigDecimal(price), stock)).id();
    }

    private Map<String, Object> productBody(String sku, String price, long stock) {
        return Map.of("sku", sku, "name", "USB cable", "description", "Demo product", "price", price, "stock", stock);
    }

    private Map<String, Object> checkout(UUID customerId, UUID productId, int quantity) {
        return Map.of("customerId", customerId, "items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    private OrderModels.CreateRequest createRequest(UUID customerId, UUID productId, int quantity) {
        return new OrderModels.CreateRequest(customerId, List.of(new OrderModels.ItemRequest(productId, quantity)));
    }
}
