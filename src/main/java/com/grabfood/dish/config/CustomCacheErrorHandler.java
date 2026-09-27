package com.grabfood.dish.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Lớp xử lý ngoại lệ Cache tùy chỉnh (CustomCacheErrorHandler):
 * Đảm bảo nguyên lý Silent Fail & High Availability:
 * Khi cụm Redis gặp sự cố (mất kết nối, timeout, sập node):
 * - Không ném ngoại lệ làm crash request của người dùng (tránh lỗi 500).
 * - Log cảnh báo rõ ràng để hệ thống giám sát ghi nhận.
 * - Tự động Fallback: Spring Cache coi như Cache Miss (trả về null) và tiếp tục thực thi truy vấn Database.
 */
public class CustomCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomCacheErrorHandler.class);

    /**
     * Xử lý lỗi khi đọc Cache (@Cacheable GET):
     * Nuốt lỗi và không rethrow, giúp Spring Cache trả về null và tự động fallback gọi CSDL.
     */
    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("=========================================================================================");
        log.warn(">>> [SILENT FAIL - CACHE GET ERROR] Redis connection error, falling back to DB!");
        log.warn("    Cache: {}, Key: {}", cache != null ? cache.getName() : "unknown", key);
        log.warn("    Chi tiết lỗi: {}", exception.getMessage());
        log.warn("=========================================================================================");
    }

    /**
     * Xử lý lỗi khi ghi dữ liệu vào Cache (@CachePut hoặc sau khi @Cacheable query DB xong):
     * Bỏ qua lỗi ghi cache, đảm bảo kết quả từ CSDL vẫn được trả về thành công cho client.
     */
    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn(">>> [SILENT FAIL - CACHE PUT ERROR] Không thể lưu cache [{}]: key={}, lỗi: {}",
                cache != null ? cache.getName() : "unknown", key, exception.getMessage());
    }

    /**
     * Xử lý lỗi khi xóa Cache (@CacheEvict):
     * Bỏ qua lỗi xóa cache, đảm bảo giao dịch cập nhật CSDL vẫn thành công (200 OK).
     */
    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn(">>> [SILENT FAIL - CACHE EVICT ERROR] Không thể xóa cache [{}]: key={}, lỗi: {}",
                cache != null ? cache.getName() : "unknown", key, exception.getMessage());
    }

    /**
     * Xử lý lỗi khi clear toàn bộ Cache:
     */
    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn(">>> [SILENT FAIL - CACHE CLEAR ERROR] Không thể clear cache [{}], lỗi: {}",
                cache != null ? cache.getName() : "unknown", exception.getMessage());
    }
}
