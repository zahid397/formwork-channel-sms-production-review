# ADR 0001 — Tenant-aware provider selection, retry/failover, and cost recording

**Status:** Accepted
**Date:** 2026-07-11

## Context

Three of the review's findings share one root cause: the send path
(`SmsChannelService.sendSms`) did the absolute minimum — validate, pick one
globally-configured gateway, send, return — and none of the surrounding
infrastructure the codebase already contains (a fully-built cost pipeline,
a `tenantId` on every message, a `RetryProperties` config block) was wired
to it.

- **Tenant isolation** (REVIEW.md Finding 2): `SmsMessage.tenantId()` was
  read by nothing. Worse, `SmsChannelAutoConfiguration` gated all five
  `SmsGateway` beans on the *same* single property, so at most one gateway
  could exist in the Spring context at all — tenant-specific routing was
  not just unimplemented, it was architecturally impossible.
- **Cost recording** (Finding 1, with Finding 4): `SmsCostService.recordCost`
  was fully implemented and fully untested-in-production because nothing
  called it.
- **Retry/failover** (Findings 6, 7): `RetryProperties` existed, was bound
  from config, had passing unit tests for its getters/setters, and drove
  zero behavior.

These three had to be designed together because the fix for one changes the
constraints on the others: making multiple gateways coexist (needed for
tenant selection) is also the prerequisite for failover; recording cost
exactly once has to be defined in terms of "the one attempt that succeeded"
once retry exists.

## Decision

**Provider activation.** Each of the five `SmsGateway` beans is now gated on
its own provider's credentials being present (`twilio.account-sid`,
`vonage.api-key`, `messagebird.access-key`, `budget-sms.username`; AWS SNS
gets an explicit `aws-sns.enabled` flag since its credentials come from the
environment, not Spring config) instead of all five sharing one
`formwork.sms-channel.provider` switch. Multiple gateways can now exist
simultaneously.

**Tenant selection.** A new `formwork.sms-channel.tenant-providers` map
(tenantId string → provider code) is checked first; a tenant with no entry
falls through to the existing global `provider` default. A tenant *with* an
entry that names a provider with no registered gateway fails loudly
(`IllegalStateException`) — it never silently falls back to the global
default or to another tenant's provider.

**Retry/failover.** `sendSms` builds a deterministic candidate chain (the
resolved primary provider, then the rest of
`formwork.sms-channel.failover-order`, skipping the primary and any
unregistered provider) and attempts up to `retry.max-attempts` times total,
cycling through the chain, sleeping `retry.backoff` between attempts.
Failures are classified:

- **Retryable** — 5xx and the generic `SEND_ERROR` (connection
  refused/DNS failure: the request never reached the provider). Advances to
  the next attempt/candidate.
- **Permanent** — 4xx, `CONFIG_ERROR`, `TIMEOUT`, and any unrecognized
  provider-specific code. Stops immediately: no further retry, no failover.

**Cost recording.** `sendSms` calls `SmsCostService.recordCost` exactly
once, only for the attempt that returns success — never for a failed
attempt, regardless of how many attempts preceded it. Idempotency is
enforced two ways: an `existsByProviderAndMessageId` check before insert
(fast path), and a unique index on `(provider, message_id)` added in
`V2__add_cost_record_idempotency_constraint.sql` (the actual guarantee
under concurrent/racing calls — the check alone has a race window;
`recordCost` catches the resulting `DataIntegrityViolationException` and
treats it as "already recorded").

## Alternatives considered

