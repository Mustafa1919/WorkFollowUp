package com.app.tracker.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Swagger UI/OpenAPI JSON yalniz "migrate" disindaki profillerde acilir (migrate profili
 * Security/web auto-config'lerini haric tutuyor, bkz. application-migrate.yml). JWT bearer semasi
 * burada tanimlanir; her controller'a ayri {@code @SecurityRequirement} eklemeye gerek yok, tumune
 * varsayilan olarak uygulanir.
 */
@Configuration
@Profile("!migrate")
public class OpenApiConfig {

  private static final String BEARER_SCHEME_NAME = "bearerAuth";
  private static final String WORKSPACE_HEADER_SCHEME_NAME = "workspaceHeader";

  @Bean
  public OpenAPI trackerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("WorkFollowUp API")
                .description(
                    "Cok kiracili is takip ve analiz SaaS - Core API. "
                        + "Kimlik dogrulama: /api/v1/auth/login ile alinan access token "
                        + "Authorization: Bearer header'inda gonderilir. Workspace'e bagli "
                        + "endpoint'ler ayrica X-Workspace-Id header'i gerektirir.")
                .version("v1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME_NAME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .addSecuritySchemes(
                    WORKSPACE_HEADER_SCHEME_NAME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Workspace-Id")))
        .addSecurityItem(
            new SecurityRequirement()
                .addList(BEARER_SCHEME_NAME)
                .addList(WORKSPACE_HEADER_SCHEME_NAME));
  }
}
