# REVIEW.md — `formwork-channel-sms`

Reviewed against the checked-out state of the module (commit `c0b2efe`, baseline
build: `mvn test` → **150 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**;
see [README.md](README.md) for exact commands and the local build-scaffold
disclosure — this repository as handed off does not include the real
`formwork-parent`/`formwork-base-tenant` platform modules, so a minimal local
stand-in was reconstructed to actually compile and run the suite instead of
reviewing statically).

Findings are ranked by production risk, Critical first. **Findings 1–3 are
selected for the Part 2 fix** (see commit history and the "Status" line on
each). Findings 1 and 4 share one root cause and are fixed together.

---

## Finding 1 — Cost is never recorded for a single SMS sent in production

**Severity:** Critical
**Category:** Financial correctness
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/api/SmsChannelService.java:10-24`

**Observed behavior**
`SmsChannelService` — the only entry point that actually sends an SMS — has a
two-argument constructor (`List<SmsGateway> gateways, SmsChannelProperties
properties`) and `sendSms()` does exactly three things: validate the phone
number, resolve a gateway, call `gateway.send(message)`, return the result.
It never references `SmsCostService`, `SmsCostRepository`, or
`ProviderRateRegistry`.

**Failure mechanism**
`SmsCostService.recordCost(UUID, String, SmsResult)` at
`cost/SmsCostService.java:38-64` is fully implemented, correctly checks
`result.isSuccess()` before persisting, computes segment-aware cost, and
saves via the repository. It is simply never invoked. A `grep` for
`recordCost(` across `src/main` finds zero call sites outside
`SmsCostService` itself; the only callers anywhere in the module are its own
unit tests.

**Production impact**
Every SMS the platform sends through this module — regardless of provider —
generates zero cost records. Per-tenant billing (`getMonthlyCost`), usage
dashboards (`getCostBreakdown`), and the `sms_cost_record` table itself stay
permanently empty while the company keeps paying Twilio/Vonage/AWS/etc. for
real messages. This is not a rounding error or an edge case — it is 100% of
revenue-attribution data silently missing from day one, and nothing in the
test suite would tell you, because no test ever asserts that `sendSms()`
causes a cost record to appear.

**Reproduction or evidence**
`SmsChannelServiceTest` (existing) mocks `SmsGateway` and `SmsChannelProperties`
only — it has no `SmsCostService`/`SmsCostRepository` collaborator to verify
against, which is itself consistent with the production code never touching
that dependency. See the added regression test
`SmsChannelServiceCostRecordingTest.sendSms_SuccessfulSend_RecordsCostExactlyOnce`
(fails on original code — compile-fails, in fact, since the constructor
doesn't accept a cost recorder at all — and passes after the fix).

**Recommended fix**
Give `SmsChannelService` an `SmsCostService` dependency and call
`recordCost(message.tenantId(), message.to(), result)` after a successful
send, before returning. Smallest safe change: one new constructor parameter,
one new call. See Finding 4 for why this alone is not sufficient without an
idempotency guard.

**Status:** Fixed (top 3, combined with Finding 4).

---

## Finding 2 — Tenant-aware provider selection does not exist; `tenantId` changes nothing

**Severity:** Critical
**Category:** Tenant isolation
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/api/SmsChannelService.java:34-40`, `formwork-channel-sms/src/main/java/one/formwork/channel/sms/config/SmsChannelAutoConfiguration.java:12-48`

**Observed behavior**
`resolveGateway()` picks a gateway using only `properties.getProvider()` — a
single global `formwork.sms-channel.provider` value — and never reads
`message.tenantId()` anywhere. Structurally, it cannot: `SmsChannelAutoConfiguration`
registers each of the five `SmsGateway` beans behind
`@ConditionalOnProperty(prefix = "formwork.sms-channel", name = "provider", havingValue = "TWILIO")`
(and so on for the other four, lines 18-46) — all five conditions key off the
*same* single property, so **at most one `SmsGateway` bean can ever exist in
the Spring context at a time**. There is no tenant-scoped configuration
source anywhere in the module.

**Failure mechanism**
`SmsMessage` carries a `tenantId` (`api/SmsMessage.java:9`) that is accepted,
stored, and then read by nothing. Two tenants configured to want different
providers is not a bug that manifests occasionally — it is impossible to
express today, because the wiring only supports one provider for the entire
deployment.

**Production impact**
This is exactly the scenario the take-home names explicitly: "A tenant
should be able to be on a different provider than the global default, and
one tenant's configuration must never affect another's." Today, a tenant
that has (say) negotiated AWS SNS pricing, or whose traffic requires a
specific provider's country coverage, is silently routed through whatever
the global default happens to be — with no error, no warning, and no way to
override it per tenant. If it were ever *partially* wired (e.g. a future
caller passing per-request provider hints), a bug in that wiring is one
`resolveGateway` call away from sending tenant A's traffic — and paying
tenant A's SMS cost — through tenant B's provider account.

**Reproduction or evidence**
`SmsChannelServiceTest.sendSms_ValidMessage_DelegatesToResolvedGateway` (existing)
stubs `properties.getProvider()` directly and never varies `tenantId` across
gateways — there is no test anywhere in the suite that sends messages for two
different tenants and asserts they land on different gateways, because no
code path could make that assertion pass. See the added regression test
`TenantAwareProviderSelectionTest` (two tenants, two configured providers;
fails on original code because `resolveGateway` has no tenant parameter to
even accept a per-tenant override).

**Recommended fix**
See Required Gap A in README.md for the implemented fix: a per-tenant
provider-override map on `SmsChannelProperties`, gateway beans registered
per-provider-when-configured (not gated on the single global selector so more
than one can coexist), and `resolveGateway(UUID tenantId)` that checks the
tenant override first, falls back to the global default only when the tenant
has no override, and fails loudly (no cross-tenant fallback) if a tenant's
configured provider isn't actually registered.

**Status:** Fixed (top 3).

---

## Finding 3 — AWS SNS request signing uses form-urlencoding instead of RFC 3986 encoding, breaking every message with a space

**Severity:** Critical
**Category:** Reliability / AWS SNS production correctness
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/provider/AwsSnsSmsGateway.java:57-60,77-78,90,120-122`

**Observed behavior**
`AwsSnsSmsGateway` builds an AWS Signature Version 4 request by hand (no AWS
SDK). The query string used for both the signed `canonicalRequest` (lines
57-60, 77-78) and the actual outgoing request URI (line 90) is built by the
private `encode()` helper at lines 120-122:
```java
private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
}
```

**Failure mechanism**
`URLEncoder.encode` implements HTML form encoding
(`application/x-www-form-urlencoded`), not RFC 3986 URI encoding. The two
disagree on exactly the characters AWS SigV4 requires to be RFC-3986-correct:
a space becomes `+` under `URLEncoder` but must be `%20` under SigV4's
canonicalization rules, and `*`/`~` are handled differently too. AWS's own
signature-verification algorithm re-derives the canonical request from the
request it actually receives using RFC 3986 rules; a query string containing
`+` for spaces does not round-trip through that process the same way it was
signed. Since the SMS `Message` body is put directly into this query string
(line 48: `params.put("Message", message.body())`), **any message containing
a space — i.e., virtually every real SMS — produces a signature AWS rejects**
with `SignatureDoesNotMatch` (HTTP 403). This is a well-documented AWS SigV4
pitfall, not a hypothetical.

**Production impact**
The AWS SNS provider is effectively non-functional for real traffic. A
tenant configured on AWS SNS gets a clean `SmsResult.failure("AWS_SNS", "403", ...)`
(caught at line 101-103, so at least it isn't misreported as success — see
Finding 6 for the general "no timeout" issue on the same gateway), but every
single-word-free message fails, silently, in production, forever — and
nothing in CI would catch it.

**Reproduction or evidence**
Neither `AwsSnsSmsGatewayTest` nor `AwsSnsSmsGatewayExtraTest` (existing)
ever calls `send()` with network I/O mocked or stubbed — they test only
`supports()`, `getProviderName()`, and the "no AWS credentials" early-return
path. No existing test constructs a query string or inspects what would be
sent over the wire, so this has zero test coverage today. See the added
regression test `AwsSnsSmsGatewayEncodingTest`, which injects a mock
`WebClient` (same reflection technique the existing `*WireMockTest` files
use), sends a message body containing a space, and asserts the captured URI
contains `%20` rather than `+` — it fails on the original code and passes
after switching to a proper RFC 3986 percent-encoder.

**Recommended fix**
Replace `URLEncoder.encode` with a small RFC-3986-compliant percent-encoder
(encode everything outside `A-Za-z0-9-._~`, uppercase hex, space → `%20`) used
consistently for both the canonical request and the actual request URI.

**Status:** Fixed (top 3).

---

## Finding 4 — No idempotency protection on cost recording: any retry or duplicate call double-bills a tenant

**Severity:** Critical
**Category:** Financial correctness
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/cost/SmsCostService.java:38-64`, `formwork-channel-sms/src/main/resources/db/migration/cs/V1__create_sms_cost_table.sql:2-19`

