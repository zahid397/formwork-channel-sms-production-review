package one.formwork.channel.sms.api;

import one.formwork.channel.sms.validation.PhoneNumberValidator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SmsChannelService {

    private final List<SmsGateway> gateways;
    private final SmsChannelProperties properties;

    public SmsChannelService(List<SmsGateway> gateways, SmsChannelProperties properties) {
        this.gateways = gateways;
        this.properties = properties;
    }

    public SmsResult sendSms(SmsMessage message) {
        PhoneNumberValidator.validate(message.to());
        SmsGateway gateway = resolveGateway(message.tenantId());
        return gateway.send(message);
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
