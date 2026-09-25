package com.app.tracker.notification.email;

/**
 * @throws EmailTransientException gecici gonderim hatasi (Kafka error handler yeniden dener)
 * @throws EmailPermanentException kalici red (adres gecersiz vb.) — cagiran yutar
 */
public interface EmailSender {

  void send(String to, EmailContent content);
}