**Observed behavior**
`recordCost` always performs an unconditional `repository.save(entity)` — new
row, new random `id` (from `TenantScopedEntity.onCreate()`), no lookup by
`messageId`/`provider` first. The Flyway migration for `sms_cost_record`
declares `message_id VARCHAR(200)` (line 10) with no `UNIQUE` constraint and
no index on it — nothing at the schema level prevents two rows for the same
provider message.

**Failure mechanism**
`recordCost` is not currently called at all (Finding 1), so today this is a
latent defect rather than an active one. But it becomes an active one the
moment cost recording is wired up (as this review does) unless something is
added: any retry-after-timeout, any duplicate webhook delivery, or any
caller-level retry that re-invokes `sendSms()` for a message whose provider
call actually succeeded the first time, will happily insert a second
`sms_cost_record` row for the same underlying SMS, silently double-billing
the tenant. There is no `findByProviderAndMessageId` guard, no unique
constraint, and no `referenceId`-based dedup — despite `SmsMessage` already
carrying a `referenceId` field (`api/SmsMessage.java:10`) that goes entirely
unused end-to-end.

**Production impact**
Combined with Required Gap C (retry/failover, which this review also
implements), retrying a slow-but-successful provider call would otherwise
record cost twice for one SMS — a direct, silent overcharge that would only
surface when a tenant disputes their invoice.

