package ua.edu.highload.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderModels {
    private OrderModels() { }

    public enum Status { PLACED, CANCELLED }

    public record CreateRequest(@NotNull UUID customerId,
                                @NotEmpty @Size(max = 50) List<@NotNull @Valid ItemRequest> items) { }

    public record ItemRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(10000) Integer quantity) { }

    public record Summary(UUID id, UUID customerId, Status status, BigDecimal total, Instant createdAt) { }

    public record Item(UUID id, UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        public BigDecimal subtotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public record Details(UUID id, UUID customerId, Status status, BigDecimal total,
                          Instant createdAt, List<Item> items) {
        public static Details of(Summary summary, List<Item> items) {
            return new Details(summary.id(), summary.customerId(), summary.status(), summary.total(),
                    summary.createdAt(), List.copyOf(items));
        }
    }
}
