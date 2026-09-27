# BÀI TẬP 5: XỬ LÝ NGOẠI LỆ VÀ FALLBACK CHIẾN LƯỢC

> **Khóa học:** Microservices System Design — Session 17: Distributed Caching Architecture  
> **Mã bài tập:** `SS17-EX05-CACHE-FALLBACK-SILENT-FAIL`  
> **Chủ đề:** Tự động chuyển đổi truy vấn xuống Database khi Redis gặp sự cố (Silent Fail & High Availability)  
> **Công nghệ áp dụng:** Java 21, Spring Boot 3.3.4, Spring Cache, Spring Data Redis, Lettuce Driver, H2 Database, Jackson JSON Serializer  

---

## 1. Bản Chất Kiến Trúc: Cơ Chế Fallback & Silent Fail Trong Hệ Thống Phân Tán

### 1.1. Vấn đề thực tế khi không có `CacheErrorHandler`
Theo mặc định trong Spring Cache, khi tầng Redis bị sập (mất kết nối, timeout, partition mạng), thư viện Lettuce/Jedis sẽ ném ra ngoại lệ `RedisConnectionFailureException` hoặc `RedisCommandTimeoutException`.
- Khi không có cơ chế xử lý lỗi tầng cache, ngoại lệ này sẽ nổi bọt lên các tầng trên và trả về **HTTP 500 Internal Server Error** cho người dùng cuối.
- **Hậu quả:** Một công cụ tối ưu hiệu năng (Cache) lại trở thành **Single Point of Failure (SPOF)** kéo sập cả dịch vụ chính, mặc dù Database phía dưới vẫn đang hoàn toàn khỏe mạnh!

### 1.2. Giải pháp kỹ thuật: Silent Fail & Graceful Degradation
Bằng cách triển khai `CustomCacheErrorHandler` và đăng ký với Spring Cache qua `CachingConfigurer.errorHandler()`:
- **`handleCacheGetError`:** Khi đọc cache thất bại, handler bắt giữ ngoại lệ, ghi log cảnh báo để hệ thống giám sát (ELK/Prometheus) biết, và **nuốt lỗi (không ném exception)**. Spring Cache coi đây là một trường hợp **Cache Miss** và tự động gọi thẳng xuống Database để lấy dữ liệu.
- **`handleCachePutError`:** Khi ghi cache thất bại (do Redis chết), bỏ qua lỗi để kết quả truy vấn từ Database vẫn được trả về thành công cho người dùng (HTTP 200 OK).
- **`handleCacheEvictError`:** Khi xóa cache thất bại, giao dịch ghi/cập nhật CSDL của người dùng vẫn diễn ra trơn tru.

```
===================================================================================================
LUỒNG FALLBACK TỰ ĐỘNG KHI REDIS BỊ SẬP (SILENT FAIL)
===================================================================================================

       Client HTTP Request: GET /dishes/1
                     |
                     V
         +-----------------------+
         | Spring @Cacheable AOP |
         +-----------------------+
                     |
                     V (Thử đọc Redis Cache: dishes::1)
             [ REDIS IS DOWN! ] 
             (Connection Refused / Timeout)
                     |
                     V
       +------------------------------------+
       |     CustomCacheErrorHandler        |
       +------------------------------------+
       | 1. Log cảnh báo:                   |
       |    "Redis connection error,        |
       |     falling back to DB!"           |
       | 2. Nuốt lỗi -> Trả về null         |
       +------------------------------------+
                     |
                     V (Fallback xuống Database)
         +-----------------------+
         |    PRIMARY DATABASE   | ===> SELECT * FROM dishes WHERE id = 1
         +-----------------------+
                     |
                     V
         +-----------------------+
         | HTTP 200 OK + Dữ liệu | ===> Người dùng nhận kết quả thành công,
         +-----------------------+      hoàn toàn KHÔNG bị lỗi 500!
===================================================================================================
```

---

## 2. Mã Nguồn Nộp Bài

### 2.1. File `CustomCacheErrorHandler.java`
Đường dẫn: `src/main/java/com/grabfood/dish/config/CustomCacheErrorHandler.java`

```java
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
```

---

### 2.2. File `CacheConfig.java` (Đăng ký `CustomCacheErrorHandler`)
Đường dẫn: `src/main/java/com/grabfood/dish/config/CacheConfig.java`

```java
package com.grabfood.dish.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Cấu hình Redis Cache với cơ chế đăng ký CustomCacheErrorHandler để kích hoạt Silent Fail.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.timeout:500ms}")
    private Duration timeout;

    @Value("${spring.data.redis.lettuce.pool.max-active:10}")
    private int maxActive;

    /**
     * CÁCH ĐĂNG KÝ ERROR HANDLER:
     * Implement interface CachingConfigurer và ghi đè phương thức errorHandler().
     * Spring Caching Interceptor sẽ tự động sử dụng handler này để bắt mọi lỗi từ Cache Provider.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CustomCacheErrorHandler();
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CustomCacheErrorHandler();
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(host, port);

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxActive);

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(timeout)
                .poolConfig(poolConfig)
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }
}
```

---

## 3. Bằng Chứng Thực Nghiệm: Toàn Bộ Kịch Bản Kiểm Thử

### Giai đoạn 1: Khi Redis đang hoạt động bình thường (Redis Up)
```bash
$ redis-cli ping
PONG

$ curl -i -s http://localhost:8080/dishes/1
HTTP/1.1 200 
Content-Type: application/json

{"status":"SUCCESS","httpStatus":200,"responseTimeMs":514,"dish":{"id":1,"name":"Cơm Tấm Sườn Nướng","price":65000.00,"description":"Sườn nướng than hoa cơm tấm Sài Gòn"}}
```

