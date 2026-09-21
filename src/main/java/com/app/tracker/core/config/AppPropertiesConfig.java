package com.app.tracker.core.config;

import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.JwtProperties;
import com.app.tracker.core.security.SystemAdminProperties;
import com.app.tracker.integration.WebhookProperties;
import com.app.tracker.notification.SlackProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  JwtProperties.class,
  CorsProperties.class,
  SystemAdminProperties.class,
  WebhookProperties.class,
  SlackProperties.class
})
public class AppPropertiesConfig {}
