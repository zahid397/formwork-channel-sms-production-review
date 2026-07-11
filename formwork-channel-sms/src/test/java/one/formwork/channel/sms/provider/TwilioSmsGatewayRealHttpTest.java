package one.formwork.channel.sms.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsMessage;
import one.formwork.channel.sms.api.SmsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Gap D / ASSIGNMENT.md Part 3.4: "Add a test that exercises a
 * gateway over real HTTP against a stub server, asserting on the bytes
 * actually sent - the URL, headers, and body."
 * <p>
 * The four existing {@code *WireMockTest} files (REVIEW.md Finding 5) don't
 * do this - despite the name, they mock WebClient itself and never issue a
 * real HTTP call, and there was no WireMock dependency in this module's
 * pom.xml at all. This test uses the same reflection technique those files
 * already use to inject a replacement WebClient into the gateway, but here
 * it's a real WebClient pointed at a real WireMock server, not a Mockito
 * mock - so a broken URL, a dropped header, or a malformed body would
 * actually fail this test.
 */
class TwilioSmsGatewayRealHttpTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String ACCOUNT_SID = "AC_TEST_SID";
    private static final String AUTH_TOKEN = "test_auth_token";

    private TwilioSmsGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        SmsChannelProperties.TwilioProperties config = new SmsChannelProperties.TwilioProperties();
        config.setAccountSid(ACCOUNT_SID);
        config.setAuthToken(AUTH_TOKEN);
        config.setFromNumber("+15005550006");
        gateway = new TwilioSmsGateway(config);

        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes());
        WebClient realClientPointedAtWireMock = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader("Authorization", basicAuth)
                .build();
        var field = TwilioSmsGateway.class.getDeclaredField("webClient");
        field.setAccessible(true);
        field.set(gateway, realClientPointedAtWireMock);
    }

    @Test
    void send_RealHttpCall_SendsCorrectUrlHeadersAndBody() {
        wireMock.stubFor(post(urlEqualTo("/Accounts/" + ACCOUNT_SID + "/Messages.json"))
                .willReturn(okJson("{\"sid\":\"SM123\",\"num_segments\":\"1\"}")));

        SmsResult result = gateway.send(new SmsMessage("+4915112345678", "Hello, World!", UUID.randomUUID()));

        assertTrue(result.isSuccess());
        assertEquals("SM123", result.messageId());

        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes());
        wireMock.verify(postRequestedFor(urlEqualTo("/Accounts/" + ACCOUNT_SID + "/Messages.json"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(equalTo("To=%2B4915112345678&From=%2B15005550006&Body=Hello%2C+World%21")));
    }

    @Test
    void send_ProviderReturns4xx_PropagatesProviderErrorBody() {
        wireMock.stubFor(post(urlEqualTo("/Accounts/" + ACCOUNT_SID + "/Messages.json"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}")));

        SmsResult result = gateway.send(new SmsMessage("+4915112345678", "Hi", UUID.randomUUID()));

        assertTrue(!result.isSuccess());
        assertEquals("400", result.errorCode());
        assertEquals("TWILIO", result.provider());
    }
}
