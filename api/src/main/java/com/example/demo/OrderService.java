package com.example.demo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.OrderDtos.CreateOrder;
import com.example.demo.OrderDtos.CreateResult;
import com.example.demo.OrderDtos.OrderView;

@Service
@PreAuthorize("denyAll()")
public class OrderService {

    private static final RowMapper<OrderView> ORDER_MAPPER =
            (rs, rowNumber) -> new OrderView(
                    rs.getObject("id", UUID.class),
                    rs.getString("product"),
                    rs.getInt("quantity"),
                    rs.getObject(
                            "created_at",
                            OffsetDateTime.class)
            );

    private final JdbcClient jdbc;
    private final CurrentIdentity identity;

    public OrderService(
            JdbcClient jdbc,
            CurrentIdentity identity) {

        this.jdbc = jdbc;
        this.identity = identity;
    }

    @PreAuthorize("hasAuthority('demo-api:orders.read')")
    @Transactional
    public List<OrderView> list(int limit, int offset) {
        UUID owner = resolveOwner();

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

    @PreAuthorize("hasAuthority('demo-api:orders.read')")
    @Transactional
    public OrderView get(UUID orderId) {
        UUID owner = resolveOwner();

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

    @PreAuthorize("hasAuthority('demo-api:orders.write')")
    @Transactional(timeout = 10)
    public CreateResult create(
            UUID idempotencyKey,
            CreateOrder request) {

        UUID owner = resolveOwner();

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

    private UUID resolveOwner() {
        var jwt = identity.requireJwt();

        String issuer = jwt.getIssuer().toString();
        String subject = jwt.getSubject();

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