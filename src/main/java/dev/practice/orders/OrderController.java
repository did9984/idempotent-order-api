package dev.practice.orders;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrderController {
  private final OrderService service;
  private final JdbcTemplate db;

  public OrderController(OrderService service, JdbcTemplate db) {
    this.service = service;
    this.db = db;
  }

  @GetMapping("/products")
  public List<Map<String, Object>> products() {
    return db.queryForList("SELECT * FROM products ORDER BY id");
  }

  @GetMapping("/orders")
  public List<OrderService.Order> list() {
    return service.list();
  }

  @PostMapping("/orders")
  public ResponseEntity<OrderService.Result> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody OrderService.Request req) {
    var result = service.create(key, req);
    return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
  }
}
