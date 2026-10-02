package ua.edu.highload.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ua.edu.highload.common.PageResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogCacheTest {
    private static final String EPOCH = "orders:catalog:v1:epoch";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PageResponse<Product> page = new PageResponse<>(List.of(), 0, 20, false);
    private CatalogCache cache;

    @BeforeEach
    void configure() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(EPOCH)).thenReturn("revision-a");
        cache = new CatalogCache(redis, json, true, 30);
    }

    @Test
    void missPopulatesCacheWithTtlAndHitDoesNotReadDatabase() throws Exception {
        var reads = new AtomicInteger();
        String key = "orders:catalog:v1:revision-a:page:0:size:20";
        assertThat(cache.read(0, 20, () -> { reads.incrementAndGet(); return page; }).status()).isEqualTo("MISS");
        verify(values).set(eq(key), anyString(), eq(Duration.ofSeconds(30)));
        when(values.get(key)).thenReturn(json.writeValueAsString(page));
        assertThat(cache.read(0, 20, () -> { reads.incrementAndGet(); return page; }).status()).isEqualTo("HIT");
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void failedCacheWriteDoesNotRepeatDatabaseQuery() {
        var reads = new AtomicInteger();
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var result = cache.read(0, 20, () -> { reads.incrementAndGet(); return page; });
        assertThat(result.status()).isEqualTo("BYPASS");
        assertThat(result.body()).isEqualTo(page);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void redisOutageFallsBackAndDisabledCacheNeverContactsRedis() {
        when(values.get(EPOCH)).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.read(0, 20, () -> page).status()).isEqualTo("BYPASS");
        clearInvocations(redis);
        var disabled = new CatalogCache(redis, json, false, 30);
        assertThat(disabled.read(0, 20, () -> page).body()).isEqualTo(page);
        disabled.afterWrite();
        verifyNoInteractions(redis);
    }

    @Test
    void invalidationIsDeferredAndDeduplicatedUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.afterWrite();
            cache.afterWrite();
            verify(values, never()).set(eq(EPOCH), anyString());
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();
            verify(values, times(1)).set(eq(EPOCH), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackTransactionDoesNotInvalidate() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.afterWrite();
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(values, never()).set(eq(EPOCH), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
