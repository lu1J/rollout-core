package io.github.lu1j.rolloutcore.server.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** A single Redis hash stores a version fence and JSON payload. Lua only arbitrates cache versions. */
public class RedisSnapshotStore implements L2SnapshotStore {
    // Compare non-negative decimal longs as strings: Lua numbers lose precision above 2^53.
    private static final String COMPARE = """
            local function older(a, b)
              return string.len(a) < string.len(b) or (string.len(a) == string.len(b) and a < b)
            end
            local old = redis.call('HGET', KEYS[1], 'version')
            """;
    static final DefaultRedisScript<Long> PUT = new DefaultRedisScript<>(COMPARE + """
        if old and older(ARGV[1], old) then return 0 end
        redis.call('HSET', KEYS[1], 'version', ARGV[1])
        redis.call('HSET', KEYS[1], 'payload', ARGV[2])
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        return 1
        """, Long.class);
    static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>(COMPARE + """
            if old and older(ARGV[1], old) then return 0 end
            redis.call('HSET', KEYS[1], 'version', ARGV[1])
            redis.call('HDEL', KEYS[1], 'payload')
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisSnapshotStore(StringRedisTemplate redis) { this.redis = redis; }
    @Override public Optional<String> get(CacheKey key) {
        return Optional.ofNullable((String) redis.opsForHash().get(key.redisKey(), "payload"));
    }
    @Override public void put(CacheKey key, long version, String payload, Duration ttl) {
        if (ttl.toMillis() > 0) redis.execute(PUT, List.of(key.redisKey()), Long.toString(version), payload, Long.toString(ttl.toMillis()));
    }
    @Override public void invalidate(CacheKey key, long latestVersion, Duration ttl) {
        redis.execute(INVALIDATE, List.of(key.redisKey()), Long.toString(latestVersion), Long.toString(ttl.toMillis()));
    }
}
