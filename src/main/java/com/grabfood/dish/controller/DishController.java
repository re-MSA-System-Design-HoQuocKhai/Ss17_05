package com.grabfood.dish.controller;

import com.grabfood.dish.entity.Dish;
import com.grabfood.dish.service.DishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/dishes")
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    /**
     * API lấy thông tin món ăn: GET /dishes/{id}
     * Hỗ trợ kiểm thử High Availability & Fault Tolerance:
     * - Khi Redis chạy: Trả về HTTP 200, tốc độ siêu nhanh từ cache.
     * - Khi Redis sập: Trả về HTTP 200, dữ liệu tự động fallback từ DB, tuyệt đối không bị lỗi 500!
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDish(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        Dish dish = dishService.getDishById(id);
        long duration = System.currentTimeMillis() - start;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("httpStatus", 200);
        response.put("responseTimeMs", duration);
        response.put("dish", dish);

        return ResponseEntity.ok(response);
    }

    /**
     * API cập nhật món ăn: PUT /dishes
     */
    @PutMapping
    public ResponseEntity<Dish> updateDish(@RequestBody Dish dish) {
        return ResponseEntity.ok(dishService.updateDish(dish));
    }
}
