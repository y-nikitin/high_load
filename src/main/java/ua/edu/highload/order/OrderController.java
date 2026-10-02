package ua.edu.highload.order;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.highload.common.PageResponse;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Оформити замовлення та атомарно списати залишки")
    public ResponseEntity<OrderModels.Details> create(@Valid @RequestBody OrderModels.CreateRequest request) {
        OrderModels.Details order = service.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Отримати замовлення з позиціями та зафіксованими цінами")
    public OrderModels.Details get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "Історія замовлень клієнта з пагінацією")
    public PageResponse<OrderModels.Summary> list(@RequestParam UUID customerId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return service.list(customerId, page, size);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Скасувати замовлення та повернути залишки рівно один раз")
    public OrderModels.Details cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
