package ua.edu.highload.order;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderItemTest {
    @Test
    void calculatesMoneyWithoutFloatingPointRounding() {
        var item = new OrderModels.Item(UUID.randomUUID(), UUID.randomUUID(), "Cable",
                new BigDecimal("0.10"), 3);
        assertThat(item.subtotal()).isEqualByComparingTo("0.30");
    }

    @Test
    void supportsLargestAllowedLineTotal() {
        var item = new OrderModels.Item(UUID.randomUUID(), UUID.randomUUID(), "Equipment",
                new BigDecimal("9999999999.99"), 10000);
        assertThat(item.subtotal()).isEqualByComparingTo("99999999999900.00");
    }
}
