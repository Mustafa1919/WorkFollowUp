package com.app.tracker.goal.controller;

import com.app.tracker.goal.dto.GoalProgressRequest;
import com.app.tracker.goal.dto.GoalRequest;
import com.app.tracker.goal.dto.GoalResponse;
import com.app.tracker.goal.dto.GoalUpdateRequest;
import com.app.tracker.goal.service.GoalService;
import com.app.tracker.report.model.ReportPeriod;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donem hedefleri. Yazma ADMIN/MANAGER ({@code TagController} ile ayni gerekce: hedef tanimlari tum
 * workspace'i ilgilendirir ve raporda herkese gorunur); listeleme herhangi bir uye.
 *
 * <p>Ilerleme guncelleme ({@code PUT .../progress}) de yonetim yetkisi ister: elle girilen deger
 * raporun ileriye donuk tarafinin TEK dogruluk kaynagidir.
 */
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

  private static final String MANAGE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')";

  private final GoalService goalService;

  public GoalController(GoalService goalService) {
    this.goalService = goalService;
  }

  /**
   * @param quarter verilmezse YILLIK hedefler doner (ceyreklikler degil)
   */
  @GetMapping
  public List<GoalResponse> list(
      @RequestParam int year, @RequestParam(required = false) Integer quarter) {
    return goalService.listByPeriod(new ReportPeriod(year, quarter)).stream()
        .map(GoalResponse::from)
        .toList();
  }

  @PostMapping
  @PreAuthorize(MANAGE)
  public ResponseEntity<GoalResponse> create(@Valid @RequestBody GoalRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            GoalResponse.from(
                goalService.create(
                    new ReportPeriod(request.year(), request.quarter()),
                    request.title(),
                    request.metricType(),
                    request.targetValue(),
                    request.projectId())));
  }

  @PutMapping("/{goalId}")
  @PreAuthorize(MANAGE)
  public GoalResponse update(
      @PathVariable UUID goalId, @Valid @RequestBody GoalUpdateRequest request) {
    return GoalResponse.from(
        goalService.update(goalId, request.title(), request.targetValue(), request.projectId()));
  }

  @PutMapping("/{goalId}/progress")
  @PreAuthorize(MANAGE)
  public GoalResponse updateProgress(
      @PathVariable UUID goalId, @Valid @RequestBody GoalProgressRequest request) {
    return GoalResponse.from(goalService.updateProgress(goalId, request.value()));
  }

  @DeleteMapping("/{goalId}")
  @PreAuthorize(MANAGE)
  public ResponseEntity<Void> delete(@PathVariable UUID goalId) {
    goalService.delete(goalId);
    return ResponseEntity.noContent().build();
  }
}
