package one.formwork.channel.sms.api;

import java.util.List;
import java.util.UUID;
import one.formwork.channel.sms.cost.SmsCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for REVIEW.md Findings 6 & 7 / README.md Required Gap C:
 * before this, sendSms() called its gateway exactly once with no retry and
 * no failover, and RetryProperties was configured but read by nothing.
 * <p>
 * Covers the four scenarios ASSIGNMENT.md Part 3.3 and the task's Phase 5.C
 * both call out: transient-fail-then-succeed, permanent failure not
 * retried, all providers exhausted, and no double cost record.
 */
@ExtendWith(MockitoExtension.class)
class RetryAndFailoverTest {

    private final UUID tenantId = UUID.randomUUID();

    @Mock private SmsGateway twilioGateway;
    @Mock private SmsGateway vonageGateway;
    @Mock private SmsCostService costService;

    private SmsChannelProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SmsChannelProperties();
        properties.setProvider("TWILIO");
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setBackoff("1ms"); // keep the test fast
        properties.setFailoverOrder(List.of("TWILIO", "VONAGE"));
    }

    @Test
    void sendSms_PrimaryTransientFailure_FailsOverToSecondProviderAndSucceeds() {
        when(twilioGateway.supports("TWILIO")).thenReturn(true);
        when(vonageGateway.supports("VONAGE")).thenReturn(true);
        when(twilioGateway.send(any())).thenReturn(SmsResult.failure("TWILIO", "500", "Internal Server Error"));
        SmsResult success = SmsResult.success("v-1", "VONAGE", 1);
        when(vonageGateway.send(any())).thenReturn(success);

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway, vonageGateway), properties, costService);

        SmsResult result = service.sendSms(new SmsMessage("+4915112345678", "Hi", tenantId));

        assertEquals(success, result);
        verify(twilioGateway, times(1)).send(any());
        verify(vonageGateway, times(1)).send(any());
    }

    @Test
    void sendSms_PermanentValidationFailure_IsNotRetriedOrFailedOver() {
        when(twilioGateway.supports("TWILIO")).thenReturn(true);
        when(vonageGateway.supports("VONAGE")).thenReturn(true);
        // 400 = permanent per SmsChannelService's classification policy.
        when(twilioGateway.send(any())).thenReturn(SmsResult.failure("TWILIO", "400", "Invalid destination"));

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway, vonageGateway), properties, costService);

        SmsResult result = service.sendSms(new SmsMessage("+4915112345678", "Hi", tenantId));

        assertFalse(result.isSuccess());
        verify(twilioGateway, times(1)).send(any());
        verify(vonageGateway, never()).send(any());
    }

    @Test
    void sendSms_AllProvidersFail_ReturnsHonestFailureWithBoundedAttempts() {
        when(twilioGateway.supports("TWILIO")).thenReturn(true);
        when(vonageGateway.supports("VONAGE")).thenReturn(true);
        when(twilioGateway.send(any())).thenReturn(SmsResult.failure("TWILIO", "500", "Internal Server Error"));
        when(vonageGateway.send(any())).thenReturn(SmsResult.failure("VONAGE", "503", "Service Unavailable"));

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway, vonageGateway), properties, costService);

        SmsResult result = service.sendSms(new SmsMessage("+4915112345678", "Hi", tenantId));

        assertFalse(result.isSuccess(), "every provider failed - this must be reported as a failure, never as success");
        // maxAttempts=3, cycling twilio/vonage/twilio - never more than 3 total attempts.
        int totalAttempts = 0;
        totalAttempts += org.mockito.Mockito.mockingDetails(twilioGateway).getInvocations().size();
        totalAttempts += org.mockito.Mockito.mockingDetails(vonageGateway).getInvocations().size();
        assertTrue(totalAttempts <= 3, "attempts must be bounded by maxAttempts, was " + totalAttempts);
    }

    @Test
    void sendSms_SucceedsAfterFailover_RecordsCostExactlyOnce() {
        when(twilioGateway.supports("TWILIO")).thenReturn(true);
        when(vonageGateway.supports("VONAGE")).thenReturn(true);
        when(twilioGateway.send(any())).thenReturn(SmsResult.failure("TWILIO", "500", "Internal Server Error"));
        SmsResult success = SmsResult.success("v-1", "VONAGE", 1);
        when(vonageGateway.send(any())).thenReturn(success);

        SmsChannelService service = new SmsChannelService(List.of(twilioGateway, vonageGateway), properties, costService);
        service.sendSms(new SmsMessage("+4915112345678", "Hi", tenantId));

        // Exactly one recordCost call, for the final successful attempt only -
        // never for the failed first attempt.
        verify(costService, times(1)).recordCost(any(), any(), any());
        verify(costService, never()).recordCost(any(), any(), org.mockito.ArgumentMatchers.argThat(r -> !r.isSuccess()));
    }
}
