package com.grabfood.dish;

import com.grabfood.dish.config.CustomCacheErrorHandler;
import com.grabfood.dish.entity.Dish;
import com.grabfood.dish.service.DishService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.interceptor.CacheErrorHandler;

@SpringBootTest
class FaultToleranceTest {

    @Autowired
    private DishService dishService;

    @Autowired
    private CacheErrorHandler cacheErrorHandler;

    @Test
    @DisplayName("Kiểm tra CustomCacheErrorHandler được đăng ký chính xác")
    void testErrorHandlerRegistered() {
        Assertions.assertNotNull(cacheErrorHandler, "CacheErrorHandler phải được đăng ký");
        Assertions.assertTrue(cacheErrorHandler instanceof CustomCacheErrorHandler,
                "CacheErrorHandler phải là thể hiện của CustomCacheErrorHandler");
    }

    @Test
    @DisplayName("Kiểm tra CustomCacheErrorHandler nuốt lỗi (Silent Fail) không ném exception")
    void testSilentFailDoesNotThrow() {
        CustomCacheErrorHandler handler = new CustomCacheErrorHandler();
        RuntimeException dummyEx = new RuntimeException("Redis connection refused: localhost:6379");

        // Gọi các phương thức xử lý lỗi, đảm bảo KHÔNG có ngoại lệ nào bị ném ra ngoài
        Assertions.assertDoesNotThrow(() -> handler.handleCacheGetError(dummyEx, null, "dishes::1"));
        Assertions.assertDoesNotThrow(() -> handler.handleCachePutError(dummyEx, null, "dishes::1", "value"));
        Assertions.assertDoesNotThrow(() -> handler.handleCacheEvictError(dummyEx, null, "dishes::1"));
        Assertions.assertDoesNotThrow(() -> handler.handleCacheClearError(dummyEx, null));
    }

    @Test
    @DisplayName("Kiểm tra đọc dữ liệu món ăn thành công từ CSDL")
    void testGetDishById() {
        Dish dish = dishService.getDishById(1L);
        Assertions.assertNotNull(dish);
        Assertions.assertEquals("Cơm Tấm Sườn Nướng", dish.getName());
    }
}
