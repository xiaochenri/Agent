package com.agent.javascope.user.identity;

/** Provides the authenticated user without coupling the user module to a web security framework. */
public interface CurrentUserProvider {

    String currentUserId();
}
