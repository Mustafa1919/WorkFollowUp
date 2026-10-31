package com.app.tracker.security.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * D4: kosan uygulamanin {@code GET /v3/api-docs} yanitindan uc envanteri. Harness uclari ELLE
 * YAZMAZ, buradan turetir: yeni bir uc eklendiginde otomatik olarak teste girer.
 */
public final class OpenApiCatalog {

  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
  private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");

  /** Bir sorgu parametresi (OpenAPI {@code in: query}). */
  public record QueryParam(String name, boolean required, String type, String format) {
    public boolean isUuid() {
      return "uuid".equals(format);
    }
  }

  /** Tek bir islem (metod + path sablonu). */
  public record Operation(
      String method,
      String path,
      List<String> pathParams,
      List<QueryParam> queryParams,
      String bodySchema,
      boolean bodyRequired) {

    public boolean hasPathParams() {
      return !pathParams.isEmpty();
    }

    public boolean isRead() {
      return "GET".equals(method);
    }

    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /** Bir istek semasi: zorunlu ve bilinen alanlar. */
  public record Schema(String name, Set<String> properties, Set<String> required) {}

  private final List<Operation> operations;
  private final JsonNode schemas;

  private OpenApiCatalog(List<Operation> operations, JsonNode schemas) {
    this.operations = List.copyOf(operations);
    this.schemas = schemas;
  }

  public static OpenApiCatalog parse(JsonNode doc) {
    List<Operation> ops = new ArrayList<>();
    for (Map.Entry<String, JsonNode> pathEntry : doc.path("paths").properties()) {
      String path = pathEntry.getKey();
      for (Map.Entry<String, JsonNode> opEntry : pathEntry.getValue().properties()) {
        if (!METHODS.contains(opEntry.getKey())) {
          continue;
        }
        JsonNode op = opEntry.getValue();
        List<String> pathParams = new ArrayList<>();
        Matcher m = PATH_PARAM.matcher(path);
        while (m.find()) {
          pathParams.add(m.group(1));
        }
        List<QueryParam> query = new ArrayList<>();
        for (JsonNode p : op.path("parameters")) {
          if ("query".equals(p.path("in").asString(""))) {
            query.add(
                new QueryParam(
                    p.path("name").asString(""),
                    p.path("required").asBoolean(false),
                    p.path("schema").path("type").asString(""),
                    p.path("schema").path("format").asString("")));
          }
        }
        String bodySchema = null;
        JsonNode content = op.path("requestBody").path("content");
        for (Map.Entry<String, JsonNode> c : content.properties()) {
          String ref = c.getValue().path("schema").path("$ref").asString("");
          bodySchema = ref.isEmpty() ? "<inline>" : ref.substring(ref.lastIndexOf('/') + 1);
        }
        ops.add(
            new Operation(
                opEntry.getKey().toUpperCase(),
                path,
                List.copyOf(pathParams),
                List.copyOf(query),
                bodySchema,
                op.path("requestBody").path("required").asBoolean(bodySchema != null)));
      }
    }
    ops.sort(
        (a, b) -> {
          int c = a.path().compareTo(b.path());
          return c != 0 ? c : a.method().compareTo(b.method());
        });
    return new OpenApiCatalog(ops, doc.path("components").path("schemas"));
  }

  public List<Operation> operations() {
    return operations;
  }

  /** Sema yoksa {@code null}. */
  public Schema schema(String name) {
    JsonNode s = schemas.path(name);
    if (s.isMissingNode() || s.isNull()) {
      return null;
    }
    Set<String> props = new LinkedHashSet<>(s.path("properties").propertyNames());
    Set<String> required = new LinkedHashSet<>();
    for (JsonNode r : s.path("required")) {
      required.add(r.asString(""));
    }
    return new Schema(name, props, required);
  }
}