**Reproduction or evidence**
No existing test calls `recordCost` twice for the same result and asserts a
single row — `SmsCostServiceTest` (existing) only ever calls it once per
test. See the added regression test
`SmsCostServiceIdempotencyTest.recordCost_CalledTwiceForSameProviderMessageId_PersistsOnlyOnce`,
which fails against the original `recordCost` (inserts two rows) and passes
once the unique constraint + check-before-insert guard are added.

**Recommended fix**
Add a Flyway `V2` migration with a unique constraint on `(provider,
message_id)`, and make `recordCost` tolerate the resulting constraint
violation by treating it as "already recorded" rather than propagating a raw
SQL exception. See README.md "Cost recording consistency trade-off" for the
exact behavior chosen when the unique check races.

**Status:** Fixed (top 3, combined with Finding 1 — same commit).

---

## Finding 5 — `*WireMockTest` files don't use WireMock and never exercise the real HTTP call shape

**Severity:** High
**Category:** Testing
**Location:** `formwork-channel-sms/src/test/java/one/formwork/channel/sms/provider/TwilioSmsGatewayWireMockTest.java:25-41` (same pattern in `VonageSmsGatewayWireMockTest.java:26-41`, `MessageBirdSmsGatewayWireMockTest.java:24-38`, `BudgetSmsGatewayWireMockTest.java:22-37`)

