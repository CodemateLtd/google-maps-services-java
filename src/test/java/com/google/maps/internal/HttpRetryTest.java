/*
 * Copyright 2026 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.maps.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPMethod;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.gson.FieldNamingPolicy;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.GeolocationApi;
import com.google.maps.PendingResult;
import com.google.maps.errors.OverDailyLimitException;
import com.google.maps.errors.OverQueryLimitException;
import com.google.maps.errors.RequestDeniedException;
import com.google.maps.metrics.RequestMetrics;
import com.google.maps.model.GeocodingResponse;
import com.google.maps.model.GeolocationResult;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Public SDK requests against real OkHttp or controlled, actual URL Fetch responses. */
@RunWith(Parameterized.class)
public class HttpRetryTest {
  private static final String SUCCESS =
      "{\"status\":\"OK\",\"results\":[{\"formatted_address\":\"Retry succeeded\"}]}";
  private static final String QUOTA =
      "{\"status\":\"OVER_QUERY_LIMIT\",\"error_message\":\"slow down\"}";
  private static final String GEOLOCATION_SUCCESS =
      "{\"location\":{\"lat\":51.5,\"lng\":-0.12},\"accuracy\":17.5}";
  private static final String GEOLOCATION_QUOTA =
      "{\"error\":{\"code\":429,\"message\":\"slow down\","
          + "\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}";
  private static final long START = Instant.parse("1994-11-06T08:49:37Z").toEpochMilli();

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> transports() {
    return Arrays.asList(new Object[][] {{"OkHttp", false}, {"GAE", true}});
  }

  private final boolean gae;
  private final Queue<HTTPResponse> gaeResponses = new ArrayDeque<>();
  private final List<Long> dispatched = Collections.synchronizedList(new ArrayList<Long>());
  private final TestTiming timing = new TestTiming();
  private MockWebServer server;
  private URLFetchService fetch;
  private OkHttpClient client;
  private GeoApiContext.Builder builder;
  private GeoApiContext context;
  private volatile boolean transportFailure;

  public HttpRetryTest(String name, boolean gae) {
    this.gae = gae;
  }

  @Before
  public void setUp() throws Exception {
    if (gae) {
      fetch = mock(URLFetchService.class);
      when(fetch.fetchAsync(any(HTTPRequest.class)))
          .thenAnswer(
              invocation -> {
                dispatched.add(timing.now);
                if (transportFailure) {
                  CompletableFuture<HTTPResponse> failed = new CompletableFuture<>();
                  failed.completeExceptionally(new IOException("connection failed"));
                  return failed;
                }
                return CompletableFuture.completedFuture(gaeResponses.remove());
              });
    } else {
      server = new MockWebServer();
      server.start();
      client =
          new OkHttpClient.Builder()
              .retryOnConnectionFailure(false)
              .addNetworkInterceptor(
                  chain -> {
                    dispatched.add(timing.now);
                    if (transportFailure) {
                      throw new IOException("connection failed");
                    }
                    return chain.proceed(chain.request());
                  })
              .build();
    }
    GeoApiContext.RequestHandler.Builder handlerBuilder =
        mock(GeoApiContext.RequestHandler.Builder.class);
    when(handlerBuilder.build()).thenReturn(new TestHandler());
    builder = new GeoApiContext.Builder(handlerBuilder).apiKey("AIza-test").maxRetries(3);
  }

