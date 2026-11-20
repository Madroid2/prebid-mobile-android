/*
 *    Copyright 2018-2021 Prebid.org, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.prebid.mobile.rendering.mraid.methods.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.prebid.mobile.PrebidMobile;
import org.prebid.mobile.test.utils.WhiteBox;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
@LooperMode(LEGACY)
public class UrlResolutionTaskTest {

    /** Short enough to keep the stalled-host cases fast. */
    private static final long TEST_BUDGET_MILLIS = 600;

    private MockWebServer server;
    private UrlResolutionTask task;
    private int originalTimeoutMillis;
    private boolean originalTimeoutModified;

    private final UrlResolutionTask.UrlResolutionListener noOpListener =
            new UrlResolutionTask.UrlResolutionListener() {
                @Override
                public void onSuccess(@NonNull String resolvedUrl) {
                }

                @Override
                public void onFailure(@NonNull String message, @Nullable Throwable throwable) {
                }
            };

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        task = new UrlResolutionTask(noOpListener, TEST_BUDGET_MILLIS, System::nanoTime);
        originalTimeoutMillis = PrebidMobile.getTimeoutMillis();
        originalTimeoutModified = PrebidMobile.getTimeoutModified();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        // Robolectric does not reset app statics between test classes sharing a sandbox.
        WhiteBox.setStaticVariableTo(PrebidMobile.class, "timeoutMillis", originalTimeoutMillis);
        WhiteBox.setStaticVariableTo(PrebidMobile.class, "isTimeoutModified", originalTimeoutModified);
    }

    @Test
    public void whenRedirectChainCompletes_resolvesToFinalUrl() {
        String finalUrl = server.url("/final").toString();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("location", finalUrl));
        server.enqueue(new MockResponse().setResponseCode(200));

        assertEquals(finalUrl, task.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void defaultTask_resolvesTheChainWithoutAConfiguredTimeout() {
        // The production constructor, with setTimeoutMillis never called.
        UrlResolutionTask defaultTask = new UrlResolutionTask(noOpListener);
        String finalUrl = server.url("/final").toString();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("location", finalUrl));
        server.enqueue(new MockResponse().setResponseCode(200));

        assertEquals(finalUrl, defaultTask.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void bidRequestTimeout_doesNotShrinkTheClickBudget() {
        // Publishers lower the bid-request timeout to cut auction latency. Before
        // prebid#1061 the click chain was given that timeout too, so a value this small
        // stopped every chain at its first hop.
        PrebidMobile.setTimeoutMillis(1);
        UrlResolutionTask defaultTask = new UrlResolutionTask(noOpListener);
        String finalUrl = server.url("/final").toString();
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("location", finalUrl)
                .setHeadersDelay(50, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setResponseCode(200));

        assertEquals(finalUrl, defaultTask.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void whenRedirectHostStalls_returnsLastKnownUrlInsteadOfDroppingClick() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        String clickUrl = server.url("/click").toString();

        assertEquals(clickUrl, task.doInBackground(clickUrl));
    }

    @Test
    public void whenLaterHopStalls_returnsTheHopAlreadyResolved() {
        String secondHop = server.url("/second").toString();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("location", secondHop));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertEquals(secondHop, task.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void whenBudgetRunsOutBetweenHops_returnsTheTargetNotYetRequested() {
        String secondHop = server.url("/second").toString();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("location", secondHop));
        UrlResolutionTask expiring = new UrlResolutionTask(noOpListener, TEST_BUDGET_MILLIS, expiresAfterFirstHop());

        assertEquals(secondHop, expiring.doInBackground(server.url("/click").toString()));
        assertEquals("The second hop must not be requested", 1, server.getRequestCount());
    }

    @Test
    public void whenBudgetRunsOutOnAMalformedTarget_staysCancelled() {
        // The expiry path hands the target on without requesting it, so it has to be
        // validated there too rather than only on the next hop's new URL().
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("location", "http://example.com:abc/landing"));
        UrlResolutionTask expiring = new UrlResolutionTask(noOpListener, TEST_BUDGET_MILLIS, expiresAfterFirstHop());

        assertNull(expiring.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void whenFirstHopRefusesTheConnection_staysCancelled() throws IOException {
        // Nothing has counted the click yet and the browser would be refused too.
        assertNull(task.doInBackground("http://127.0.0.1:" + closedPort() + "/click"));
    }

    @Test
    public void whenALaterHopRefusesTheConnection_opensItAnyway() throws IOException {
        // The click tracker already answered, so the ad server has counted the click.
        // Cancelling would only leave a tap that does nothing.
        String landing = "http://127.0.0.1:" + closedPort() + "/landing";
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("location", landing));

        assertEquals(landing, task.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void whenRedirectTargetIsMalformed_staysCancelled() {
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("location", "http://example.com:abc/landing"));

        assertNull(task.doInBackground(server.url("/click").toString()));
    }

    @Test
    public void isConnectionRefused_onlyForARealRefusal() {
        // JVM wording, and Android libcore wording which carries the errno.
        assertTrue(UrlResolutionTask.isConnectionRefused(new ConnectException("Connection refused")));
        assertTrue(UrlResolutionTask.isConnectionRefused(new ConnectException(
                "failed to connect to /10.0.0.1 (port 80) after 2000ms: isConnected failed: ECONNREFUSED (Connection refused)")));
        ConnectException wrapped = new ConnectException("failed to connect");
        wrapped.initCause(new SocketException("ECONNREFUSED (Connection refused)"));
        assertTrue(UrlResolutionTask.isConnectionRefused(wrapped));

        // Android also reports a momentarily missing network as ConnectException, during a
        // Wi-Fi/cellular handover or on a dead IPv6 route. That must fall back, not cancel.
        assertFalse(UrlResolutionTask.isConnectionRefused(new ConnectException(
                "failed to connect to /10.0.0.1 (port 80) after 2000ms: connect failed: ENETUNREACH (Network is unreachable)")));
        assertFalse(UrlResolutionTask.isConnectionRefused(new ConnectException(null)));
    }

    @Test
    public void whenUrlIsNotHttp_returnsDeepLinkUnchanged() {
        String deepLink = "myapp://product/1";

        assertEquals(deepLink, task.doInBackground(deepLink));
    }

    @Test
    public void whenNoUrlSupplied_returnsNull() {
        assertNull(task.doInBackground());
    }

    /**
     * A clock that reads zero for the deadline and the first hop's budget check, then
     * reads past the deadline, so the budget runs out after exactly one hop.
     */
    private static UrlResolutionTask.Clock expiresAfterFirstHop() {
        return new UrlResolutionTask.Clock() {
            private int reads;

            @Override
            public long nanoTime() {
                return reads++ < 2 ? 0L : TimeUnit.MILLISECONDS.toNanos(TEST_BUDGET_MILLIS + 1);
            }
        };
    }

    /**
     * A port nothing is listening on, so connecting to it is refused. Racy in principle:
     * another process could bind the port between the probe closing and the connect.
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
