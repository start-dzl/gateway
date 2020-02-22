package com.example.demo.limiter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ConfigurationProperties("spring.cloud.gateway.redis-rate-limiter")
public class UserLevelRedisRateLimiter extends AbstractRateLimiter<UserLevelRedisRateLimiter.Config> implements ApplicationContextAware {

    /**
     * @deprecated
     */
    @Deprecated
    public static final String REPLENISH_RATE_KEY = "replenishRate";
    /**
     * @deprecated
     */
    @Deprecated
    public static final String BURST_CAPACITY_KEY = "burstCapacity";
    public static final String REDIS_SCRIPT_NAME = "redisRequestRateLimiterScript";
    private Log log = LogFactory.getLog(this.getClass());
    private ReactiveRedisTemplate<String, String> redisTemplate;
    private RedisScript<List<Long>> script;
    private AtomicBoolean initialized = new AtomicBoolean(false);
    private UserLevelRedisRateLimiter.Config defaultConfig;
    private boolean includeHeaders = true;
    private String remainingHeader = "X-RateLimit-Remaining";
    private String replenishRateHeader = "X-RateLimit-Replenish-Rate";
    private String burstCapacityHeader = "X-RateLimit-Burst-Capacity";

    public UserLevelRedisRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate, RedisScript<List<Long>> script, Validator validator) {
        super(UserLevelRedisRateLimiter.Config.class, "redis-rate-limiter", validator);
        this.redisTemplate = redisTemplate;
        this.script = script;
        this.initialized.compareAndSet(false, true);
    }

    public UserLevelRedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity) {
        super(UserLevelRedisRateLimiter.Config.class, "redis-rate-limiter", (Validator) null);
        this.defaultConfig = (new UserLevelRedisRateLimiter.Config()).setReplenishRate(defaultReplenishRate).setBurstCapacity(defaultBurstCapacity);
    }

    static List<String> getKeys(String id) {
        String prefix = "request_rate_limiter.{" + id;
        String tokenKey = prefix + "}.tokens";
        String timestampKey = prefix + "}.timestamp";
        return Arrays.asList(tokenKey, timestampKey);
    }

    public boolean isIncludeHeaders() {
        return this.includeHeaders;
    }

    public void setIncludeHeaders(boolean includeHeaders) {
        this.includeHeaders = includeHeaders;
    }

    public String getRemainingHeader() {
        return this.remainingHeader;
    }

    public void setRemainingHeader(String remainingHeader) {
        this.remainingHeader = remainingHeader;
    }

    public String getReplenishRateHeader() {
        return this.replenishRateHeader;
    }

    public void setReplenishRateHeader(String replenishRateHeader) {
        this.replenishRateHeader = replenishRateHeader;
    }

    public String getBurstCapacityHeader() {
        return this.burstCapacityHeader;
    }

    public void setBurstCapacityHeader(String burstCapacityHeader) {
        this.burstCapacityHeader = burstCapacityHeader;
    }

    public void setApplicationContext(ApplicationContext context) throws BeansException {
        if (this.initialized.compareAndSet(false, true)) {
            this.redisTemplate = (ReactiveRedisTemplate) context.getBean("stringReactiveRedisTemplate", ReactiveRedisTemplate.class);
            this.script = (RedisScript) context.getBean("redisRequestRateLimiterScript", RedisScript.class);
            if (context.getBeanNamesForType(Validator.class).length > 0) {
                this.setValidator((Validator) context.getBean(Validator.class));
            }
        }

    }

    UserLevelRedisRateLimiter.Config getDefaultConfig() {
        return this.defaultConfig;
    }

    public Mono<Response> isAllowed(String routeId, String id) {
        if (!this.initialized.get()) {
            throw new IllegalStateException("RedisRateLimiter is not initialized");
        } else {
            UserLevelRedisRateLimiter.Config routeConfig = this.loadConfiguration(routeId);
            int replenishRate = routeConfig.getReplenishRate();
            int burstCapacity = routeConfig.getBurstCapacity();

            try {
                List<String> keys = getKeys(id);

                List<String> scriptArgs = Arrays.asList(replenishRate + "", burstCapacity + "", Instant.now().getEpochSecond() + "", "1");
                Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys, scriptArgs);
                return flux.onErrorResume((throwable) -> {
                    return Flux.just(Arrays.asList(1L, -1L));
                }).reduce(new ArrayList(), (longs, l) -> {
                    longs.addAll(l);
                    return longs;
                }).map((results) -> {
                    boolean allowed = (Long) results.get(0) == 1L;
                    Long tokensLeft = (Long) results.get(1);
                    Response response = new Response(allowed, this.getHeaders(routeConfig, tokensLeft));
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("response: " + response);
                    }

                    return response;
                });
            } catch (Exception var9) {
                this.log.error("Error determining if user allowed from redis", var9);
                return Mono.just(new Response(true, this.getHeaders(routeConfig, -1L)));
            }
        }
    }

    UserLevelRedisRateLimiter.Config loadConfiguration(String routeId) {
        UserLevelRedisRateLimiter.Config routeConfig = (UserLevelRedisRateLimiter.Config) this.getConfig().getOrDefault(routeId, this.defaultConfig);
        if (routeConfig == null) {
            routeConfig = (UserLevelRedisRateLimiter.Config) this.getConfig().get("defaultFilters");
        }

        if (routeConfig == null) {
            throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
        } else {
            return routeConfig;
        }
    }

    @NotNull
    public Map<String, String> getHeaders(UserLevelRedisRateLimiter.Config config, Long tokensLeft) {
        Map<String, String> headers = new HashMap();
        if (this.isIncludeHeaders()) {
            headers.put(this.remainingHeader, tokensLeft.toString());
            headers.put(this.replenishRateHeader, String.valueOf(config.getReplenishRate()));
            headers.put(this.burstCapacityHeader, String.valueOf(config.getBurstCapacity()));
        }

        return headers;
    }

    @Validated
    public static class Config {
        @Min(1L)
        private int replenishRate;
        @Min(1L)
        private int burstCapacity = 1;

        public List<String> getWhiteList() {
            return whiteList;
        }

        public void setWhiteList(List<String> whiteList) {
            this.whiteList = whiteList;
        }

        private List<String> whiteList = new ArrayList();

        public Config() {
        }

        public int getReplenishRate() {
            return this.replenishRate;
        }

        public UserLevelRedisRateLimiter.Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return this.burstCapacity;
        }

        public UserLevelRedisRateLimiter.Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public String toString() {
            return "Config{replenishRate=" + this.replenishRate + ", burstCapacity=" + this.burstCapacity + '}';
        }
    }


}
