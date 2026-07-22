package com.stockmind.infrastructure.eastmoney;

public final class EastmoneyAccessException extends IllegalStateException {
    private final String errorCode;

    public EastmoneyAccessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
