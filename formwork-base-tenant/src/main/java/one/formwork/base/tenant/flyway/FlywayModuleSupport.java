package one.formwork.base.tenant.flyway;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * STUB - reconstructed for this review only. formwork-channel-sms calls
 * {@code FlywayModuleSupport.create(dataSource, "cs")} expecting a
 * {@link Flyway} that migrates from {@code classpath:db/migration/cs}, which
 * is where its own {@code V1__create_sms_cost_table.sql} lives. This does
 * exactly that and nothing else (no multi-schema orchestration, which the
 * real platform module likely adds for other call sites).
 */
public final class FlywayModuleSupport {

    private FlywayModuleSupport() {}

    public static Flyway create(DataSource dataSource, String moduleTag) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/" + moduleTag)
                .baselineOnMigrate(true)
                .load();
    }
}
