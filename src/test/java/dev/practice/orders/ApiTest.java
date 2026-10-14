package dev.practice.orders;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;

  @Autowired OrderService service;
  private static final String BODY =
      """
      {"productId":1,"quantity":2}
      """;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM orders");
    db.update("UPDATE products SET stock=10 WHERE id=1");
    db.update("UPDATE products SET stock=20 WHERE id=2");
  }

  @Test
  void retryReplaysAndDeductsStockOnce() throws Exception {
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "demo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.order.total").value(100000))
        .andExpect(jsonPath("$.replayed").value(false));
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "demo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true));
    assertThat(db.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class))
        .isEqualTo(8);
    assertThat(service.list()).hasSize(1);
  }

  @Test
  void differentRequestWithSameKeyIsRejected() throws Exception {
    service.create("same-key", new OrderService.Request(1L, 2));
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("2}", "3}")))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("productId\":1", "productId\":2")))
        .andExpect(status().isConflict());
    assertThat(db.queryForObject("SELECT stock FROM products WHERE id=2", Integer.class))
        .isEqualTo(20);
  }

  @Test
  void insufficientStockDoesNotWriteAnOrder() throws Exception {
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "large")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("2}", "11}")))
        .andExpect(status().isConflict());
    assertThat(service.list()).isEmpty();
    assertThat(db.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class))
        .isEqualTo(10);
  }

  @Test
  void rejectsMissingKeyInvalidQuantityAndMissingProduct() throws Exception {
    mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("2}", "0}")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("productId\":1", "productId\":999")))
        .andExpect(status().isNotFound());
  }

  @Test
  void simultaneousSameKeyOnlyDeductsOnce() throws Exception {
    var results =
        race(i -> service.create("shared", new OrderService.Request(1L, 1)).replayed() ? 200 : 201);
    assertThat(results).filteredOn(code -> code == 201).hasSize(1);
    assertThat(results).filteredOn(code -> code == 200).hasSize(7);
    assertThat(service.list()).hasSize(1);
    assertThat(db.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class))
        .isEqualTo(9);
  }

  @Test
  void simultaneousOrdersNeverOversell() throws Exception {
    var results =
        race(
            i -> {
              try {
                service.create("unique-" + i, new OrderService.Request(1L, 2));
                return 201;
              } catch (org.springframework.web.server.ResponseStatusException e) {
                return e.getStatusCode().value();
              }
            });
    assertThat(results).filteredOn(code -> code == 201).hasSize(5);
    assertThat(results).filteredOn(code -> code == 409).hasSize(3);
    assertThat(db.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class)).isZero();
  }

  private java.util.List<Integer> race(java.util.function.IntFunction<Integer> task)
      throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 8; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return task.apply(index);
                }));
      }
      gate.countDown();
      var results = new java.util.ArrayList<Integer>();
      for (var future : futures) results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
