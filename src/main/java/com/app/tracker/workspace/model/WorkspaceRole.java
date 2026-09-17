package com.app.tracker.workspace.model;

/** SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.2 — workspace_users.role icin izinli degerler. */
public final class WorkspaceRole {

  private WorkspaceRole() {}

  public static final String ADMIN = "WORKSPACE_ADMIN";
  public static final String MANAGER = "MANAGER";
  public static final String DEVELOPER = "DEVELOPER";
  public static final String VIEWER = "VIEWER";
}
