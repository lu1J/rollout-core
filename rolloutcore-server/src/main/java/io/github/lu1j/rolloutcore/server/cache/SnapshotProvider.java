package io.github.lu1j.rolloutcore.server.cache;

public interface SnapshotProvider {
    LoadResult get(CacheKey key);
    enum Source { L1, L2, DB, LKG, NEGATIVE }
    record LoadResult(EvaluationSnapshot snapshot, Source source) {}
}
