package io.github.lu1j.rolloutcore.server.cache;

public record ConfigChanged(CacheKey key, long latestVersion) {}
