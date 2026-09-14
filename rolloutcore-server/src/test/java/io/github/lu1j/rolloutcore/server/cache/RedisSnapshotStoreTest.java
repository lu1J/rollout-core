package io.github.lu1j.rolloutcore.server.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSnapshotStoreTest extends CacheFixture {
    @Test void readsOnlyJsonPayloadUsingScopedRedisKey() {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashes);
        when(hashes.get(key.redisKey(), "payload")).thenReturn(codec.encode(snapshot(5, true)));
        var store = new RedisSnapshotStore(template);
        assertEquals(5, codec.decode(key, store.get(key).orElseThrow()).configVersion());
        verify(hashes).get("rolloutcore:eval:v1:shop:prod:pay", "payload");
    }
    @Test void versionedScriptsBindLongVersionAndTtlWithoutNumericRounding() {
        var template = mock(StringRedisTemplate.class); var store = new RedisSnapshotStore(template);
        store.put(key, Long.MAX_VALUE, "{}", Duration.ofSeconds(30));
        verify(template).execute(RedisSnapshotStore.PUT, List.of(key.redisKey()), "9223372036854775807", "{}", "30000");
        store.invalidate(key, 6, Duration.ofSeconds(60));
        verify(template).execute(RedisSnapshotStore.INVALIDATE, List.of(key.redisKey()), "6", "60000");
        for (var script : List.of(RedisSnapshotStore.PUT, RedisSnapshotStore.INVALIDATE)) {
            String lua = script.getScriptAsString();
            assertTrue(lua.contains("older(ARGV[1], old)")); assertTrue(lua.contains("string.len"));
            assertTrue(lua.contains("PEXPIRE")); assertFalse(lua.contains("tonumber"));
        }
    }
    @Test void expiredLoadIsNeverWrittenToRedis() {
        var template = mock(StringRedisTemplate.class);
        new RedisSnapshotStore(template).put(key, 5, "{}", Duration.ZERO); verifyNoInteractions(template);
    }
    @Test void putUsesOnlySingleFieldHsetCallsInsideOneVersionGuardedScript() {
        String lua = RedisSnapshotStore.PUT.getScriptAsString();
        var writes = java.util.regex.Pattern.compile("redis\\.call\\('HSET'[^\\n]*").matcher(lua)
                .results().map(java.util.regex.MatchResult::group).toList();
        assertEquals(List.of("redis.call('HSET', KEYS[1], 'version', ARGV[1])",
                "redis.call('HSET', KEYS[1], 'payload', ARGV[2])"), writes);
        assertTrue(lua.indexOf("if old and older(ARGV[1], old) then return 0 end") < lua.indexOf(writes.getFirst()));
        assertTrue(lua.indexOf(writes.getLast()) < lua.indexOf("redis.call('PEXPIRE', KEYS[1], ARGV[3])"));
        var template = mock(StringRedisTemplate.class);
        new RedisSnapshotStore(template).put(key, 6, "{}", Duration.ofSeconds(30));
        verify(template).execute(RedisSnapshotStore.PUT, List.of(key.redisKey()), "6", "{}", "30000");
        verifyNoMoreInteractions(template);
    }
    @Test void storeContractAllowsEqualAndNewerVersionsToPublishPayload() {
        redis.invalidate(key, 6, Duration.ofSeconds(60));
        String equal = codec.encode(snapshot(6, false));
        redis.put(key, 6, equal, Duration.ofSeconds(30));
        assertEquals(equal, redis.get(key).orElseThrow()); assertEquals(6, redis.version);
        String newer = codec.encode(snapshot(7, true));
        redis.put(key, 7, newer, Duration.ofSeconds(40));
        assertEquals(newer, redis.get(key).orElseThrow()); assertEquals(7, redis.version);
    }
    @Test void invalidateKeepsVersionAndRemovesPayloadWithTtl() {
        redis.put(key, 6, codec.encode(snapshot(6, true)), Duration.ofSeconds(30));
        redis.invalidate(key, 6, Duration.ofSeconds(60));
        assertEquals(6, redis.version); assertTrue(redis.get(key).isEmpty());
        assertEquals(Duration.ofSeconds(60).toNanos(), redis.expires);
        String lua = RedisSnapshotStore.INVALIDATE.getScriptAsString();
        assertTrue(lua.contains("redis.call('HSET', KEYS[1], 'version', ARGV[1])"));
        assertTrue(lua.contains("redis.call('HDEL', KEYS[1], 'payload')"));
        assertTrue(lua.contains("redis.call('PEXPIRE', KEYS[1], ARGV[2])"));
    }
    @Test void storeContractPreventsOlderFillAfterInvalidationOrNewerFill() {
        redis.put(key, 5, codec.encode(snapshot(5, true)), Duration.ofSeconds(60));
        redis.invalidate(key, 6, Duration.ofSeconds(60));
        redis.put(key, 5, codec.encode(snapshot(5, true)), Duration.ofSeconds(50));
        assertTrue(redis.get(key).isEmpty());
        redis.put(key, 6, codec.encode(snapshot(6, false)), Duration.ofSeconds(50));
        redis.put(key, 5, codec.encode(snapshot(5, true)), Duration.ofSeconds(50));
        redis.invalidate(key, 5, Duration.ofSeconds(60));
        assertEquals(6, codec.decode(key, redis.get(key).orElseThrow()).configVersion());
    }
}
