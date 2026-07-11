package one.formwork.channel.sms.provider;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for REVIEW.md Finding 3: AwsSnsSmsGateway signs and sends
 * its AWS SigV4 request using java.net.URLEncoder (HTML form encoding)
 * instead of RFC 3986 URI encoding. The two disagree on space ('+' vs
 * '%20'), '~' and '*' - and AWS's signature verification requires RFC 3986,
 * so any message body containing a space fails with SignatureDoesNotMatch
 * in production.
 * <p>
 * Exercises the private {@code encode()} method directly (same reflection
 * technique the original SmsCostEntityOnCreateTest.java already uses in this
 * codebase) so the fix is provable without needing real AWS credentials or a
 * live network call.
 */
class AwsSnsSmsGatewayEncodingTest {

    private static String encode(String value) throws Exception {
        Method m = AwsSnsSmsGateway.class.getDeclaredMethod("encode", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    @Test
    void encode_Space_ProducesPercent20NotPlus() throws Exception {
        assertEquals("%20", encode(" "));
    }

    @Test
    void encode_TypicalSmsBody_MatchesRfc3986() throws Exception {
        assertEquals("Hello%2C%20World%21", encode("Hello, World!"));
    }

    @Test
    void encode_UnreservedCharacters_AreNotPercentEncoded() throws Exception {
        String unreserved = "AZaz09-_.~";
        assertEquals(unreserved, encode(unreserved));
    }

    @Test
    void encode_Tilde_IsNotPercentEncoded() throws Exception {
        assertEquals("~", encode("~"));
    }

    @Test
    void encode_Asterisk_IsPercentEncoded() throws Exception {
        assertEquals("%2A", encode("*"));
    }
}