**Observed behavior**
Despite the name, none of these four test classes start a WireMock server or
make a real HTTP call. Each one uses Mockito to mock `WebClient` itself,
reflectively overwrites the gateway's private `webClient` field with the
mock (e.g. `TwilioSmsGatewayWireMockTest.java:37-40`), then stubs the entire
fluent call chain (`webClient.post() → uri(anyString(), any(Object[].class)) →
contentType(any()) → bodyValue(any()) → retrieve() → bodyToMono(Map.class)`)
to return a canned response. `formwork-channel-sms/pom.xml` (lines 18-49)
has no WireMock dependency at all — there is no library in this module
capable of standing up a stub HTTP server.

**Failure mechanism**
`uri(anyString(), any(Object[].class))` matches *any* string. If the Twilio
URL path is misspelled, if `To=`/`From=`/`Body=` are renamed or dropped from
the form body, if the `Authorization` header is removed, or if the request
content-type is wrong, this test's stub still returns the same canned
`Map.of("sid", "SM123", ...)` response and the test still passes — because
the mock never looks at what was actually sent, only that *some* call
happened on *some* mocked chain. Ask the question the take-home poses: "if I
broke the code this test covers, would this test fail?" For the actual bytes
on the wire — the one thing a "WireMockTest" should be proving — the answer
is no.

**Production impact**
This is the test-suite-quality issue the take-home explicitly asks reviewers
to look for. It creates false confidence: 150/150 tests green, but zero of
them would catch a broken request URL, a dropped auth header, or (as in
Finding 3) a broken encoding scheme, for any of the four providers these
files cover.

**Reproduction or evidence**
Read the four files directly — no `wiremock` import exists anywhere in
`src/test`, and `pom.xml` confirms no such dependency is declared. Compare
against the added `AwsSnsWireMockRealHttpTest`
(`src/test/java/.../provider/AwsSnsWireMockRealHttpTest.java`), which starts
an actual WireMock server and asserts on the real request path, headers, and
query string — the kind of test this take-home's Part 3.4 asks for.

**Recommended fix**
Either rename these four files to stop implying they do something they
don't (`*MockedWebClientTest`), or replace them with real WireMock-backed
tests asserting on the request that was actually sent. Given the time-box,
this review adds one honest example (AWS SNS, since that's also where the
real bug was) rather than rewriting all four — see README.md "Scope cut" for
why the other three were left as-is.

**Status:** Documented only (the new AWS SNS WireMock test added under
Required Gap D demonstrates the fix pattern; retrofitting the other three
providers is scoped out — see README.md).

---

## Finding 6 — No timeout anywhere in the HTTP layer; a hung provider blocks the sending thread forever

**Severity:** High
**Category:** Reliability
**Location:** All five gateways — `AwsSnsSmsGateway.java:37,95`, `TwilioSmsGateway.java:27-30,46`, `VonageSmsGateway.java:23-25,46`, `MessageBirdSmsGateway.java:23-26,44`, `BudgetSmsGateway.java:19,35`

**Observed behavior**
Every gateway builds its `WebClient` with `WebClient.builder()...build()` and
no `ClientHttpConnector`/timeout configuration, then calls `.block()` with no
`Duration` argument (e.g. `TwilioSmsGateway.java:46`:
`.bodyToMono(Map.class).block();`).

**Failure mechanism**
`Mono.block()` without a timeout waits indefinitely for a terminal signal.
If a provider's endpoint accepts the TCP connection but never responds (a
real, common failure mode during provider outages — far more common than a
clean connection refusal), the calling thread parks forever. `sendSms()` is
synchronous, so this is a real thread from the calling application's request
pool, not a background worker.

**Production impact**
One provider outage can exhaust the entire web server's thread pool one
request at a time, taking down unrelated, healthy request paths in the same
application — a classic cascading-failure trigger. It also makes any retry
logic (Finding 7 / Required Gap C) meaningless if built naively on top,
since a single hung attempt never returns control to a retry loop.

**Reproduction or evidence**
No existing test exercises a slow/non-responding server (they all use
synchronous, immediately-resolved Mono stubs), so none would catch a
regression here. This review's retry/failover implementation (Required Gap
C) adds explicit per-call timeouts as a prerequisite, verified in
`RetryAndFailoverTest`.

