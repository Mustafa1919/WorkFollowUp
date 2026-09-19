package com.app.tracker.user.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DATABASE_SCHEMA.md 2.2 — RLS'e tabi degildir, workspace-bagimsiz kimliktir. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

  @Id private UUID id;

  private String email;

  private String passwordHash;

  private String fullName;

  private boolean emailVerified;

  private boolean systemAdmin;

  public static User newUser(UUID id, String email, String passwordHash, String fullName) {
    User user = new User();
    user.id = id;
    user.email = email;
    user.passwordHash = passwordHash;
    user.fullName = fullName;
    user.emailVerified = false;
    user.systemAdmin = false;
    return user;
  }

  public void markEmailVerified() {
    this.emailVerified = true;
  }

  /**
   * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.2 — bkz. SystemAdminProperties javadoc'u. {@code
   * isSystemAdmin()} getter'i sinifin {@code @Getter}'inden (Lombok, boolean alan icin "is" onekini
   * otomatik uretir) gelir, burada tekrar tanimlanmaz.
   */
  public void grantSystemAdmin() {
    this.systemAdmin = true;
  }

  public void changePasswordHash(String newPasswordHash) {
    this.passwordHash = newPasswordHash;
  }
}
