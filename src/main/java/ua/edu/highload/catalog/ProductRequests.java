package ua.edu.highload.catalog;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class ProductRequests {
    private ProductRequests() { }

    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,39}") String sku,
            @NotBlank @Size(max = 160) String name,
            @NotNull @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull @Min(0) @Max(1000000000) Long stock) { }

    public record Update(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price) { }

    public record Restock(@NotNull @Min(1) @Max(1000000000) Long quantity) { }
}
