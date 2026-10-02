package ua.edu.highload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP between two separate JVM containers; never connects to the Compose database. */
class StatelessClusterIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static Network network;
    private static PostgreSQLContainer<?> database;
    private static GenericContainer<?> first;
    private static GenericContainer<?> second;

    @BeforeAll
    static void startCluster() {
        network = Network.newNetwork();
        database = new PostgreSQLContainer<>("postgres:17.4-alpine")
                .withNetwork(network).withNetworkAliases("db");
        database.start();
        // Failsafe runs after package, so this is the same application JAR users build.
        var image = new ImageFromDockerfile("high-load-stateless-it-" + UUID.randomUUID(), true)
                .withFileFromPath("app.jar", Path.of("target/order-service-1.0.0.jar"))
                .withDockerfileFromBuilder(builder -> builder.from("eclipse-temurin:21-jre-alpine")
                        .copy("app.jar", "/app/app.jar").entryPoint("java", "-jar", "/app/app.jar").build());
        first = node(image, "node-1");
        second = node(image, "node-2");
        first.start();
        second.start();
    }

    private static GenericContainer<?> node(ImageFromDockerfile image, String identity) {
        return new GenericContainer<>(image).withNetwork(network).withExposedPorts(8080)
                .withEnv("DB_URL", "jdbc:postgresql://db:5432/" + database.getDatabaseName())
                .withEnv("DB_USERNAME", database.getUsername()).withEnv("DB_PASSWORD", database.getPassword())
                .withEnv("INSTANCE_ID", identity)
                // Extra time to observe the intentionally blocked transaction in the crash test.
                .withCommand("--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '20s'",
                        "--spring.jdbc.template.query-timeout=30s")
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(90));
    }

    @AfterAll
    static void stopCluster() {
        try {
            if (second != null) second.close();
        } finally {
            try {
                if (first != null) first.close();
            } finally {
                try {
                    if (database != null) database.close();
                } finally {
                    if (network != null) network.close();
                }
            }
        }
    }

    @BeforeEach
    void cleanTestData() throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("TRUNCATE order_items, orders, products, customers");
        }
    }

    @Test
    void crossInstanceWorkflowSurvivesLossAndReplacementOfFirstJvm() throws Exception {
        String customerId = customer();
        String productId = product(10);
        assertThat(api(second, "GET", "/api/products/" + productId, null, 200).get("stock").asLong())
                .isEqualTo(10);
        api(second, "PUT", "/api/products/" + productId,
                Map.of("name", "Updated on node 2", "description", "", "price", "12.50"), 200);
        assertThat(api(first, "GET", "/api/products/" + productId, null, 200).get("price").decimalValue())
                .isEqualByComparingTo("12.50");
        String orderId = api(first, "POST", "/api/orders", checkout(customerId, productId, 2), 201)
                .get("id").asText();
        assertThat(api(second, "GET", "/api/orders/" + orderId, null, 200).get("total").decimalValue())
                .isEqualByComparingTo("25.00");

        try {
            killFirst();
            assertThat(api(second, "GET", "/api/orders/" + orderId, null, 200).get("status").asText())
                    .isEqualTo("PLACED");
            api(second, "POST", "/api/orders/" + orderId + "/cancel", null, 200);
        } finally {
            replaceFirst();
        }
        assertThat(api(first, "GET", "/api/orders/" + orderId, null, 200).get("status").asText())
                .isEqualTo("CANCELLED");
        api(first, "POST", "/api/orders/" + orderId + "/cancel", null, 200);
        assertThat(api(second, "GET", "/api/products/" + productId, null, 200).get("stock").asLong())
                .isEqualTo(10);
    }

    @Test
    void twoJvmsCompetingForSameStockCannotOversell() throws Exception {
        String customerId = customer();
        String productId = product(5);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 16; i++) {
                GenericContainer<?> target = i % 2 == 0 ? first : second;
                results.add(executor.submit(() -> {
                    start.await();
                    return exchange(target, "POST", "/api/orders", checkout(customerId, productId, 1)).statusCode();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) {
                int status = result.get(40, TimeUnit.SECONDS);
                assertThat(status).isIn(201, 409);
                if (status == 201) accepted++;
            }
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(api(second, "GET", "/api/products/" + productId, null, 200).get("stock").asLong()).isZero();
        assertThat(api(first, "GET", "/api/orders?customerId=" + customerId, null, 200).get("items").size())
                .isEqualTo(5);
    }

    @Test
    void killDuringUncommittedCheckoutRollsBackAndOtherJvmCanContinue() throws Exception {
        String customerId = customer();
        String productId = product(5);
        String committedId = api(first, "POST", "/api/orders", checkout(customerId, productId, 1), 201)
                .get("id").asText();

        // The temporary trigger exists ONLY inside the isolated Testcontainers database.
        // Pause after INSERT orders and UPDATE products, before INSERT order_items and COMMIT.
        try (Connection blocker = connect(); Statement sql = blocker.createStatement()) {
            sql.execute("SELECT pg_advisory_lock(817263)");
            sql.execute("""
                    CREATE FUNCTION lab2_pause_item() RETURNS trigger AS $$
                    BEGIN PERFORM pg_advisory_xact_lock(817263); RETURN NEW; END;
                    $$ LANGUAGE plpgsql
                    """);
            sql.execute("""
                    CREATE TRIGGER lab2_pause_item BEFORE INSERT ON order_items
                    FOR EACH ROW EXECUTE FUNCTION lab2_pause_item()
                    """);
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> inFlight = executor.submit(
                        () -> exchange(first, "POST", "/api/orders", checkout(customerId, productId, 2)));
                awaitBlockedTransaction(sql);
                killFirst();
                assertThatThrownBy(() -> inFlight.get(35, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
                sql.execute("SELECT pg_advisory_unlock(817263)");

                // Only the previously committed order remains; interrupted deductions were rolled back.
                assertThat(api(second, "GET", "/api/orders?customerId=" + customerId, null, 200).get("items").size())
                        .isEqualTo(1);
                assertThat(api(second, "GET", "/api/orders/" + committedId, null, 200).get("status").asText())
                        .isEqualTo("PLACED");
                assertThat(api(second, "GET", "/api/products/" + productId, null, 200).get("stock").asLong())
                        .isEqualTo(4);
                api(second, "POST", "/api/orders", checkout(customerId, productId, 2), 201);
                assertThat(api(second, "GET", "/api/products/" + productId, null, 200).get("stock").asLong())
                        .isEqualTo(2);
            } finally {
                sql.execute("SELECT pg_advisory_unlock_all()");
                executor.shutdownNow();
                // Stopping the container closes its connections before removing the test trigger.
                first.stop();
                sql.execute("DROP TRIGGER IF EXISTS lab2_pause_item ON order_items");
                sql.execute("DROP FUNCTION IF EXISTS lab2_pause_item()");
                first.start();
            }
        }
        assertThat(api(first, "GET", "/api/products/" + productId, null, 200).get("stock").asLong())
                .isEqualTo(2);
    }

    private void awaitBlockedTransaction(Statement sql) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (var rows = sql.executeQuery("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE application_name = 'node-1' AND state = 'active' AND wait_event = 'advisory')
                    """)) {
                rows.next();
                if (rows.getBoolean(1)) return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Checkout never reached the controlled pre-commit pause");
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private void killFirst() {
        first.getDockerClient().killContainerCmd(first.getContainerId()).withSignal("KILL").exec();
    }

    private void replaceFirst() {
        first.stop();
        first.start();
    }

    private String customer() throws Exception {
        return api(first, "POST", "/api/customers",
                Map.of("name", "Cluster customer", "email", "cluster@example.com"), 201).get("id").asText();
    }

    private String product(long stock) throws Exception {
        return api(first, "POST", "/api/products", Map.of("sku", "CLUSTER-ITEM", "name", "Cluster product",
                "description", "", "price", "10.00", "stock", stock), 201).get("id").asText();
    }

    private Map<String, Object> checkout(String customerId, String productId, int quantity) {
        return Map.of("customerId", customerId, "items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    private JsonNode api(GenericContainer<?> node, String method, String path, Object body, int expected) throws Exception {
        var response = exchange(node, method, path, body);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expected);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> exchange(GenericContainer<?> node, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + node.getHost() + ":" + node.getMappedPort(8080) + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .header("X-Instance-ID", "client-cannot-choose-node")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("X-Instance-ID")).contains(node == first ? "node-1" : "node-2");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        return response;
    }
}