**Recommended fix**
Add an explicit `Duration` timeout to every `.block()` call (or configure
connect/response timeouts on the shared `ClientHttpConnector`). Implemented
as part of Required Gap C.

**Status:** Fixed (as part of Required Gap C, not one of the top-3 review
picks — see README.md for why AWS SNS encoding was prioritized over this for
the top-3 slot).

---

## Finding 7 — No retry or failover logic exists; `RetryProperties` is completely unused

**Severity:** High
**Category:** Reliability
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/api/SmsChannelProperties.java:58-62`, `formwork-channel-sms/src/main/java/one/formwork/channel/sms/api/SmsChannelService.java:20-24`

**Observed behavior**
`SmsChannelProperties.RetryProperties` declares `maxAttempts` (default 3) and
`backoff` (default `"5s"`). A repo-wide search for reads of
`properties.getRetry()` outside `SmsChannelPropertiesTest` returns nothing.
`sendSms()` calls `gateway.send(message)` exactly once; any failure is
returned to the caller as-is.

**Failure mechanism**
Not a bug in the sense of wrong output — it's a fully-modeled configuration
surface with zero behavior behind it. A transient failure (one dropped
connection, one 500 from a provider) that would succeed on a second attempt
or a second provider today just fails, once, permanently.

**Production impact**
Every transient provider blip becomes a lost or manually-retried message.
Combined with Finding 1 (no cost recorded even on success), there is
currently no automatic resilience anywhere in the send path at all.

**Reproduction or evidence**
`SmsChannelPropertiesTest` (existing) only asserts getter/setter roundtrips
on `RetryProperties` — it never asserts that a configured `maxAttempts` value
changes how many times a gateway is actually invoked.

**Recommended fix:** see Required Gap C in README.md for the implemented
bounded retry + deterministic failover, with transient/permanent failure
classification.

**Status:** Fixed (Required Gap C; not a top-3 pick — see README.md).

---

## Finding 8 — Recipient phone numbers logged in plaintext at INFO level in all five gateways

**Severity:** Medium
**Category:** Security
**Location:** `TwilioSmsGateway.java:54`, `VonageSmsGateway.java:55`, `MessageBirdSmsGateway.java:47`, `BudgetSmsGateway.java:39`, `AwsSnsSmsGateway.java:99`

**Observed behavior**
Every gateway's success path logs the raw recipient number at INFO, e.g.
`TwilioSmsGateway.java:54`:
`log.info("Twilio SMS sent: sid={}, to={}", sid, message.to());` — same
pattern (`to={}`, `message.to()`) in all five files.

**Failure mechanism**
`message.to()` is the untransformed E.164 phone number — direct PII. INFO is
typically shipped to centralized log aggregation with broad internal access
and long retention, unlike the cost table's `maskRecipient` masking (used
only in `SmsCostService`, not in the gateways themselves).

**Production impact**
Every SMS sent leaves a permanent, plaintext, broadly-accessible PII trail
in application logs, independent of and inconsistent with the masking
already applied on the cost-recording path. This is a compliance-relevant
gap (GDPR-style data-minimization expectations) for a platform that
explicitly already has a masking convention elsewhere in the same module.

**Reproduction or evidence**
Read the cited lines directly; grep `to={}` across `src/main/java/.../provider/`
returns all five hits.

**Recommended fix**
Reuse `SmsCostService.maskRecipient`-style masking (or a shared utility) in
every gateway's log statements; log the provider's returned message ID for
correlation instead of the raw number.

**Status:** Out of scope due to time-box — see README.md "Scope cut" (flagged, not fixed; smallest fix is mechanical but touches all five gateways and their tests, and was deprioritized behind the three Critical fixes and Required Gaps A–C).

---

## Finding 9 — `sendBulk` aborts the whole batch and loses results for already-sent messages

**Severity:** Medium
**Category:** Reliability
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/api/SmsChannelService.java:26-28`

**Observed behavior**
```java
public List<SmsResult> sendBulk(List<SmsMessage> messages) {
    return messages.stream().map(this::sendSms).toList();
}
```

