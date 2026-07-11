package one.formwork.channel.sms.api;

import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.validation.PhoneNumberValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SmsChannelService {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelService.class);

    private final List<SmsGateway> gateways;
    private final SmsChannelProperties properties;
    private final SmsCostService costService;

    public SmsChannelService(List<SmsGateway> gateways, SmsChannelProperties properties, SmsCostService costService) {
        this.gateways = gateways;
        this.properties = properties;
        this.costService = costService;
    }

    /**
     * Sends with bounded retry + deterministic failover (see
     * docs/adr/0001-*.md for the full trade-off writeup):
     * <ul>
     *   <li>Candidates: the tenant's resolved primary provider, then the
     *       rest of {@code formwork.sms-channel.failover-order}.</li>
     *   <li>A retryable failure (5xx, or the generic "request never reached
     *       the provider" SEND_ERROR) advances to the next candidate in the
     *       chain, up to {@code retry.max-attempts} total attempts.</li>
     *   <li>A permanent failure (4xx, CONFIG_ERROR, a TIMEOUT - see
     *       {@link #isPermanent}, or an unrecognized code) stops immediately:
     *       no further retry, no failover.</li>
     *   <li>Cost is recorded exactly once, only for the attempt that
     *       actually returns success.</li>
     * </ul>
     * Consistency trade-off: the SMS send itself is the source of truth. If
     * it succeeds, that result is returned to the caller regardless of what
     * happens next in cost recording - failing the whole request because
     * bookkeeping had a problem would make the caller retry a message that
     * was already, irreversibly, sent. A cost-recording failure is
     * therefore logged loudly (it needs alerting/reconciliation in a real
     * deployment) rather than propagated.
     */
    public SmsResult sendSms(SmsMessage message) {
        PhoneNumberValidator.validate(message.to());
        List<SmsGateway> chain = buildFailoverChain(message.tenantId());
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        Duration backoff = parseBackoff(properties.getRetry().getBackoff());

        SmsResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            SmsGateway gateway = chain.get((attempt - 1) % chain.size());
            lastResult = gateway.send(message);

            if (lastResult.isSuccess()) {
                recordCostSafely(message, lastResult);
                return lastResult;
            }

            if (isPermanent(lastResult)) {
                log.warn("Non-retryable failure from {} for tenant {}, errorCode={} - not retrying",
                        lastResult.provider(), message.tenantId(), lastResult.errorCode());
                break;
            }

            if (attempt < maxAttempts) {
                sleep(backoff);
            }
        }
        return lastResult;
    }

    public List<SmsResult> sendBulk(List<SmsMessage> messages) {
        return messages.stream().map(this::sendSms).toList();
    }

    public void handleDeliveryCallback(String provider, Map<String, String> params) {
        // Provider-specific callback handling
    }

    private void recordCostSafely(SmsMessage message, SmsResult result) {
        try {
            costService.recordCost(message.tenantId(), message.to(), result);
        } catch (DataAccessException e) {
            log.error("Cost recording failed after a successful send: tenant={}, provider={}, messageId={}. "
                            + "The SMS was already sent; this needs manual reconciliation.",
                    message.tenantId(), result.provider(), result.messageId(), e);
        }
    }

    /**
     * Builds the ordered candidate list for one send: the tenant's resolved
     * primary provider first (fails loudly here if it isn't registered - no
     * cross-tenant or global-default fallback for an explicitly-configured
     * override, same as before retry/failover existed), then the rest of
     * the configured global failover order, skipping the primary and any
     * provider with no registered gateway.
     */
    private List<SmsGateway> buildFailoverChain(UUID tenantId) {
        String primaryType = resolveProviderType(tenantId);
        SmsGateway primary = findGateway(primaryType)
                .orElseThrow(() -> new IllegalStateException(
                        "No SmsGateway configured for provider: " + primaryType
                                + (tenantId != null ? " (tenant " + tenantId + ")" : "")));

        List<SmsGateway> chain = new ArrayList<>();
        chain.add(primary);
        for (String providerCode : properties.getFailoverOrder()) {
            if (providerCode.equalsIgnoreCase(primaryType)) {
                continue;
            }
            findGateway(providerCode).ifPresent(chain::add);
        }
        return chain;
    }

    private Optional<SmsGateway> findGateway(String providerType) {
        return gateways.stream().filter(g -> g.supports(providerType)).findFirst();
    }

    private String resolveProviderType(UUID tenantId) {
        if (tenantId != null) {
            String override = properties.getTenantProviders().get(tenantId.toString());
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        return properties.getProvider();
    }

    private boolean isPermanent(SmsResult result) {
        String code = result.errorCode();
        if (code == null) {
            return true;
        }
        if ("SEND_ERROR".equals(code)) {
            return false;
        }
        if (code.matches("\\d{3}")) {
            return Integer.parseInt(code) < 500;
        }
        // CONFIG_ERROR, TIMEOUT, and any provider-specific code we don't
        // recognize (Vonage's numeric status, BudgetSMS's API_ERROR,
        // EMPTY_RESPONSE, ...): treat as permanent. Retrying a failure mode
        // we don't understand - or a timeout, whose outcome is genuinely
        // unknown - is worse than not retrying (see README.md "Known
        // limitations" for why TIMEOUT specifically isn't retried here).
        return true;
    }

    private Duration parseBackoff(String backoff) {
        try {
            return DurationStyle.detectAndParse(backoff);
        } catch (IllegalArgumentException e) {
            return Duration.ofSeconds(5);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
