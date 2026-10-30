package com.app.tracker.core.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Istek govdesi ust siniri; bkz. {@link RequestBodyLimitFilter}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.http")
public class RequestBodyLimitProperties {

  private DataSize maxRequestBodySize = DataSize.ofMegabytes(2);
}
