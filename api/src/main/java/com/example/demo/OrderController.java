package com.example.demo;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.OrderDtos.CreateOrder;
import com.example.demo.OrderDtos.OrderView;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public List<OrderView> list(
            @AuthenticationPrincipal Jwt identity,

            @RequestParam(name = "limit", defaultValue = "20")
            @Min(1) @Max(100) int limit,

            @RequestParam(name = "offset", defaultValue = "0")
            @Min(0) @Max(10000) int offset) {

        return orders.list(identity, limit, offset);
    }

    @GetMapping("/{id}")
    public OrderView get(
            @AuthenticationPrincipal Jwt identity,
            @PathVariable("id") UUID id) {

        return orders.get(identity, id);
    }

    @PostMapping
    public ResponseEntity<OrderView> create(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CreateOrder request) {

        var result = orders.create(identity, key, request);

        return ResponseEntity
                .status(result.replayed() ? 200 : 201)
                .location(URI.create(
                        "/api/orders/" + result.order().id()))
                .header(
                        "Idempotency-Replayed",
                        Boolean.toString(result.replayed()))
                .body(result.order());
    }
}