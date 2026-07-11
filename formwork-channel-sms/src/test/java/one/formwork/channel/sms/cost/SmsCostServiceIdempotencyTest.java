package one.formwork.channel.sms.cost;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for REVIEW.md Finding 4: recordCost has no idempotency
 * guard, so calling it twice for the same provider message (e.g. a
 * caller-level retry after a successful-but-slow send) persists two cost
 * records for one real SMS.
 * <p>
 * Uses SmsCostRepository.existsByProviderAndMessageId, which does not exist
 * on the original repository - fails to compile there.
 */
@ExtendWith(MockitoExtension.class)
class SmsCostServiceIdempotencyTest {

    private final UUID tenantId = UUID.randomUUID();

    @Mock private SmsCostRepository repository;
    @Mock private ProviderRateRegistry rateRegistry;

    private SmsCostService service;

    @BeforeEach
    void setUp() {
        service = new SmsCostService(repository, rateRegistry);
    }

    @Test
    void recordCost_CalledTwiceForSameProviderMessageId_PersistsOnlyOnce() {
        one.formwork.channel.sms.api.SmsResult result =
                one.formwork.channel.sms.api.SmsResult.success("msg-dup-1", "TWILIO", 1);
        when(rateRegistry.getRate("TWILIO", "DE")).thenReturn(new BigDecimal("0.075"));
        // First call: not seen yet. Second call: already recorded.
        when(repository.existsByProviderAndMessageId("TWILIO", "msg-dup-1"))
                .thenReturn(false, true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmsCostEntity first = service.recordCost(tenantId, "+491234567890", result);
        SmsCostEntity second = service.recordCost(tenantId, "+491234567890", result);

        assertNotNull(first, "first call should persist a cost record");
        assertNull(second, "second call for the same provider message must not persist a duplicate");
        verify(repository, times(1)).save(any());
    }
}
