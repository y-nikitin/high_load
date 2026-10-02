package ua.edu.highload.catalog;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.highload.common.PageResponse;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Створити товар; SKU має бути унікальним")
    public ResponseEntity<Product> create(@Valid @RequestBody ProductRequests.Create request) {
        Product product = service.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
    }

    @GetMapping
    @Operation(summary = "Каталог активних товарів із пагінацією (page від 0, size 1–100)")
    public PageResponse<Product> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Отримати активний товар")
    public Product get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Замінити назву, опис і ціну товару; SKU та залишок не змінюються")
    public Product update(@PathVariable UUID id, @Valid @RequestBody ProductRequests.Update request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/restock")
    @Operation(summary = "Атомарно збільшити залишок товару")
    public Product restock(@PathVariable UUID id, @Valid @RequestBody ProductRequests.Restock request) {
        return service.restock(id, request.quantity());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Прибрати товар із продажу, зберігши історію замовлень")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
