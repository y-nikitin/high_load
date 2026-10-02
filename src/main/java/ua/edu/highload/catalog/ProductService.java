package ua.edu.highload.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.highload.common.ApiException;
import ua.edu.highload.common.PageResponse;

@Service
public class ProductService {
    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product create(ProductRequests.Create request) {
        return repository.insert(request);
    }

    public Product get(UUID id) {
        Product product = repository.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        if (!product.active()) {
            throw ApiException.notFound("Product");
        }
        return product;
    }

    public PageResponse<Product> list(int page, int size) {
        int offset = PageResponse.offset(page, size);
        return PageResponse.from(repository.list(offset, size + 1), page, size);
    }

    @Transactional
    public Product update(UUID id, ProductRequests.Update request) {
        lockActive(id);
        return repository.update(id, request);
    }

    @Transactional
    public Product restock(UUID id, long quantity) {
        lockActive(id);
        return repository.changeStock(id, quantity);
    }

    @Transactional
    public void delete(UUID id) {
        List<Product> products = repository.lockAll(List.of(id));
        if (products.isEmpty()) {
            throw ApiException.notFound("Product");
        }
        repository.deactivate(id);
    }

    private void lockActive(UUID id) {
        List<Product> products = repository.lockAll(List.of(id));
        if (products.isEmpty() || !products.getFirst().active()) {
            throw ApiException.notFound("Product");
        }
    }
}
