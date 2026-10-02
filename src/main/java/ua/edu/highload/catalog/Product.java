package ua.edu.highload.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Product(UUID id, String sku, String name, String description, BigDecimal price,
                      long stock, boolean active, Instant createdAt) { }
