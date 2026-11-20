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

import android.os.AsyncTask;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import org.prebid.mobile.LogUtil;
import org.prebid.mobile.PrebidMobile;
import org.prebid.mobile.rendering.networking.BaseNetworkTask;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/**
 * Follows a click URL's redirect chain so the browser is handed the landing page.
 * <p>
 * The chain shares one budget, {@link #CLICK_RESOLUTION_BUDGET_MILLIS}, independent of
 * {@link PrebidMobile#setTimeoutMillis(int)}: that setting is the bid-request timeout,
 * which publishers lower to cut auction latency, and a click should not get less time
 * because of it. The budget is approximate rather than a hard limit. DNS lookup is not
 * bounded by the connect timeout, and the read timeout applies per read, so a slow
 * resolver or a server trickling headers can overrun it.
 * <p>
 * When a hop stalls, the URL of that hop is handed to the browser. Its request was
 * already sent, so the browser requests it a second time and the host can see two
 * requests for one tap. That is accepted: the alternative is dropping the click, and
 * the budget makes a stall rare.
 */
@VisibleForTesting
public class UrlResolutionTask extends AsyncTask<String, Void, String> {
    private static final String TAG = UrlResolutionTask.class.getSimpleName();

    /** Time allowed for the whole redirect chain. */
    static final long CLICK_RESOLUTION_BUDGET_MILLIS = 5_000;

    /** Upper bounds for a single hop, further limited by what is left of the budget. */
    private static final int HOP_CONNECT_TIMEOUT_MILLIS = BaseNetworkTask.TIMEOUT_DEFAULT;
    private static final int HOP_READ_TIMEOUT_MILLIS = BaseNetworkTask.SOCKET_TIMEOUT;

    /** Monotonic time source, replaceable in tests. */
    interface Clock {
        long nanoTime();
    }

    @NonNull private final UrlResolutionListener listener;
    private final long budgetMillis;
    @NonNull private final Clock clock;

    public UrlResolutionTask(@NonNull UrlResolutionListener listener) {
        this(listener, CLICK_RESOLUTION_BUDGET_MILLIS, System::nanoTime);
    }

    @VisibleForTesting
    UrlResolutionTask(@NonNull UrlResolutionListener listener, long budgetMillis, @NonNull Clock clock) {
        this.listener = listener;
        this.budgetMillis = Math.max(1L, budgetMillis);
        this.clock = clock;
    }

    @Nullable
    @Override
    protected String doInBackground(@Nullable String... urls) {
        if (urls == null || urls.length == 0) {
            return null;
        }

        final long deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        String previousUrl = null;
        // Hops that answered. Once the first one has, the ad server has counted the click.
        int answeredHops = 0;
        try {
            String locationUrl = urls[0];

            while (locationUrl != null && answeredHops < GetOriginalUrlTask.MAX_REDIRECTS) {
                // if location url is not http(s), assume it's an Android deep link
                // this scheme will fail URL validation so we have to check early
                if (!locationUrl.startsWith(PrebidMobile.SCHEME_HTTP)) {
                    return locationUrl;
                }

                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - clock.nanoTime());
                if (remainingMillis <= 0) {
                    LogUtil.debug(TAG, "Url resolution budget spent; opening " + locationUrl);
                    return isValidUrl(locationUrl) ? locationUrl : null;
                }

                previousUrl = locationUrl;
                locationUrl = getRedirectLocation(locationUrl, remainingMillis);
                answeredHops++;
            }
        }
        catch (MalformedURLException e) {
            // A target URI accepts but URL rejects, such as a non-numeric port.
            LogUtil.error(TAG, "Malformed redirect target: " + previousUrl);
            return null;
        }
        catch (UnknownHostException | ConnectException e) {
            if (e instanceof ConnectException && !isConnectionRefused(e)) {
                // ENETUNREACH and similar: transient network state, let the browser retry.
                LogUtil.debug(TAG, "Network unavailable at " + previousUrl + ": " + e.getMessage());
                return previousUrl;
            }
            if (answeredHops > 0) {
                // The click was already counted upstream; give the user the browser's answer.
                LogUtil.debug(TAG, "Unreachable after a counted click: " + previousUrl);
                return previousUrl;
            }
            LogUtil.debug(TAG, "Destination unreachable at " + previousUrl + ": " + e.getMessage());
            return null;
        }
        catch (IOException e) {
            // Stall, reset or a TLS failure stricter than a browser's; hand over this hop.
            LogUtil.debug(TAG, "Url resolution stopped at " + previousUrl + ": " + e.getMessage());
            return previousUrl;
        }
        catch (URISyntaxException | NullPointerException e) {
            return null;
        }

