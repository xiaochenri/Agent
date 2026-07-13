package com.agent.javascope.user.application;

import com.agent.javascope.user.port.BusinessAgentHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BusinessAgentRegistry {

    private final Map<String, BusinessAgentHandler> handlers;

    public BusinessAgentRegistry(List<BusinessAgentHandler> handlers) {
        LinkedHashMap<String, BusinessAgentHandler> registered = new LinkedHashMap<>();
        for (BusinessAgentHandler handler : handlers) {
            String code = normalize(handler.businessCode());
            BusinessAgentHandler previous = registered.putIfAbsent(code, handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate business agent handler: " + code);
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public BusinessAgentHandler require(String businessCode) {
        String code = normalize(businessCode);
        BusinessAgentHandler handler = handlers.get(code);
        if (handler == null) {
            throw new IllegalArgumentException("unsupported businessCode: " + code);
        }
        return handler;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("businessCode must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid businessCode: " + value);
        }
        return normalized;
    }
}
