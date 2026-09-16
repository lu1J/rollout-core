package io.github.lu1j.rolloutcore.sdk;

import tools.jackson.databind.JsonNode;

/** source describes the returned value; error retains the original failure, even during LKG. */
public record EvaluationResult<T>(T value, Source source, Error error, String errorMessage,
        String variant, String reason, Long configVersion, int attempts) {
    public enum Source { REMOTE, LKG, DEFAULT }
    public enum Error { NONE, FLAG_NOT_FOUND, TYPE_MISMATCH, INVALID_CONTEXT, BUSINESS_ERROR,
        TIMEOUT, CONNECTION, SERVER_UNAVAILABLE, PROTOCOL_ERROR, INTERRUPTED }
    public EvaluationResult { value = copy(value); }
    @Override public T value() { return copy(value); }
    @SuppressWarnings("unchecked") private static <T> T copy(T value) {
        return value instanceof JsonNode node ? (T) node.deepCopy() : value;
    }
}
