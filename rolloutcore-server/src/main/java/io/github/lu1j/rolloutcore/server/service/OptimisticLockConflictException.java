package io.github.lu1j.rolloutcore.server.service;

public class OptimisticLockConflictException extends BusinessException {
    public OptimisticLockConflictException() {
        super(409, "optimistic_lock_conflict", "Config version changed; reload before retrying");
    }
}
