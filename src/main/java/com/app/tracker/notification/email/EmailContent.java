package com.app.tracker.notification.email;

/**
 * Sablonlarin uretimi: konu + duz metin + basit HTML (istemcinin metnini tercih edebilmesi icin).
 */
public record EmailContent(String subject, String text, String html) {}