**DB-backed per-tenant provider configuration**, instead of a config map.
Rejected for this time-box: there is no existing tenant-configuration
table or repository in this module to extend, and building one (entity,
migration, repository, cache invalidation) is a materially larger change
than the config-map approach, which reuses the exact mechanism
(`@ConfigurationProperties`) the rest of the module already uses for every
other provider setting. A DB-backed table is the right answer for a real
multi-tenant SaaS at scale (self-service tenant onboarding, no redeploy to
change a tenant's provider) — noted under Future Improvements.

**Event-driven cost recording** (publish an `SmsSentEvent`, record cost in
an `@EventListener`), instead of a direct synchronous call. Rejected:
Spring's default `ApplicationEventPublisher` is synchronous-in-process by
default anyway, so this would add indirection without changing failure
semantics, and an async listener (via `@Async` or an external queue) turns
"cost recording failed" into a *silently* dropped event unless a
dead-letter/retry mechanism is also built — which is exactly the kind of
"listener that's never wired up" failure mode this whole review exists to
catch. A direct call with an explicit try/catch and a loud log line is more
honest about what actually happens on failure.

**Transactional outbox for cost recording** (write the cost record and the
"send occurred" fact in one local transaction, dispatch separately).
Rejected as over-engineering for this time-box: it solves a problem this
module doesn't fully have, since the SMS send is a synchronous HTTP call,
not a message this service publishes — there's nothing to make atomic with
the DB write except the DB write itself. Worth revisiting if sends become
asynchronous.

**Idempotency key for the send request itself** (client-supplied key,
checked before dispatch, so a timeout-then-retry can detect and skip a
duplicate send instead of just refusing to retry). This is the correct
production fix for the `TIMEOUT`-is-permanent limitation below, and
`SmsMessage.referenceId()` already exists as a natural place to carry it —
but implementing it means every one of the five providers needs a
consistent "check if already sent" story, which several of their APIs
don't support natively. Deferred; see Consequences.

## Consequences

- **Breaking config change.** A deployment that previously worked with only
  `formwork.sms-channel.provider=TWILIO` and Twilio credentials continues to
  work unchanged (credential presence already implied "configured"). A
  deployment that relied on *only* `provider` being set with no
  provider-specific credentials configured (unusual, since the gateway
  constructors would have failed regardless) would need to also set the new
  `aws-sns.enabled` flag if using AWS SNS specifically.
- **Consistency trade-off, SMS success vs. cost persistence failure.** The
  SMS send is the source of truth: if it succeeds, that result is returned
  to the caller regardless of what happens in `recordCost` next. A
  `DataAccessException` from cost recording is caught and logged at ERROR
  with tenant/provider/messageId (never phone number or message body) for
  manual reconciliation, not propagated — propagating it would make the
  caller retry a message that was already, irreversibly, sent, risking a
  duplicate real-world SMS and a duplicate charge for a problem that was
  purely a bookkeeping failure. The gap this leaves: a cost-recording
  failure produces a real send with no cost record and only a log line as
  evidence, until a human or an automated reconciliation job acts on it.
  No such job exists yet — see Future Improvements.
- **`TIMEOUT` is treated as permanent, not retryable.** A timeout means we
  stopped waiting, not that the provider didn't process the request — we
  cannot tell "definitely not sent" from "sent, response lost" with the
  information available today. Retrying blind risks a duplicate send (and
  duplicate charge) for the failure mode where retrying would help. Treating
  it as permanent trades resilience (a genuine transient timeout is not
  retried) for safety (a slow-but-successful send is never resent). The
  real fix is the deferred idempotency-key alternative above.
- **KNOWN GAP, found in this review's own final hostile self-check, not yet
  fixed: `SEND_ERROR` is retried on the same unstated assumption that
  "the request never reached the provider," but that assumption is not
  actually enforced anywhere.** Every gateway's `catch (Exception e)` block
  is a single blanket catch that maps *any* exception - a connection
  refused before a byte was sent, or a connection reset while reading the
  provider's response after it may have already processed the request - to
  the same `SEND_ERROR` code. Only the first case is safe to retry; the
  second is exactly as ambiguous as `TIMEOUT` and is currently
  misclassified as safe. This means the unsafe-duplicate-send risk this
  ADR describes as solved for timeouts is **not actually closed** for this
  closely related failure mode. Fixing it needs each gateway to distinguish
  connection-phase exceptions (`java.net.ConnectException`,
  `java.net.UnknownHostException` - safe to retry) from response-phase
  ones (unsafe, should be classified alongside `TIMEOUT`), which no gateway
  currently does. Tracked as a correction to make before this design is
  considered complete, not before this document is honest about it.

## Transaction/idempotency trade-offs

`recordCost` runs inside its own `@Transactional` boundary, separate from
the (non-transactional, in-process) HTTP call to the provider — they cannot
be one transaction, since the provider call is an irreversible external
side effect a database transaction can't roll back. The idempotency
guarantee is therefore enforced at the storage layer (unique constraint on
`(provider, message_id)`), not by wrapping both operations atomically.

**What this guarantees, precisely, and what it does not.** The constraint
guarantees at most one cost row per `(provider, messageId)` pair - not "at
most one cost row per real send," which is a stronger claim this design
does not make. Those differ exactly when an upstream caller retries
`sendSms()` for what it considers one logical message and the provider
issues a *new* `messageId` for that retry (a real possibility whenever the
first attempt's outcome was ambiguous - see the `TIMEOUT`/`SEND_ERROR`
discussion above): two rows, each individually satisfying the unique
constraint, get written for one real-world send. Closing that gap needs a
caller-supplied idempotency key checked before dispatch (the deferred
alternative above), not a stronger database constraint - the database has
no way to know two different provider message IDs represent the same
logical request.

## Why this scope under the time-box

Tenant selection and cost recording were built to production quality
because they were both the two highest-priority findings *and* two of the
four required functional gaps — fixing them once satisfied both. Retry/
failover follows the same reasoning for Findings 6/7. The one deliberately
incomplete piece is unknown-outcome (timeout) handling: solving it properly
needs a per-provider "check delivery status" capability this module's five
gateways don't uniformly have, which is a larger investigation than this
review's time-box allows — documented as a known limitation rather than
half-built.
