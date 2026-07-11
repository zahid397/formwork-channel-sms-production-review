package one.formwork.channel.sms.config;

import one.formwork.channel.sms.api.*;
import one.formwork.channel.sms.provider.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import one.formwork.base.tenant.config.TenantBaseAutoConfiguration;

/**
 * Registers one {@link SmsGateway} bean per provider that has credentials
 * configured, so more than one can be active in the same deployment at
 * once. This is required for both tenant-aware provider selection (a tenant
 * override is useless if only the global default's gateway bean exists) and
 * for failover (there must be a second gateway to fail over to).
 * <p>
 * Each bean is gated on that provider's own credential being present, not
 * on the legacy {@code formwork.sms-channel.provider} selector - previously
 * all five beans were gated on that single property, which meant at most
 * one gateway could ever exist in the context. {@code provider} is still
 * read (by SmsChannelService) as the default choice for tenants with no
 * override; it no longer controls which beans exist.
 */
@AutoConfiguration(after = TenantBaseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "formwork.sms-channel", name = "provider")
@EnableConfigurationProperties(SmsChannelProperties.class)

public class SmsChannelAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "formwork.sms-channel.twilio", name = "account-sid")
    public SmsGateway twilioGateway(SmsChannelProperties props) {
        return new TwilioSmsGateway(props.getTwilio());
    }

    @Bean
    @ConditionalOnProperty(prefix = "formwork.sms-channel.vonage", name = "api-key")
    public SmsGateway vonageGateway(SmsChannelProperties props) {
        return new VonageSmsGateway(props.getVonage());
    }

    @Bean
    @ConditionalOnProperty(prefix = "formwork.sms-channel.aws-sns", name = "enabled", havingValue = "true")
    public SmsGateway awsSnsGateway(SmsChannelProperties props) {
        return new AwsSnsSmsGateway(props.getAwsSns());
    }

    @Bean
    @ConditionalOnProperty(prefix = "formwork.sms-channel.budget-sms", name = "username")
    public SmsGateway budgetSmsGateway(SmsChannelProperties props) {
        return new BudgetSmsGateway(props.getBudgetSms());
    }

    @Bean
    @ConditionalOnProperty(prefix = "formwork.sms-channel.messagebird", name = "access-key")
    public SmsGateway messageBirdGateway(SmsChannelProperties props) {
        return new MessageBirdSmsGateway(props.getMessagebird());
    }

}