  @After
  public void tearDown() throws Exception {
    if (context != null) {
      context.close();
    } else if (client != null) {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  public void unparseable429BodiesRetryByStatus() throws Exception {
    for (String body : new String[] {"", "{", "slow down", "null", "[]", SUCCESS}) {
      enqueue(429, body, null);
      enqueue(200, SUCCESS, null);
      assertSuccess();
    }
    assertEquals(12, dispatched.size());
    assertEquals(Arrays.asList(500L, 500L, 500L, 500L, 500L, 500L), timing.waits);
    assertPaced();
  }

  @Test
  public void recognizableClassicAndNestedQuotaErrorsRetry() throws Exception {
    for (String body :
        new String[] {
          QUOTA, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"slow down\"}}"
        }) {
      enqueue(429, body, null);
      enqueue(200, SUCCESS, null);
      assertSuccess();
    }
    assertEquals(4, dispatched.size());
    assertEquals(Arrays.asList(500L, 500L), timing.waits);
  }

  @Test
  public void explicitQuotaDenyIsNotBypassedByHttp429() throws Exception {
    builder.setIfExceptionIsAllowedToRetry(OverQueryLimitException.class, false);
    enqueue(429, QUOTA, "2");
    enqueue(200, SUCCESS, null);
    assertFailure(OverQueryLimitException.class);
    assertNoRetry();
  }

  @Test
  public void explicitExceptionAllowStillAppliesToHttp429() throws Exception {
    builder.setIfExceptionIsAllowedToRetry(RequestDeniedException.class, true);
    enqueue(429, "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"denied\"}", null);
    enqueue(200, SUCCESS, null);
    assertSuccess();
    assertEquals(2, dispatched.size());
  }

  @Test
  public void permanentDailyQuotaErrorsAreNotRetried() throws Exception {
    String dailyMessage =
        "You have exceeded your daily request quota for this API. If you did not set a custom daily"
            + " request quota, verify your project has an active billing account:"
            + " http://g.co/dev/maps-no-account";
    for (String body :
        new String[] {
          "{\"status\":\"OVER_QUERY_LIMIT\",\"error_message\":\"" + dailyMessage + "\"}",
          "{\"error\":{\"status\":\"dailyLimitExceeded\",\"message\":\"daily quota\"}}"
        }) {
      enqueue(429, body, null);
      assertFailure(OverDailyLimitException.class);
    }
    assertEquals(2, dispatched.size());
    assertTrue(timing.waits.isEmpty());
  }

  @Test
  public void geolocationReasonQuotaRetriesAndPreservesResult() throws Exception {
    enqueue(429, GEOLOCATION_QUOTA, null);
    enqueue(200, GEOLOCATION_SUCCESS, null);
    GeolocationResult result = geolocate().await();
    assertEquals(51.5, result.location.lat, 0);
    assertEquals(-0.12, result.location.lng, 0);
    assertEquals(17.5, result.accuracy, 0);
    assertEquals(2, dispatched.size());
    assertPaced();
  }

  @Test
  public void geolocationReasonQuotaDenyIsPreserved() throws Exception {
    builder.setIfExceptionIsAllowedToRetry(OverQueryLimitException.class, false);
    enqueue(429, GEOLOCATION_QUOTA, "2");
    enqueue(200, GEOLOCATION_SUCCESS, null);
    assertEquals(
        "slow down", assertFailure(geolocate(), OverQueryLimitException.class).getMessage());
    assertNoRetry();
  }

  @Test
  public void geolocationReasonDailyQuotaIsNotRetried() throws Exception {
    enqueue(
        429,
        "{\"error\":{\"code\":429,\"message\":\"daily quota\","
            + "\"errors\":[{\"reason\":\"dailyLimitExceeded\"}]}}",
        null);
    enqueue(200, GEOLOCATION_SUCCESS, null);
    assertEquals(
        "daily quota", assertFailure(geolocate(), OverDailyLimitException.class).getMessage());
    assertNoRetry();
  }

  @Test
  public void geolocationLastReasonControlsQuotaPolicy() throws Exception {
    enqueue(
        429,
        "{\"error\":{\"code\":429,\"message\":\"daily quota\","
            + "\"errors\":[{\"reason\":\"userRateLimitExceeded\"},"
            + "{\"reason\":\"dailyLimitExceeded\"}]}}",
        null);
    enqueue(200, GEOLOCATION_SUCCESS, null);
    assertFailure(geolocate(), OverDailyLimitException.class);
    assertNoRetry();
  }

  @Test
  public void disabledRetriesReturnStatusBearingFailure() throws Exception {
    builder.disableRetries();
    enqueue(429, "", null);
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertNoRetry();
  }

  @Test
  public void maxRetriesZeroDisablesStatusRetries() throws Exception {
    builder.maxRetries(0);
    for (String body : new String[] {"", "{", "not JSON", "null", "[]", SUCCESS}) {
      enqueue(429, body, null);
      assertStatusFailure(429);
    }
    assertEquals(6, dispatched.size());
    assertTrue(timing.waits.isEmpty());
  }

  @Test
  public void retryCountBoundsRequests() throws Exception {
    builder.maxRetries(1);
    enqueue(429, "", null);
    enqueue(429, "", null);
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertEquals(2, dispatched.size());
    assertEquals(Collections.singletonList(500L), timing.waits);
  }

  @Test
  public void zeroTimeoutPrevents429Retries() throws Exception {
    builder.maxRetries(null).retryTimeout(0, TimeUnit.MILLISECONDS);
    enqueue(429, "", "70");
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertNoRetry();
  }

  @Test
  public void zeroTimeoutPreservesExistingStatusAndApiRetryControls() throws Exception {
    builder.maxRetries(null).retryTimeout(0, TimeUnit.MILLISECONDS);
    enqueue(503, QUOTA, null);
    enqueue(200, QUOTA, null);
    enqueue(200, SUCCESS, null);
    assertFailure(OverQueryLimitException.class);
    assertFailure(OverQueryLimitException.class);
    assertEquals(2, dispatched.size());
    assertTrue(timing.waits.isEmpty());
  }

  @Test
  public void nextBackoffMustFitRemainingBudget() throws Exception {
    builder.retryTimeout(499, TimeUnit.MILLISECONDS);
    enqueue(429, "", null);
    assertStatusFailure(429);
    assertNoRetry();
  }

  @Test
  public void exactDelayBudgetCanRetry() throws Exception {
    builder.retryTimeout(500, TimeUnit.MILLISECONDS);
    enqueue(429, "", null);
    enqueue(200, SUCCESS, null);
    assertSuccess();
    assertEquals(Collections.singletonList(500L), timing.waits);
  }

  @Test
  public void cumulativeBudgetIsCheckedBeforeNextWait() throws Exception {
    builder.retryTimeout(1000, TimeUnit.MILLISECONDS);
    enqueue(429, "", null);
    enqueue(429, "", null);
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertEquals(2, dispatched.size());
    assertEquals(Collections.singletonList(500L), timing.waits);
  }

  @Test
  public void retryAfterSecondsPacesDispatch() throws Exception {
    enqueue(429, "", "2");
    enqueue(200, SUCCESS, null);
    if (gae) {
      assertSuccess();
    } else {
      assertCallbackSuccess();
    }
    assertEquals(2, dispatched.size());
    assertEquals(Collections.singletonList(2000L), timing.waits);
    assertPaced();
  }

  @Test
  public void retryAfterAcceptsStandardAndLegacyHttpDates() throws Exception {
    String[] dates = {
      "Sun, 06 Nov 1994 08:49:40 GMT", "Sunday, 06-Nov-94 08:49:43 GMT", "Sun Nov  6 08:49:46 1994"
    };
    for (String date : dates) {
      enqueue(429, QUOTA, date);
      enqueue(200, SUCCESS, null);
      assertSuccess();
    }
    assertEquals(Arrays.asList(3000L, 3000L, 3000L), timing.waits);
    assertPaced();
  }

  @Test
  public void invalidRetryAfterUsesJitteredBackoff() throws Exception {
    for (String header : new String[] {"later", "-1", "1.5", "Sun, 99 Nov 1994 08:49:40 GMT"}) {
      enqueue(429, "", header);
      enqueue(200, SUCCESS, null);
      assertSuccess();
    }
    assertEquals(Arrays.asList(500L, 500L, 500L, 500L), timing.waits);
  }

  @Test
  public void zeroRetryAfterCannotBypassDelayBudget() throws Exception {
    assertRepeated429sStopWithinBudget("0");
  }

  @Test
  public void pastRetryAfterCannotBypassDelayBudget() throws Exception {
    assertRepeated429sStopWithinBudget("Sun, 06 Nov 1994 08:49:36 GMT");
  }

  @Test
  public void retryAfterBeyondBudgetTerminatesWithoutSleeping() throws Exception {
    builder.retryTimeout(1000, TimeUnit.MILLISECONDS);
    enqueue(429, "", "2");
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertNoRetry();
  }

  @Test
  public void retryAfterDateBeyondBudgetDoesNotDispatchEarly() throws Exception {
    builder.retryTimeout(1000, TimeUnit.MILLISECONDS);
    enqueue(429, "", "Sun, 06 Nov 1994 08:49:40 GMT");
    enqueue(200, SUCCESS, null);
    assertStatusFailure(429);
    assertNoRetry();
  }

  @Test
  public void retryAfterOverflowNeverBecomesShortDelay() throws Exception {
    for (String value :
        new String[] {"9223372036854776", "9223372036854775807", "99999999999999999999999999"}) {
      enqueue(429, "", value);
      assertStatusFailure(429);
    }
    assertEquals(3, dispatched.size());
    assertTrue(timing.waits.isEmpty());
  }

  @Test
  public void structured429BudgetExhaustionPreservesApiException() throws Exception {
    builder.retryTimeout(1000, TimeUnit.MILLISECONDS);
    enqueue(429, QUOTA, "2");
    assertFailure(OverQueryLimitException.class);
    assertNoRetry();
  }

  @Test
  public void existingTransientStatusesStillRetryWithPacing() throws Exception {
    for (int status : new int[] {500, 503, 504}) {
      enqueue(status, "temporary failure", "1");
      enqueue(200, SUCCESS, null);
      assertSuccess();
    }
    assertEquals(6, dispatched.size());
    assertEquals(Arrays.asList(1000L, 1000L, 1000L), timing.waits);
    assertPaced();
  }

  @Test
  public void otherHttpFailuresAreNotRetried() throws Exception {
    enqueue(502, "bad gateway", "1");
    assertStatusFailure(502);
    assertNoRetry();
  }

  @Test
  public void transportExceptionsAreNotRetried() throws Exception {
    transportFailure = true;
    assertEquals("connection failed", assertFailure(IOException.class).getMessage());
    assertNoRetry();
  }

  @Test
  public void apiErrorsOutside429RetainExceptionPolicyAndPacing() throws Exception {
    enqueue(200, QUOTA, "1");
    enqueue(200, SUCCESS, null);
    assertSuccess();
    assertEquals(Collections.singletonList(1000L), timing.waits);
    assertPaced();
  }

  @Test
  public void interruptedRetryAfterWaitNeverDispatchesEarly() throws Exception {
    timing.interrupt = true;
    enqueue(429, "", "2");
    enqueue(200, SUCCESS, null);
    assertFailure(InterruptedException.class);
    assertEquals(1, dispatched.size());
    assertEquals(Collections.singletonList(2000L), timing.waits);
  }

  @Test
  public void retryWaitRemainsInsideExistingNetworkInterval() throws Exception {
    MeasuredNetworkMetrics measurements = new MeasuredNetworkMetrics();
    builder.requestMetricsReporter(name -> measurements);
    enqueue(429, "", "2");
    enqueue(200, SUCCESS, null);
    assertSuccess();
    assertEquals(2, dispatched.size());
    assertEquals(2000L, measurements.reported);
  }

  @Test
  public void cancelDuringRetryWaitDoesNotSendAnotherRequest() throws Exception {
    timing.waitStarted = new CountDownLatch(1);
    timing.waitReleased = new CountDownLatch(1);
    enqueue(429, "", "2");
    enqueue(200, SUCCESS, null);
    PendingResult<GeocodingResponse> pending = GeocodingApi.geocode(context(), "somewhere");
    CompletableFuture<GeocodingResponse> completion = new CompletableFuture<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                completion.complete(pending.await());
              } catch (Throwable failure) {
                completion.completeExceptionally(failure);
              }
            },
            "retry-cancellation-test");
    worker.start();
    try {
      assertTrue(timing.waitStarted.await(5, TimeUnit.SECONDS));
      pending.cancel();
      timing.waitReleased.countDown();
      try {
        completion.get(5, TimeUnit.SECONDS);
        fail("Cancelled retry returned a success");
      } catch (CancellationException expected) {
        // URL Fetch reports cancellation through its Future.
      } catch (ExecutionException expected) {
        assertTrue(expected.getCause() instanceof IOException);
      }
      assertEquals(1, dispatched.size());
    } finally {
      timing.waitReleased.countDown();
      worker.interrupt();
      worker.join(5000);
    }
  }

  private void assertCallbackSuccess() throws Exception {
    CountDownLatch complete = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    GeocodingApi.geocode(context(), "somewhere")
        .setCallback(
            new PendingResult.Callback<GeocodingResponse>() {
              @Override
              public void onResult(GeocodingResponse response) {
                if (response == null
                    || response.results == null
                    || response.results.length != 1
                    || !"Retry succeeded".equals(response.results[0].formattedAddress)) {
                  failure.set(new AssertionError("Unexpected results"));
                }
                complete.countDown();
              }

              @Override
              public void onFailure(Throwable error) {
                failure.set(error);
                complete.countDown();
              }
            });
    assertTrue(complete.await(5, TimeUnit.SECONDS));
    assertEquals(null, failure.get());
  }

  private void assertRepeated429sStopWithinBudget(String retryAfter) throws Exception {
    builder.maxRetries(null).retryTimeout(1000, TimeUnit.MILLISECONDS);
    enqueue(429, "", retryAfter);
    enqueue(429, "", retryAfter);
    enqueue(429, "", retryAfter);
    // A terminal sentinel makes an incorrectly unbounded zero-wait loop fail without hanging.
    enqueue(400, "Retry budget was bypassed", null);
    assertStatusFailure(429);
    assertEquals(2, dispatched.size());
    assertTrue(timing.now > START);
    assertTrue(timing.now - START <= 1000);
  }

  private GeoApiContext context() {
    if (context == null) {
      context = builder.build();
    }
    return context;
  }

  private void assertSuccess() throws Exception {
    GeocodingResponse response = GeocodingApi.geocode(context(), "somewhere").await();
    assertEquals(1, response.results.length);
    assertEquals("Retry succeeded", response.results[0].formattedAddress);
  }

  private PendingResult<GeolocationResult> geolocate() {
    return GeolocationApi.newRequest(context()).ConsiderIp(true).CreatePayload();
  }

  private Exception assertFailure(Class<? extends Exception> expected) throws Exception {
    return assertFailure(GeocodingApi.geocode(context(), "somewhere"), expected);
  }

  private Exception assertFailure(PendingResult<?> request, Class<? extends Exception> expected)
      throws Exception {
    try {
      request.await();
      fail("Expected " + expected.getSimpleName());
      return null;
    } catch (Exception error) {
      assertTrue("Unexpected failure: " + error, expected.isInstance(error));
      return error;
    }
  }

  private void assertStatusFailure(int status) throws Exception {
    assertTrue(assertFailure(IOException.class).getMessage().contains(Integer.toString(status)));
  }

  private void assertNoRetry() {
    assertEquals(1, dispatched.size());
    assertTrue(timing.waits.isEmpty());
  }

  private void assertPaced() {
    assertEquals(timing.now, (long) dispatched.get(dispatched.size() - 1));
    for (int i = 1; i < dispatched.size(); i += 2) {
      assertEquals((long) timing.waits.get(i / 2), dispatched.get(i) - dispatched.get(i - 1));
    }
  }

  private void enqueue(int status, String body, String retryAfter) throws Exception {
    if (gae) {
      List<HTTPHeader> headers = new ArrayList<>();
      headers.add(new HTTPHeader("Content-Type", "application/json"));
      if (retryAfter != null) {
        headers.add(new HTTPHeader("rEtRy-AfTeR", retryAfter));
      }
      gaeResponses.add(
          new HTTPResponse(
              status, body.getBytes(UTF_8), new URL("http://localhost/maps"), headers));
    } else {
      MockResponse response =
          new MockResponse()
              .setResponseCode(status)
              .setHeader("Content-Type", "application/json")
              .setBody(body);
      if (retryAfter != null) {
        response.setHeader("rEtRy-AfTeR", retryAfter);
      }
      server.enqueue(response);
    }
  }

  private final class MeasuredNetworkMetrics implements RequestMetrics {
    private long started;
    private long accumulated;
    private long reported = -1;

    @Override
    public void startNetwork() {
      started = timing.now;
    }

    @Override
    public void endNetwork() {
      accumulated += timing.now - started;
    }

    @Override
    public void endRequest(Exception failure, int status, long retries) {
      if (reported < 0) {
        reported = accumulated;
      }
    }
  }

  private final class TestTiming extends RetryDelay.Timing {
    private volatile long now = START;
    private final List<Long> waits = new ArrayList<>();
    private boolean interrupt;
    private CountDownLatch waitStarted;
    private CountDownLatch waitReleased;

    @Override
    long currentTimeMillis() {
      return now;
    }

    @Override
    double random() {
      return 0.5;
    }

    @Override
    void sleep(long millis) throws InterruptedException {
      waits.add(millis);
      if (interrupt) {
        throw new InterruptedException("controlled interruption");
      }
      if (waitStarted != null) {
        waitStarted.countDown();
        waitReleased.await();
      }
      now += millis;
    }
  }

  private final class TestHandler implements GeoApiContext.RequestHandler {
    @Override
    public <T, R extends ApiResponse<T>> PendingResult<T> handle(
        String hostName,
        String url,
        Map<String, String> headers,
        Class<R> clazz,
        FieldNamingPolicy naming,
        long timeout,
        Integer retries,
        ExceptionsAllowedToRetry exceptions,
        RequestMetrics metrics) {
      if (gae) {
        try {
          return new GaePendingResult<>(
              new HTTPRequest(new URL("http://localhost" + url)),
              fetch,
              clazz,
              naming,
              timeout,
              retries,
              exceptions,
              metrics,
              timing);
        } catch (java.net.MalformedURLException error) {
          throw new AssertionError(error);
        }
      }
      return new OkHttpPendingResult<>(
          new Request.Builder().url(server.url(url)).build(),
          client,
          clazz,
          naming,
          timeout,
          retries,
          exceptions,
          metrics,
          timing);
    }

    @Override
    public <T, R extends ApiResponse<T>> PendingResult<T> handlePost(
        String hostName,
        String url,
        String payload,
        Map<String, String> headers,
        Class<R> clazz,
        FieldNamingPolicy naming,
        long timeout,
        Integer retries,
        ExceptionsAllowedToRetry exceptions,
        RequestMetrics metrics) {
      if (gae) {
        try {
          HTTPRequest request = new HTTPRequest(new URL("http://localhost" + url), HTTPMethod.POST);
          request.setPayload(payload.getBytes(UTF_8));
          return new GaePendingResult<>(
              request, fetch, clazz, naming, timeout, retries, exceptions, metrics, timing);
        } catch (java.net.MalformedURLException error) {
          throw new AssertionError(error);
        }
      }
      Request request =
          new Request.Builder()
              .url(server.url(url))
              .post(RequestBody.create(payload, MediaType.get("application/json")))
              .build();
      return new OkHttpPendingResult<>(
          request, client, clazz, naming, timeout, retries, exceptions, metrics, timing);
    }

    @Override
    public void shutdown() {
      if (client != null) {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
      }
    }
  }
}
