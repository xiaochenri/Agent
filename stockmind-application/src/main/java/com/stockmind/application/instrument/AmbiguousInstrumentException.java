package com.stockmind.application.instrument;

public final class AmbiguousInstrumentException extends IllegalArgumentException {
    public AmbiguousInstrumentException(String message) {
        super(message);
    }
}
