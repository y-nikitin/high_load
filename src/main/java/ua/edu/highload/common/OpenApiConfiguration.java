package ua.edu.highload.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI orderApi() {
        return new OpenAPI().info(new Info()
                .title("Order Service — лабораторна робота №1")
                .version("1.0.0")
                .description("Клієнти, каталог та транзакційні замовлення. Усі ціни в UAH. "
                        + "Навчальний API без автентифікації; запуск лише в локальному середовищі."));
    }
}
