package com.stockmind.bootstrap;

import com.agent.javascope.user.identity.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Development fallback. Replace this bean with a Spring Security principal adapter when login is enabled. */
@Component
public class DevelopmentCurrentUserProvider implements CurrentUserProvider {

    private final String userId;

    public DevelopmentCurrentUserProvider(
            @Value("${javascope.user.development-user-id:chat-user}") String userId) {
        this.userId = userId;
    }

    @Override
    public String currentUserId() {
        return userId;
    }
}
