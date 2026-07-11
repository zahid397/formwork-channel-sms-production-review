-- V2: Prevent duplicate cost records for the same provider message.
--
-- SmsCostService.recordCost is called once per successful send, but a
-- caller-level retry (e.g. an upstream client re-invoking sendSms after a
-- slow-but-successful attempt) could otherwise invoke it twice for the same
-- underlying SMS, double-billing the tenant. The application checks first
-- (SmsCostRepository.existsByProviderAndMessageId) as a fast path, but this
-- constraint is the actual correctness guarantee under concurrent/racing
-- calls - see REVIEW.md Finding 4 and docs/adr/0001-*.md.
--
-- Multiple NULL message_id rows remain allowed: standard SQL treats NULL as
-- distinct from NULL for uniqueness purposes, which matches gateways whose
-- provider response is missing an id (recordCost has nothing to dedupe on
-- in that case and always inserts, same as before this migration).
CREATE UNIQUE INDEX IF NOT EXISTS uq_sms_cost_provider_message
    ON sms_cost_record (provider, message_id);
