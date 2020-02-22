package com.example.demo;

import com.example.demo.limiter.UserLevelRedisRateLimiter;
import com.example.demo.util.RemoteAddrKeyResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import org.springframework.validation.Validator;
import java.util.List;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    KeyResolver userKeyResolver() {
        return new RemoteAddrKeyResolver();
    }
    @Bean
    @Primary
        //使用自己定义的限流类
    UserLevelRedisRateLimiter userLevelRedisRateLimiter(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Qualifier(UserLevelRedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            @Qualifier("defaultValidator") Validator validator){
        return new UserLevelRedisRateLimiter(redisTemplate , script , validator);
    }

}
