package com.app.tracker.notification.service;

import java.net.URI;

/** Slack'e tek mesaj gonderme sinirinin soyutlamasi (test sinirlari ve dis bagimlilik icin). */
public interface SlackSender {

  /**
   * @throws SlackTransientException yeniden denenebilir hata
   * @throws SlackPermanentException yeniden denemenin anlamsiz oldugu hata
   */
  void send(URI webhookUrl, String text);
}
