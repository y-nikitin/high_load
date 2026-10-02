package ua.edu.highload.customer;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ua.edu.highload.common.ApiException;

@Service
public class CustomerService {
    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    public Customer create(CreateCustomerRequest request) {
        return repository.insert(request.name().strip(), request.email().strip().toLowerCase(Locale.ROOT));
    }

    public Customer get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
    }
}