**Failure mechanism**
`sendSms` can throw (`InvalidPhoneNumberException` from validation, or
`IllegalStateException` from `resolveGateway`). `Stream.map(...).toList()`
is not evaluated lazily-per-element from the caller's perspective — an
exception on message *N* propagates out of `sendBulk` entirely, discarding
the already-computed `SmsResult`s for messages `1..N-1`, even though those
messages were already dispatched to (and, for a successful send, billed by)
the provider.

**Production impact**
A caller sending a batch of 50 messages where message #30 has a malformed
number gets no results at all for messages 1–29 — no confirmation those 29
sends succeeded — even though real SMS already went out and, once Finding 1
is fixed, real cost was already recorded for them. The natural caller
reaction to "my bulk call threw an exception" is to retry the whole batch,
which would re-send (and, absent Finding 4's idempotency work, re-bill)
messages 1–29.

**Reproduction or evidence**
No existing test calls `sendBulk` with a mix of valid and invalid messages;
`sendBulk_MultipleMessages_SendsEachIndividually` (existing) uses uniformly
valid messages only.

**Recommended fix**
Catch per-message failures inside the stream and map them to a
`SmsResult.failure(...)` entry instead of letting the exception escape, so a
batch always returns one result per input message.

**Status:** Out of scope due to time-box — documented only (see README.md
"Scope cut").

---

## Finding 10 — Cost currency is hardcoded to `"EUR"` while the rate registry mixes differently-denominated rates

**Severity:** Medium
**Category:** Financial correctness
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/cost/SmsCostService.java:57`, `formwork-channel-sms/src/main/java/one/formwork/channel/sms/cost/ProviderRateRegistry.java:22-31`

**Observed behavior**
`recordCost` always calls `entity.setCurrency("EUR")` (line 57), unconditionally,
regardless of provider or country. `ProviderRateRegistry`'s constructor seeds
both `"DE"`-keyed rates (lines 23-27) and `"US"`-keyed rates (lines 29-31)
for the same providers, with no currency tag stored anywhere alongside a
rate — `rates` is a flat `Map<String, BigDecimal>` (line 19).

**Failure mechanism**
Twilio/Vonage/AWS US SMS pricing is quoted in USD in the real world; the `US`
rates in this registry (`0.0079`, `0.0068`, `0.0065`) are the right order of
magnitude for USD list prices, not EUR. Recording them with `currency="EUR"`
means any US-priced message's cost row is mislabeled at the currency level —
the number is right, the unit is wrong.

**Production impact**
Any tenant-facing invoice, cross-currency rollup, or finance reconciliation
built on `sms_cost_record.total_cost` + `currency` for US traffic reports the
wrong monetary unit. This wouldn't be caught by any current test — no test
asserts a relationship between country/provider and the recorded currency.

**Reproduction or evidence**
Read `SmsCostService.java:57` and `ProviderRateRegistry.java:22-31` directly;
`ProviderRateRegistryTest` (existing) never asserts anything about currency
(the registry doesn't expose one).

**Recommended fix**
Store a currency alongside each rate in `ProviderRateRegistry` (e.g. change
the map value to a small `Rate(BigDecimal amount, String currency)` record)
and have `recordCost` use it instead of a literal.

**Status:** Documented only — out of scope due to time-box (see README.md
"Scope cut"; this is a real defect but changing the rate registry's value
type touches its public API and every caller, which is a larger, riskier
change than the time-box allows alongside the three Critical fixes).

---

## Finding 11 — `maskRecipient` barely masks the shortest phone numbers the system actually accepts

**Severity:** Low
**Category:** Security
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/cost/SmsCostService.java:114-117`

**Observed behavior**
```java
static String maskRecipient(String phoneNumber) {
    if (phoneNumber == null || phoneNumber.length() < 6) return "***";
    return phoneNumber.substring(0, 4) + "***" + phoneNumber.substring(phoneNumber.length() - 2);
}
```

**Failure mechanism**
This always reveals the first 4 and last 2 characters. `PhoneNumberValidatorTest`
(existing, line 22: `"+1234567"`) confirms the module accepts 8-character
E.164 numbers as valid input (`+` plus 7 digits — the documented minimum).
For an 8-character number, 4 + 2 = 6 of 8 characters (75%) survive
"masking" unchanged; only the middle 2 digits are actually hidden. The
function's own existing test (`maskRecipient_Normal_MasksMiddle`) only
exercises a 13-character number, where the same fixed 4+2 reveal is a much
smaller fraction (46%) — masking the effectiveness of the bug.

**Production impact**
Low — this only affects the masked value that already appears in
`sms_cost_record.recipient` for anyone with DB/reporting access, not raw
logs, so the exposure is limited to a partial number rather than a full one
regardless of length. It does mean the masking is far weaker than a reader
of `maskRecipient_Normal_MasksMiddle` would assume for realistic short
numbers.

**Reproduction or evidence**
`maskRecipient("+1234567")` → `"+123***67"`: 6 of the original 8 characters
are unchanged.

**Recommended fix**
Mask a fixed *proportion* of the number (or a fixed minimum number of hidden
digits) instead of a fixed count from each end, e.g. keep only the first 3
and last 2 regardless of length, hide everything else.

**Status:** Documented only — out of scope due to time-box (see README.md).

---

## Finding 12 — AWS SNS sends the recipient number and message body as GET query-string parameters

**Severity:** Low
**Category:** Security
**Location:** `formwork-channel-sms/src/main/java/one/formwork/channel/sms/provider/AwsSnsSmsGateway.java:89-95`

**Observed behavior**
The `PhoneNumber` and `Message` values go into the request URI as query
parameters on a `GET` (line 89: `webClient.get()`), not into a POST body.
AWS's Query API does accept `GET` for `Publish`, so this is not a
functional bug (independent of Finding 3's encoding bug) — but URLs are far
more likely than POST bodies to be captured verbatim by intermediate
proxies, load balancer access logs, or HTTP client debug/trace logging that
a future maintainer might enable.

**Production impact**
Low-to-medium, contingent on infrastructure the module doesn't control
(corporate proxies, LB access logs). Worth fixing opportunistically, not on
its own.

**Reproduction or evidence**
Read `AwsSnsSmsGateway.java:89-95` directly.

**Recommended fix**
Switch to SNS's `POST` form (still Query-API/SigV4, just with the
parameters in a signed request body instead of the URI) when reworking this
gateway for Finding 3.

**Status:** Documented only — out of scope due to time-box (see README.md).

---

## Summary

| # | Finding | Severity | Category | Status |
|---|---|---|---|---|
| 1 | Cost never recorded | Critical | Financial correctness | **Fixed (top 3)** |
| 2 | No tenant-aware provider selection | Critical | Tenant isolation | **Fixed (top 3)** |
| 3 | AWS SNS SigV4 encoding breaks real messages | Critical | Reliability / AWS SNS | **Fixed (top 3)** |
| 4 | No idempotency on cost recording | Critical | Financial correctness | **Fixed (with #1)** |
| 5 | `*WireMockTest` doesn't use WireMock | High | Testing | Documented; pattern fixed for AWS SNS only |
| 6 | No timeout anywhere in HTTP layer | High | Reliability | Fixed (Required Gap C) |
| 7 | No retry/failover logic | High | Reliability | Fixed (Required Gap C) |
| 8 | PII logged in plaintext (5 gateways) | Medium | Security | Documented only |
| 9 | `sendBulk` aborts batch on one bad message | Medium | Reliability | Documented only |
| 10 | Currency hardcoded to EUR | Medium | Financial correctness | Documented only |
| 11 | Weak masking for short phone numbers | Low | Security | Documented only |
| 12 | AWS SNS PII in GET query string | Low | Security | Documented only |

Findings 1, 2, and 3 (with 4 folded into 1) are the three fixed under Part 2.
Findings 6 and 7 are fixed as part of Required Gap C (Part 3) since retry and
timeouts are inseparable. Findings 5, 8, 9, 10, 11, 12 are real, verified
defects that are documented rather than fixed — see README.md "Scope cut"
for the reasoning behind each cut.
