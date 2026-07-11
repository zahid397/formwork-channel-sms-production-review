package one.formwork.channel.sms.api;

import java.util.List;
import java.util.UUID;
import one.formwork.channel.sms.cost.SmsCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for REVIEW.md Finding 1: SmsChannelService never calls
 * SmsCostService.recordCost anywhere, so a real SMS send never produces a
 * cost record despite the cost-recording infrastructure being fully built.
 * <p>
 * This test uses a three-argument SmsChannelService constructor
 * (gateways, properties, costService) that does not exist on the original
 * code - it fails to compile there, which is as red as a regression test
 * gets. See README.md "Cost recording consistency trade-off" for what
 * happens when the SMS send succeeds but recordCost itself throws.
 */
@ExtendWith(MockitoExtension.class)
class SmsChannelServiceCostRecordingTest {

    private final UUID tenantId = UUID.randomUUID();

    @Mock private SmsGateway gateway;
    @Mock private SmsChannelProperties properties;
    @Mock private SmsCostService costService;

    @Test
    void sendSms_SuccessfulSend_RecordsCostExactlyOnce() {
        when(properties.getProvider()).thenReturn("TWILIO");
        when(properties.getRetry()).thenReturn(new SmsChannelProperties.RetryProperties());
        when(properties.getFailoverOrder()).thenReturn(List.of());
        when(gateway.supports("TWILIO")).thenReturn(true);
        SmsResult success = SmsResult.success("msg-1", "TWILIO", 1);
        when(gateway.send(any())).thenReturn(success);

        SmsChannelService service = new SmsChannelService(List.of(gateway), properties, costService);
        SmsMessage message = new SmsMessage("+4915112345678", "Hello", tenantId);

        SmsResult result = service.sendSms(message);

        assertEquals(success, result);
        verify(costService, times(1)).recordCost(tenantId, "+4915112345678", success);
    }

    @Test
    void sendSms_FailedSend_DoesNotRecordCost() {
        when(properties.getProvider()).thenReturn("TWILIO");
        // A permanent (4xx) failure so this test exercises exactly one
        // attempt regardless of retry/failover behavior - the point here is
        // "failure never records cost", not retry counting.
        SmsChannelProperties.RetryProperties retry = new SmsChannelProperties.RetryProperties();
        when(properties.getRetry()).thenReturn(retry);
        when(properties.getFailoverOrder()).thenReturn(List.of());
        when(gateway.supports("TWILIO")).thenReturn(true);
        SmsResult failure = SmsResult.failure("TWILIO", "400", "Bad request");
        when(gateway.send(any())).thenReturn(failure);

        SmsChannelService service = new SmsChannelService(List.of(gateway), properties, costService);
        SmsMessage message = new SmsMessage("+4915112345678", "Hello", tenantId);

        service.sendSms(message);

        verify(costService, never()).recordCost(any(), any(), any());
    }
}
