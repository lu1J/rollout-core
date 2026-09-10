package io.github.lu1j.rolloutcore.server.service;

public class BusinessException extends RuntimeException {
    private final String code;
    private final int status;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public String getCode() { return code; }
    public int getStatus() { return status; }
    public static BusinessException validation(String message) {
        return new BusinessException(400, "validation_error", message);
    }
    public static BusinessException notFound(String resource) {
        return new BusinessException(404, "resource_not_found", resource + " not found");
    }
}
