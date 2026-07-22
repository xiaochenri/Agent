package com.agent.javascope.user.identity;

/** Raised when a web request has no valid signed-in user session. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("authentication required");
    }
}
