package ua.edu.highload.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ua.edu.highload.common.PageResponse;

/** Shared cache-aside. A new namespace invalidates all page/size variants without Redis KEYS/SCAN. */
@Component
public class CatalogCache {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogCache.class);
    private static final String PREFIX = "orders:catalog:v1:";
    private static final String EPOCH = PREFIX + "epoch";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final JavaType pageType;
    private final boolean enabled;
    private final Duration ttl;

    public CatalogCache(StringRedisTemplate redis, ObjectMapper json,
                        @Value("${app.cache.enabled:false}") boolean enabled,
                        @Value("${app.cache.ttl-seconds:30}") long ttlSeconds) {
        if (ttlSeconds < 1 || ttlSeconds > 3600) {
            throw new IllegalArgumentException("CACHE_TTL_SECONDS must be between 1 and 3600");
        }
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.pageType = json.getTypeFactory().constructParametricType(PageResponse.class, Product.class);
    }

    public record Result(PageResponse<Product> body, String status) { }

    public Result read(int page, int size, Supplier<PageResponse<Product>> database) {
        if (!enabled) return new Result(database.get(), "BYPASS");
        String key;
        try {
            String epoch = redis.opsForValue().get(EPOCH);
            if (epoch == null) {
                String proposed = UUID.randomUUID().toString();
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(EPOCH, proposed))) {
                    epoch = proposed;
                } else {
                    epoch = redis.opsForValue().get(EPOCH);
                }
            }
            if (epoch == null) throw new IllegalStateException("Cache namespace is unavailable");
            key = PREFIX + epoch + ":page:" + page + ":size:" + size;
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                PageResponse<Product> value = json.readValue(cached, pageType);
                if (value != null && value.page() == page && value.size() == size && value.items() != null) {
                    LOG.debug("Catalog cache HIT {}", key);
                    return new Result(value, "HIT");
                }
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            LOG.debug("Catalog cache unavailable; reading PostgreSQL", exception);
            return new Result(database.get(), "BYPASS");
        }

        // Do not catch database errors as cache failures, or repeat the database query on a cache SET failure.
        PageResponse<Product> value = database.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
            LOG.debug("Catalog cache MISS {}", key);
            return new Result(value, "MISS");
        } catch (RuntimeException | JsonProcessingException exception) {
            LOG.debug("Catalog cache write failed; returning PostgreSQL result", exception);
            return new Result(value, "BYPASS");
        }
    }

    public void afterWrite() {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Checkout updates several rows: invalidate once, and only if the transaction commits.
            boolean registered = TransactionSynchronizationManager.getSynchronizations().stream()
                    .anyMatch(sync -> sync instanceof InvalidateCatalog);
            if (!registered) TransactionSynchronizationManager.registerSynchronization(new InvalidateCatalog());
        } else {
            invalidate(); // An autocommit INSERT/UPDATE has already succeeded.
        }
    }

    private void invalidate() {
        try {
            // UUID avoids reusing an old namespace if the epoch itself was evicted or Redis restarted.
            redis.opsForValue().set(EPOCH, UUID.randomUUID().toString());
            LOG.info("Catalog cache INVALIDATED after committed product mutation");
        } catch (RuntimeException exception) {
            // The committed business operation must not fail because an optional cache is offline.
            LOG.warn("Catalog invalidation failed; cached pages may remain stale until their TTL expires");
        }
    }

    private final class InvalidateCatalog implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            invalidate();
        }
    }
}
