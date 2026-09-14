package io.github.lu1j.rolloutcore.server.cache;

import io.github.lu1j.rolloutcore.server.service.InputRules;

public record CacheKey(String projectKey, String environmentKey, String flagKey) {
    public CacheKey {
        InputRules.key(projectKey);
        InputRules.key(environmentKey);
        InputRules.key(flagKey);
    }
    public String redisKey() {
        return "rolloutcore:eval:v1:" + projectKey + ":" + environmentKey + ":" + flagKey;
    }
}
