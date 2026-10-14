package dev.practice.orders;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
  private final JdbcTemplate db;

  public OrderService(JdbcTemplate db) {
    this.db = db;
  }

  public record Request(@NotNull @Positive Long productId, @Min(1) @Max(1000) int quantity) {}

  public record Order(
      String id, long productId, int quantity, BigDecimal total, LocalDateTime createdAt) {}

  public record Result(Order order, boolean replayed) {}

  @Transactional
  public Result create(String key, Request req) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new ResponseStatusException(
          BAD_REQUEST, "Idempotency-Key must contain 1-100 ASCII letters, digits, or . _ : -");
    var product =
        db.queryForList("SELECT price,stock FROM products WHERE id=? FOR UPDATE", req.productId());
    if (product.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Product not found");
    var existing =
        db.query(
            "SELECT * FROM orders WHERE request_key=?",
            (rs, n) ->
                new Order(
                    rs.getString("id"),
                    rs.getLong("product_id"),
                    rs.getInt("quantity"),
                    rs.getBigDecimal("total"),
                    rs.getObject("created_at", LocalDateTime.class)),
            key);
    if (!existing.isEmpty()) {
      var order = existing.get(0);
      if (order.productId() != req.productId() || order.quantity() != req.quantity())
        throw new ResponseStatusException(
            CONFLICT, "Idempotency-Key was already used with a different request");
      return new Result(order, true);
    }
    if (((Number) product.get(0).get("STOCK")).intValue() < req.quantity())
      throw new ResponseStatusException(CONFLICT, "Insufficient stock");
    var total =
        ((BigDecimal) product.get(0).get("PRICE")).multiply(BigDecimal.valueOf(req.quantity()));
    var order =
        new Order(
            UUID.randomUUID().toString(),
            req.productId(),
            req.quantity(),
            total,
            LocalDateTime.now().withNano(0));
    db.update("UPDATE products SET stock=stock-? WHERE id=?", req.quantity(), req.productId());
    db.update(
        "INSERT INTO orders VALUES(?,?,?,?,?,?)",
        order.id(),
        key,
        order.productId(),
        order.quantity(),
        order.total(),
        order.createdAt());
    return new Result(order, false);
  }

  public List<Order> list() {
    return db.query(
        "SELECT * FROM orders ORDER BY created_at,id",
        (rs, n) ->
            new Order(
                rs.getString("id"),
                rs.getLong("product_id"),
                rs.getInt("quantity"),
                rs.getBigDecimal("total"),
                rs.getObject("created_at", LocalDateTime.class)));
  }
}
