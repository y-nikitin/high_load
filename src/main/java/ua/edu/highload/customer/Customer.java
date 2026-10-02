package ua.edu.highload.customer;

import java.time.Instant;
import java.util.UUID;

public record Customer(UUID id, String name, String email, Instant createdAt) { }