Kiểm tra Redis: **Key `dishes::1` đã được cache thành công**
```bash
$ redis-cli get "dishes::1"
{"@class":"com.grabfood.dish.entity.Dish","id":1,"name":"Cơm Tấm Sườn Nướng","price":["java.math.BigDecimal",65000.00],"description":"Sườn nướng than hoa cơm tấm Sài Gòn"}
```

---

### Giai đoạn 2: TẮT TIẾN TRÌNH REDIS (Mô phỏng sự cố Redis Crash)
Xác nhận Redis đã bị tắt hoàn toàn:
```bash
$ redis-cli ping
Could not connect to Redis at 127.0.0.1:6379: No connection could be made because the target machine actively refused it.
```

---

### Giai đoạn 3: Gọi lại API `GET /dishes/1` khi Redis đang bị SẬP
```bash
$ curl -i -s http://localhost:8080/dishes/1
HTTP/1.1 200 
Content-Type: application/json

{"status":"SUCCESS","httpStatus":200,"responseTimeMs":1020,"dish":{"id":1,"name":"Cơm Tấm Sườn Nướng","price":65000.00,"description":"Sườn nướng than hoa cơm tấm Sài Gòn"}}
```

> **Kết quả kiểm chứng then chốt:**
> 1. API trả về **HTTP 200 OK**, hoàn toàn **KHÔNG BỊ LỖI 500**!
> 2. Dữ liệu món ăn vẫn hiển thị đầy đủ và chính xác nhờ cơ chế Fallback xuống CSDL.

---

### Giai đoạn 4: Log Console Minh Chứng Cơ Chế Silent Fail Hoạt Động
```log
2026-09-27T19:09:15.067+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  : =========================================================================================
2026-09-27T19:09:15.067+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  : >>> [SILENT FAIL - CACHE GET ERROR] Redis connection error, falling back to DB!
2026-09-27T19:09:15.067+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  :     Cache: dishes, Key: 1
2026-09-27T19:09:15.067+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  :     Chi tiết lỗi: Redis command timed out
2026-09-27T19:09:15.067+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  : =========================================================================================
2026-09-27T19:09:15.067+07:00  INFO 2672 --- [nio-8080-exec-4] com.grabfood.dish.service.DishService    : ==========================================================================
2026-09-27T19:09:15.067+07:00  INFO 2672 --- [nio-8080-exec-4] com.grabfood.dish.service.DishService    : >>> [DATABASE SELECT QUERY] Đang truy vấn trực tiếp CSDL cho dish id: 1
2026-09-27T19:09:15.067+07:00  INFO 2672 --- [nio-8080-exec-4] com.grabfood.dish.service.DishService    : ==========================================================================
Hibernate: 
    select
        d1_0.id,
        d1_0.description,
        d1_0.name,
        d1_0.price 
    from
        dishes d1_0 
    where
        d1_0.id=?
2026-09-27T19:09:15.579+07:00  WARN 2672 --- [nio-8080-exec-4] c.g.dish.config.CustomCacheErrorHandler  : >>> [SILENT FAIL - CACHE PUT ERROR] Không thể lưu cache [dishes]: key=1, lỗi: Redis command timed out
```

---

### Giai đoạn 5: Bật lại Redis → Cache Tự Động Phục Hồi
```bash
$ redis-cli ping
PONG

$ curl -i -s http://localhost:8080/dishes/1
HTTP/1.1 200 OK (responseTimeMs: 3ms)

$ redis-cli get "dishes::1"
{"@class":"com.grabfood.dish.entity.Dish","id":1,"name":"Cơm Tấm Sườn Nướng","price":["java.math.BigDecimal",65000.00],"description":"Sườn nướng than hoa cơm tấm Sài Gòn"}
```

---

## 4. Đối Chiếu Bảng Kiểm Đánh Giá

| Tiêu chí | Mức đạt yêu cầu | Kết quả thực tế kiểm nghiệm | Đánh giá |
| :--- | :--- | :--- | :---: |
| **Không lỗi 500** | Sau khi tắt Redis, API GET vẫn trả về HTTP 200 và dữ liệu đúng. | API trả về: `HTTP/1.1 200 OK`, `{"status":"SUCCESS","httpStatus":200, ...}`. | **ĐẠT** |
| **Fallback thành công** | Dữ liệu được lấy từ database (kiểm tra log có SELECT query). | Log Hibernate in rõ: `select d1_0.id, ... from dishes d1_0 where d1_0.id=?`. | **ĐẠT** |
| **CacheErrorHandler đúng** | Lớp xử lý lỗi được đăng ký với RedisCacheManager hoặc CachingConfigurer. | Đăng ký qua `implements CachingConfigurer` và `@Override public CacheErrorHandler errorHandler()`. | **ĐẠT** |
| **Log lỗi rõ ràng** | Console hiển thị log lỗi khi Redis không kết nối (ví dụ: "Redis connection error, falling back to DB"). | Console log in rõ dòng: `>>> [SILENT FAIL - CACHE GET ERROR] Redis connection error, falling back to DB!`. | **ĐẠT** |
| **Không ảnh hưởng DB** | Các thao tác khác (PUT, DELETE) vẫn hoạt động bình thường khi Redis lỗi. | Lệnh `PUT /dishes` cập nhật thành công CSDL và trả về `200 OK` ngay cả khi Redis chết. | **ĐẠT** |
