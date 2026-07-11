package one.formwork.channel.sms;

import java.util.List;
import java.util.UUID;
import one.formwork.channel.sms.api.SmsChannelService;
import one.formwork.channel.sms.api.SmsGateway;
import one.formwork.channel.sms.api.SmsMessage;
import one.formwork.channel.sms.api.SmsResult;
import one.formwork.channel.sms.config.SmsFlywayAutoConfiguration;
import one.formwork.channel.sms.cost.SmsCostEntity;
import one.formwork.channel.sms.cost.SmsCostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Gap D: one honest integration test. Starts a real Spring
 * context, uses the real SmsChannelService / SmsCostService / JPA
 * repository wiring (none of them mocked), runs the module's actual Flyway
 * migrations against a real (H2, see "Known limitations" below) database,
 * and asserts on persisted state read back from that database - not on
 * what a mock recorded it was called with.
 * <p>
 * Proves end-to-end: REVIEW.md Finding 2 (tenant-aware provider selection -
 * two tenants configured for different providers are actually routed to
 * different gateways) and Finding 1 (cost recording actually happens and is
 * actually persisted, correctly scoped per tenant).
 * <p>
 * The only test doubles here are the two SmsGateway beans, which are the
 * external network boundary (already covered by
 * TwilioSmsGatewayRealHttpTest and the per-gateway unit tests) - not the
 * service/persistence layer this test exists to prove.
 * <p>
 * Known limitation: the real platform targets PostgreSQL
 * (flyway-database-postgresql), and V1/V2's migration SQL uses
 * PostgreSQL-specific types (TIMESTAMPTZ) that H2 does not understand, even
 * in MODE=PostgreSQL compatibility mode - confirmed by actually trying it
 * (Flyway fails with "Unknown data type: TIMESTAMPTZ"). Postgres itself
 * isn't available in this review environment either (no Docker daemon
 * running, so Testcontainers was not used - see README.md "Known
 * limitations"). Rather than edit the real migration SQL just to make a
 * test pass, this test disables this module's Flyway auto-configuration
 * and lets Hibernate generate the schema from the same JPA mapping
 * (SmsCostEntity / TenantScopedEntity) that production uses instead. That
 * means this test does NOT prove the V1/V2 SQL files themselves are
 * correct against Postgres - only that the service/repository/persistence
 * wiring and the tenant-scoped queries behave correctly against a real
 * database.
 */
@SpringBootTest(classes = SmsChannelIntegrationTest.TestApp.class, properties = {
        "formwork.sms-channel.provider=TWILIO",
        "spring.datasource.url=jdbc:h2:mem:sms_channel_it;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Spring Boot's own Flyway autoconfiguration (not just this
        // module's SmsFlywayAutoConfiguration) recursively scans the
        // default classpath:db/migration location and would find and try
        // to run V1/V2 regardless of the exclude below - see the class
        // Javadoc for why that fails against H2.
        "spring.flyway.enabled=false"
})
class SmsChannelIntegrationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @DynamicPropertySource
    static void tenantOverride(DynamicPropertyRegistry registry) {
        // Only tenant B has an override; tenant A must fall through to the
        // global default (TWILIO) configured above.
        registry.add("formwork.sms-channel.tenant-providers." + TENANT_B, () -> "VONAGE");
    }

    @Autowired
    private SmsChannelService smsChannelService;

    @Autowired
    private SmsCostRepository smsCostRepository;

    @Test
    @Transactional
    void sendSms_TwoTenantsDifferentProviders_RoutesCorrectlyAndPersistsIsolatedCostRecords() {
        SmsResult resultA = smsChannelService.sendSms(new SmsMessage("+4915112345678", "Hi A", TENANT_A));
        SmsResult resultB = smsChannelService.sendSms(new SmsMessage("+4915112345679", "Hi B", TENANT_B));

        assertEquals("TWILIO", resultA.provider(), "tenant A has no override - must use the global default");
        assertEquals("VONAGE", resultB.provider(), "tenant B's override must actually route it to Vonage");

        // Read back from the real database, not from the SmsResult already
        // in hand - this is what proves recordCost actually persisted.
        List<SmsCostEntity> costsA = smsCostRepository.findByTenantIdAndProvider(TENANT_A, "TWILIO");
        List<SmsCostEntity> costsB = smsCostRepository.findByTenantIdAndProvider(TENANT_B, "VONAGE");

        assertEquals(1, costsA.size(), "tenant A's send must produce exactly one persisted cost record");
        assertEquals(1, costsB.size(), "tenant B's send must produce exactly one persisted cost record");
        assertEquals(TENANT_A, costsA.get(0).getTenantId());
        assertEquals(TENANT_B, costsB.get(0).getTenantId());
        assertTrue(costsA.get(0).getTotalCost().signum() > 0, "recorded cost must be a real positive amount, not zero/null");

        // Tenant isolation extends to cost records: tenant A's query must
        // never surface a row that belongs to tenant B, and vice versa.
        assertTrue(smsCostRepository.findByTenantIdAndProvider(TENANT_A, "VONAGE").isEmpty());
        assertTrue(smsCostRepository.findByTenantIdAndProvider(TENANT_B, "TWILIO").isEmpty());
    }

    @SpringBootApplication(exclude = SmsFlywayAutoConfiguration.class)
    static class TestApp {

        @Bean
        SmsGateway twilioGateway() {
            return new FakeGateway("TWILIO");
        }

        @Bean
        SmsGateway vonageGateway() {
            return new FakeGateway("VONAGE");
        }
    }

    /**
     * A hand-written fake, not a Mockito mock - this is the external
     * network boundary being substituted, not the service under test.
     */
    static class FakeGateway implements SmsGateway {
        private final String provider;

        FakeGateway(String provider) {
            this.provider = provider;
        }

        @Override
        public SmsResult send(SmsMessage message) {
            return SmsResult.success("fake-" + UUID.randomUUID(), provider, 1);
        }

        @Override
        public boolean supports(String providerType) {
            return provider.equalsIgnoreCase(providerType);
        }

        @Override
        public String getProviderName() {
            return provider;
        }
    }
}
