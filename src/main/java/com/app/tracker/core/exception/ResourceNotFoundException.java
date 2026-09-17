package com.app.tracker.core.exception;

/** Istenen kaynak yok VEYA (RLS nedeniyle) bu tenant'a gorunmuyor — ikisi de 404. */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
