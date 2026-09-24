package com.app.tracker.goal.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** Yalniz {@code CUSTOM} metrikli hedefler icin elle ilerleme girisi. */
public record GoalProgressRequest(@PositiveOrZero long value) {}
