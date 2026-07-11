package one.formwork.channel.sms.provider;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Shared timeout policy for all five gateways (REVIEW.md Finding 6: none of
 * them had any timeout before this, so a hung provider blocked the sending
 * thread forever).
 * <p>
 * {@link #isTimeout} lets a gateway's catch block tell a timeout apart from
 * an outright connection failure. That distinction matters for retry
 * classification: a connection refusal means the request never reached the
 * provider (safe to retry), but a timeout means we simply stopped waiting -
 * the provider may have already processed it. See
 * SmsChannelService.FailureClassifier and docs/adr/0001-*.md.
 */
final class GatewayTimeouts {

    static final Duration DEFAULT = Duration.ofSeconds(10);

    private GatewayTimeouts() {}

    static boolean isTimeout(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof TimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
