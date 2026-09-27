package com.grabfood.dish.service;

import com.grabfood.dish.entity.Dish;
import com.grabfood.dish.repository.DishRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DishService {

    private static final Logger log = LoggerFactory.getLogger(DishService.class);

    private final DishRepository dishRepository;

    public DishService(DishRepository dishRepository) {
        this.dishRepository = dishRepository;
    }

    /**
     * Lấy thông tin món ăn theo ID.
     * Áp dụng @Cacheable:
     * - Khi Redis hoạt động: Đọc từ cache nếu có (Cache Hit), hoặc đọc DB rồi nạp cache (Cache Miss).
     * - Khi Redis sập (Connection Refused/Timeout): CustomCacheErrorHandler bắt lỗi, trả về null.
     *   Phương thức này tự động fallback gọi CSDL bình thường (Silent Fail), không gây lỗi 500!
     */
    @Cacheable(value = "dishes", key = "#id")
    public Dish getDishById(Long id) {
        log.info("==========================================================================");
        log.info(">>> [DATABASE SELECT QUERY] Đang truy vấn trực tiếp CSDL cho dish id: {}", id);
        log.info("==========================================================================");

        return dishRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy món ăn với ID: " + id));
    }

    /**
     * Cập nhật thông tin món ăn.
     * Áp dụng @CacheEvict:
     * - Khi Redis sập: Cập nhật DB vẫn thành công, CustomCacheErrorHandler bắt lỗi xóa cache và bỏ qua.
     */
    @Transactional
    @CacheEvict(value = "dishes", key = "#dish.id")
    public Dish updateDish(Dish dish) {
        log.info(">>> [DATABASE UPDATE] Đang cập nhật món ăn vào CSDL: id={}", dish.getId());
        return dishRepository.save(dish);
    }
}
