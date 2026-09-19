package com.app.tracker.core.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.2 — {@code SYSTEM_ADMIN} global rolunun provizyon
 * mekanizmasi: doc bunun NASIL atanacagini tanimlamiyor (Faz1'de hic implemente edilmemisti), bu
 * yuzden basit ve denetlenebilir bir "sahip" deseni secildi — kendine-kayit YOKTUR. Operator, bu
 * env degiskeniyle "sistemin sahibi" olacak e-postalari onceden bildirir; {@code AuthService.login}
 * her girişte eslesen kullaniciyi (henuz degilse) {@code users.system_admin=true} olarak isaretler.
 * DB'deki bayrak kalici gercek kaynagi olur; bu liste sadece "ilk atama" tetikleyicisidir — buradan
 * çıkarılan bir e-posta, DB'de zaten true olan bayragi GERİ ALMAZ (bilerek: yanlislikla env
 * degiskenini silip admin'i kaza ile dusurme riski yerine, dusurme ELLE/DB'de yapilir).
 */
@ConfigurationProperties(prefix = "app.security")
public class SystemAdminProperties {

  private List<String> systemAdminEmails = new ArrayList<>();

  public List<String> getSystemAdminEmails() {
    return List.copyOf(systemAdminEmails);
  }

  public void setSystemAdminEmails(List<String> systemAdminEmails) {
    this.systemAdminEmails = new ArrayList<>(systemAdminEmails);
  }
}
