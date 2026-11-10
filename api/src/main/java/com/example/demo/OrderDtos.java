package com.example.demo;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrder(
            @NotBlank
            @Size(max = 120)
            String product,

            @NotNull
            @Min(1)
            @Max(100)
            Integer quantity) {

        public CreateOrder {
            product = product == null ? null : product.strip();
        }
    }

    public record OrderView(
            UUID id,
            String product,
            int quantity,
            OffsetDateTime createdAt) {
    }

    public record CreateResult(
            OrderView order,
            boolean replayed) {
    }
}