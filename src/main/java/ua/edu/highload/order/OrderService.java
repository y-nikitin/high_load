package ua.edu.highload.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.highload.catalog.Product;
import ua.edu.highload.catalog.ProductRepository;
import ua.edu.highload.common.ApiException;
import ua.edu.highload.common.PageResponse;
import ua.edu.highload.customer.CustomerService;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final CustomerService customers;

    public OrderService(OrderRepository orders, ProductRepository products, CustomerService customers) {
        this.orders = orders;
        this.products = products;
        this.customers = customers;
    }

    @Transactional
    public OrderModels.Details create(OrderModels.CreateRequest request) {
        customers.get(request.customerId());
        Map<UUID, Integer> quantities = new HashMap<>();
        for (OrderModels.ItemRequest item : request.items()) {
            if (quantities.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw ApiException.badRequest("Each product must appear only once in an order");
            }
        }

        List<Product> lockedProducts = products.lockAll(quantities.keySet());
        if (lockedProducts.size() != quantities.size()) {
            throw ApiException.notFound("One or more products");
        }
        List<OrderModels.Item> items = new ArrayList<>();
        for (Product product : lockedProducts) {
            int quantity = quantities.get(product.id());
            if (!product.active()) {
                throw ApiException.conflict("Product is no longer available: " + product.sku());
            }
            if (product.stock() < quantity) {
                throw ApiException.conflict("Insufficient stock for product: " + product.sku());
            }
            items.add(new OrderModels.Item(UUID.randomUUID(), product.id(), product.name(),
                    product.price(), quantity));
        }

        BigDecimal total = items.stream().map(OrderModels.Item::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderModels.Summary order = orders.insert(request.customerId(), total);
        for (OrderModels.Item item : items) {
            products.changeStock(item.productId(), -item.quantity());
            orders.insertItem(order.id(), item);
        }
        return OrderModels.Details.of(order, items);
    }

    @Transactional(readOnly = true)
    public OrderModels.Details get(UUID id) {
        OrderModels.Summary order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order"));
        return OrderModels.Details.of(order, orders.items(id));
    }

    public PageResponse<OrderModels.Summary> list(UUID customerId, int page, int size) {
        int offset = PageResponse.offset(page, size);
        customers.get(customerId);
        return PageResponse.from(orders.list(customerId, offset, size + 1), page, size);
    }

    @Transactional
    public OrderModels.Details cancel(UUID id) {
        OrderModels.Summary order = orders.lockById(id).orElseThrow(() -> ApiException.notFound("Order"));
        List<OrderModels.Item> items = orders.items(id);
        if (order.status() == OrderModels.Status.CANCELLED) {
            return OrderModels.Details.of(order, items);
        }
        products.lockAll(items.stream().map(OrderModels.Item::productId).toList());
        for (OrderModels.Item item : items) {
            products.changeStock(item.productId(), item.quantity());
        }
        return OrderModels.Details.of(orders.cancel(id), items);
    }
}
