package com.example.demo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.OrderDtos.CreateOrder;
import com.example.demo.OrderDtos.CreateResult;
import com.example.demo.OrderDtos.OrderView;

@Service
public class OrderService {

    private static final RowMapper<OrderView> ORDER_MAPPER =
            (rs, rowNumber) -> new OrderView(
                    rs.getObject("id", UUID.class),
                    rs.getString("product"),
                    rs.getInt("quantity"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );

    private final JdbcClient jdbc;

    public OrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OrderView> list(Jwt identity, int limit, int offset) {
        UUID owner = resolveOwner(identity);

        return jdbc.sql("""
                SELECT id, product, quantity, created_at
                FROM app_order
                WHERE owner_id = :owner
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("owner", owner)
                .param("limit", limit)
                .param("offset", offset)
                .query(ORDER_MAPPER)
                .list();
    }

    @Transactional
    public OrderView get(Jwt identity, UUID orderId) {
        UUID owner = resolveOwner(identity);

        return jdbc.sql("""
                SELECT id, product, quantity, created_at
                FROM app_order
                WHERE id = :id
                  AND owner_id = :owner
                """)
                .param("id", orderId)
                .param("owner", owner)
                .query(ORDER_MAPPER)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Order not found"));
    }

    @Transactional(timeout = 10)
    public CreateResult create(
            Jwt identity,
            UUID idempotencyKey,
            CreateOrder request) {

        UUID owner = resolveOwner(identity);

        /*
         * Serialize creation for this owner.
         *
         * The lock remains held until this transaction commits.
         * Concurrent retries cannot both pass the lookup and insert.
         */
        jdbc.sql("""
                SELECT id
                FROM app_user
                WHERE id = :owner
                FOR UPDATE
                """)
                .param("owner", owner)
                .query(UUID.class)
                .single();

        var existing = jdbc.sql("""
                SELECT id, product, quantity, created_at
                FROM app_order
                WHERE owner_id = :owner
                  AND idempotency_key = :key
                """)
                .param("owner", owner)
                .param("key", idempotencyKey)
                .query(ORDER_MAPPER)
                .optional();

        if (existing.isPresent()) {
            OrderView order = existing.get();

            boolean sameRequest =
                    order.product().equals(request.product())
                            && order.quantity() == request.quantity();

            if (!sameRequest) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key was already used with a different request");
            }

            return new CreateResult(order, true);
        }

        OrderView created = jdbc.sql("""
                INSERT INTO app_order (
                    id,
                    owner_id,
                    idempotency_key,
                    product,
                    quantity
                )
                VALUES (
                    :id,
                    :owner,
                    :key,
                    :product,
                    :quantity
                )
                RETURNING id, product, quantity, created_at
                """)
                .param("id", UUID.randomUUID())
                .param("owner", owner)
                .param("key", idempotencyKey)
                .param("product", request.product())
                .param("quantity", request.quantity())
                .query(ORDER_MAPPER)
                .single();

        return new CreateResult(created, false);
    }

    private UUID resolveOwner(Jwt identity) {
        if (identity.getIssuer() == null
                || identity.getSubject() == null
                || identity.getSubject().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Token must contain an issuer and subject");
        }

        String issuer = identity.getIssuer().toString();
        String subject = identity.getSubject();

        /*
         * Provision the application user on first access.
         * The unique constraint handles concurrent first requests.
         */
        jdbc.sql("""
                INSERT INTO app_user (
                    id,
                    identity_issuer,
                    identity_subject
                )
                VALUES (:id, :issuer, :subject)
                ON CONFLICT (identity_issuer, identity_subject)
                DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("issuer", issuer)
                .param("subject", subject)
                .update();

        return jdbc.sql("""
                SELECT id
                FROM app_user
                WHERE identity_issuer = :issuer
                  AND identity_subject = :subject
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(UUID.class)
                .single();
    }
}