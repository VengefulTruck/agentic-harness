package com.jay.agentic.state;

/**
 * The SDLC stages this harness coordinates.
 *
 * <p>An enum rather than free-form strings, so the dependency graph is
 * validated at compile time — a mistyped stage name cannot reach runtime,
 * and the set of stages stays a closed, reviewable list.
 */
public enum StageId {
    INTAKE,
    CLARIFY,
    EXPLORE,
    PLAN,
    APPROVAL_GATE,
    IMPLEMENT,
    TEST,
    DOCS,
    SECURITY_REVIEW,
    VALIDATE,
    RELEASE_GATE,
    SUMMARY
}