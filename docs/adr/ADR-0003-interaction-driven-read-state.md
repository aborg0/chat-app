# ADR-0003: Interaction-Driven Read State

## Status

Accepted

## Context

Marking messages as read immediately on viewport entry can produce false positives, especially during initial chapter load, scroll jumps, or layout churn. The UI also needs a controllable way to preserve unread state for later review.

## Decision

Read state in chapter timelines is interaction-driven.

- fully visible messages become eligible for auto-read
- eligible messages are marked read only after a subsequent user interaction
- the UI renders a divider at the first unread message
- the user may set an `Unread From Here` barrier that blocks auto-read for that message and newer messages until the chapter context is reset
- unread counts may update optimistically in the UI and then reconcile with backend state

## Consequences

- read semantics better match deliberate user attention than raw visibility
- unread state remains stable across transient viewport changes
- the frontend carries more local state to manage visibility, pending read transitions, and barriers
- changes to chapter loading, scrolling, or message rendering need regression coverage for unread behavior