        return previousUrl;
    }

    /**
     * Whether a connect failure is a definite refusal (ECONNREFUSED) rather than a
     * network that is momentarily unavailable. Android reports both as
     * {@link ConnectException}, distinguishable only by the errno in the message.
     */
    @VisibleForTesting
    static boolean isConnectionRefused(@NonNull Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("ECONNREFUSED") || message.contains("Connection refused"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static boolean isValidUrl(@NonNull String url) {
        try {
            new URL(url);
            return true;
        }
        catch (MalformedURLException e) {
            return false;
        }
    }

    /** A per-hop timeout no larger than its cap or what is left of the budget. */
    private static int boundedTimeout(final int hopCapMillis, final long remainingMillis) {
        return (int) Math.max(1L, Math.min(hopCapMillis, remainingMillis));
    }

    @Nullable
    private String getRedirectLocation(@NonNull final String urlString, final long remainingMillis)
    throws IOException, URISyntaxException {
        final URL url = new URL(urlString);

        // Split the remainder across connect and read, so one hop cannot use twice it.
        final int connectMillis = boundedTimeout(HOP_CONNECT_TIMEOUT_MILLIS, remainingMillis / 2);
        final int readMillis = boundedTimeout(HOP_READ_TIMEOUT_MILLIS, remainingMillis - connectMillis);

        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = (HttpURLConnection) url.openConnection();
            httpUrlConnection.setInstanceFollowRedirects(false);
            httpUrlConnection.setConnectTimeout(connectMillis);
            httpUrlConnection.setReadTimeout(readMillis);

            return resolveRedirectLocation(urlString, httpUrlConnection);
        }
        finally {
            if (httpUrlConnection != null) {
                // Only the headers are read; draining a body would cost another read timeout.
                httpUrlConnection.disconnect();
            }
        }
    }

    @VisibleForTesting
    @Nullable
    static String resolveRedirectLocation(@NonNull final String baseUrl,
                                          @NonNull final HttpURLConnection httpUrlConnection)
    throws IOException, URISyntaxException {
        final URI baseUri = new URI(baseUrl);
        final int responseCode = httpUrlConnection.getResponseCode();
        final String redirectUrl = httpUrlConnection.getHeaderField("location");
        String result = null;

        if (responseCode >= 300 && responseCode < 400) {
            try {
                // If redirectUrl is a relative path, then resolve() will correctly complete the path;
                // otherwise, resolve() will return the redirectUrl
                result = baseUri.resolve(redirectUrl).toString();
            }
            catch (IllegalArgumentException e) {
                // Ensure the request is cancelled instead of resolving an intermediary URL
                LogUtil.error(TAG, "Invalid URL redirection. baseUrl=" + baseUrl + "\n redirectUrl=" + redirectUrl);
                throw new URISyntaxException(redirectUrl, "Unable to parse invalid URL");
            }
            catch (NullPointerException e) {
                LogUtil.error(TAG, "Invalid URL redirection. baseUrl=" + baseUrl + "\n redirectUrl=" + redirectUrl);
                throw e;
            }
        }

        return result;
    }

    @Override
    protected void onPostExecute(@Nullable final String resolvedUrl) {
        super.onPostExecute(resolvedUrl);

        if (isCancelled() || resolvedUrl == null) {
            onCancelled();
        }
        else {
            listener.onSuccess(resolvedUrl);
        }
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();

        listener.onFailure("Task for resolving url was cancelled", null);
    }

    public interface UrlResolutionListener {
        void onSuccess(@NonNull final String resolvedUrl);

        void onFailure(@NonNull final String message, @Nullable final Throwable throwable);
    }
}