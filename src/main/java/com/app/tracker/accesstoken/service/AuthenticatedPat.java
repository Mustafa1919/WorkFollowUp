package com.app.tracker.accesstoken.service;

import java.util.UUID;

/** PatAuthenticationFilter'in SecurityContext'i kurmak icin ihtiyac duydugu asgari bilgi. */
public record AuthenticatedPat(UUID tokenId, UUID userId) {}
