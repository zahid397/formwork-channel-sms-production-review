package one.formwork.channel.sms.api;

import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.validation.PhoneNumberValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
     * Consistency trade-off (see docs/adr/0001-*.md): the SMS send itself is
     * the source of truth. If it succeeds, that result is returned to the
     * caller regardless of what happens next - failing the whole request
     * because cost bookkeeping had a problem would make the caller retry a
     * message that was already, irreversibly, sent. A cost-recording
     * failure is therefore logged loudly (it needs alerting/reconciliation
     * in a real deployment) rather than propagated.
     */
    public SmsResult sendSms(SmsMessage message) {
        PhoneNumberValidator.validate(message.to());
        SmsGateway gateway = resolveGateway(message.tenantId());
        SmsResult result = gateway.send(message);
        if (result.isSuccess()) {
            recordCostSafely(message, result);
        }
        return result;
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

    public List<SmsResult> sendBulk(List<SmsMessage> messages) {
        return messages.stream().map(this::sendSms).toList();
    }

    public void handleDeliveryCallback(String provider, Map<String, String> params) {
        // Provider-specific callback handling
    }

    /**
     * Resolves the gateway for a message. A tenant with an explicit
     * override in {@code formwork.sms-channel.tenant-providers} always uses
     * it - and only it: if that provider isn't registered (not
     * enabled/configured), this fails loudly rather than silently falling
     * back to the global default or to any other tenant's provider. A
     * tenant with no override uses the global default, same as before.
     */
    private SmsGateway resolveGateway(UUID tenantId) {
        String providerType = resolveProviderType(tenantId);
        return gateways.stream()
                .filter(g -> g.supports(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No SmsGateway configured for provider: " + providerType
                                + (tenantId != null ? " (tenant " + tenantId + ")" : "")));
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
}
