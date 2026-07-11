package one.formwork.channel.sms.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import one.formwork.channel.sms.cost.SmsCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for REVIEW.md Finding 2 / README.md Required Gap A:
 * SmsChannelService.resolveGateway() reads only the single global
 * `formwork.sms-channel.provider` value and never looks at
 * SmsMessage.tenantId(), so two tenants configured for different providers
 * are, today, structurally forced onto the same one.
 */
@ExtendWith(MockitoExtension.class)
class TenantAwareProviderSelectionTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Mock private SmsGateway twilioGateway;
    @Mock private SmsGateway vonageGateway;
    @Mock private SmsCostService costService;

    @Test
    void sendSms_TenantsWithDifferentProviderOverrides_EachUsesItsOwnProvider() {
        when(twilioGateway.supports("TWILIO")).thenReturn(true);
        when(vonageGateway.supports("VONAGE")).thenReturn(true);
        when(twilioGateway.send(any())).thenReturn(SmsResult.success("t-1", "TWILIO", 1));
        when(vonageGateway.send(any())).thenReturn(SmsResult.success("v-1", "VONAGE", 1));

        SmsChannelProperties properties = new SmsChannelProperties();
        properties.setProvider("TWILIO"); // global default
        // Only tenant B overrides away from the global default.
        properties.setTenantProviders(Map.of(tenantB.toString(), "VONAGE"));

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway, vonageGateway), properties, costService);

        SmsResult resultA = service.sendSms(new SmsMessage("+4915112345678", "Hi A", tenantA));
        SmsResult resultB = service.sendSms(new SmsMessage("+4915112345678", "Hi B", tenantB));

        assertEquals("TWILIO", resultA.provider(), "tenant with no override should use the global default");
        assertEquals("VONAGE", resultB.provider(), "tenant B's override must route it to Vonage, not the global default");
        verify(vonageGateway).send(any());
        verify(twilioGateway).send(any());
    }

    @Test
    void sendSms_TenantOverrideProviderNotRegistered_FailsWithoutFallingBackToGlobalDefault() {
        when(twilioGateway.supports("MESSAGEBIRD")).thenReturn(false);

        SmsChannelProperties properties = new SmsChannelProperties();
        properties.setProvider("TWILIO");
        // Tenant A is configured for a provider that has no matching gateway
        // bean (e.g. not enabled/credentialed) - must fail loudly rather
        // than silently sending through the global default (TWILIO) or any
        // other tenant's provider.
        properties.setTenantProviders(Map.of(tenantA.toString(), "MESSAGEBIRD"));

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway), properties, costService);

        assertThrows(IllegalStateException.class,
                () -> service.sendSms(new SmsMessage("+4915112345678", "Hi", tenantA)));
        verify(twilioGateway, never()).send(any());
    }
}
