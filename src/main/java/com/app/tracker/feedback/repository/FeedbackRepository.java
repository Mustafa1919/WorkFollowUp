package com.app.tracker.feedback.repository;

import com.app.tracker.feedback.model.Feedback;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS zaten workspace'e gore filtreler (TagRepository ile AYNI desen). */
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

  List<Feedback> findAllByOrderByCreatedAtDesc();
}
