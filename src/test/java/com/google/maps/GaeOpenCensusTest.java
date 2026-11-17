package com.google.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.google.maps.errors.RequestDeniedException;
import com.google.maps.errors.UnknownErrorException;
import com.google.maps.metrics.MetricsTestSupport;
import com.google.maps.metrics.OpenCensusMetrics;
import com.google.maps.model.GeocodingResponse;
import io.opencensus.stats.AggregationData.DistributionData;
import io.opencensus.stats.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.MockedStatic;

@Category(MediumTests.class)
public class GaeOpenCensusTest {
  @Test
  public void initialFailedFutureRecordsOriginalIOExceptionAndCompletedNetworkTime()
      throws Exception {
    runCase(false, null, new IOException("initial URLFetch failure"));
  }

  @Test
  public void failedFutureAfterHttp500RecordsHttpZeroAndBothNetworkIntervals() throws Exception {
    runCase(true, null, new IOException("URLFetch failure after HTTP 500"));
  }

  @Test
  public void successRecordsOneFinalObservation() throws Exception {
    runCase(false, "OK", null);
  }

  @Test
  public void http200ApiErrorRecordsItsExceptionClass() throws Exception {
    runCase(false, "REQUEST_DENIED", null);
  }

  @Test
  public void unexpectedFutureFailureReportsTheCallerVisibleApiError() throws Exception {
    runCase(false, null, new IllegalStateException("unexpected URLFetch failure"));
  }

  private static void runCase(boolean retry, String status, Exception originalFailure)
      throws Exception {
    AtomicLong clock = new AtomicLong(1000);
    Deque<Future<HTTPResponse>> responses = new ArrayDeque<>();
    if (retry) {
      responses.add(new ControlledFuture(clock, 10, response(500, "UNKNOWN_ERROR"), null));
    }
    responses.add(
        new ControlledFuture(
            clock,
            retry ? 20 : 10,
            originalFailure == null ? response(200, status) : null,
            originalFailure));
    URLFetchService client = mock(URLFetchService.class);
    when(client.fetchAsync(any(HTTPRequest.class)))
        .thenAnswer(invocation -> responses.removeFirst());

    try (MetricsTestSupport metrics = new MetricsTestSupport();
        MockedStatic<URLFetchServiceFactory> factory = mockStatic(URLFetchServiceFactory.class)) {
      factory.when(URLFetchServiceFactory::getURLFetchService).thenReturn(client);
      try (GeoApiContext context =
          new GeoApiContext.Builder(new GaeRequestHandler.Builder())
              .apiKey("AIza...")
              .requestMetricsReporter(metrics.reporter(clock::get))
              .maxRetries(retry ? 1 : 0)
              .retryTimeout(1, TimeUnit.MINUTES)
              .build()) {
        GeocodingResponse result = null;
        Exception failure = null;
        try {
          result = GeocodingApi.geocode(context, "Sydney").await();
        } catch (Exception e) {
          failure = e;
        }

        Class<? extends Exception> expectedError = null;
        int expectedHttpCode = 200;
        if (originalFailure != null) {
          assertNull(result);
          if (originalFailure instanceof IOException) {
            assertSame(originalFailure, failure);
            expectedError = IOException.class;
          } else {
            assertNotNull(failure);
            assertEquals(UnknownErrorException.class, failure.getClass());
            expectedError = UnknownErrorException.class;
          }
          expectedHttpCode = 0;
        } else if ("REQUEST_DENIED".equals(status)) {
          assertNull(result);
          assertNotNull(failure);
          assertEquals(RequestDeniedException.class, failure.getClass());
          assertEquals("metrics fixture denied", failure.getMessage());
          expectedError = RequestDeniedException.class;
        } else {
          assertNull(failure);
          assertNotNull(result);
          assertEquals(1, result.results.length);
          assertEquals("metrics fixture result", result.results[0].formattedAddress);
        }

        int attempts = retry ? 2 : 1;
        verify(client, times(attempts)).fetchAsync(any(HTTPRequest.class));
        OpenCensusTest.assertFinalMetrics(metrics, expectedHttpCode, expectedError, attempts - 1);
        // A missing or repeated endNetwork changes the actual collected sum, even if the final
        // request count and outcome happen to be correct. There is no wall-clock timing here.
        assertLatency(metrics, OpenCensusMetrics.Views.NETWORK_LATENCY, retry ? 30 : 10);
        assertLatency(metrics, OpenCensusMetrics.Views.REQUEST_LATENCY, retry ? 30 : 10);
      }
    }
  }

  private static void assertLatency(MetricsTestSupport metrics, View view, long expectedMillis) {
    DistributionData data =
        (DistributionData) metrics.snapshot(view).getAggregationMap().values().iterator().next();
    assertEquals(expectedMillis, data.getMean(), 0.0);
  }

  private static HTTPResponse response(int code, String status) {
    HTTPResponse response = mock(HTTPResponse.class);
    when(response.getResponseCode()).thenReturn(code);
    when(response.getHeaders()).thenReturn(Collections.emptyList());
    when(response.getContent())
        .thenReturn(OpenCensusTest.responseBody(status).getBytes(StandardCharsets.UTF_8));
    return response;
  }

  /** A real, in-process future that completes only when GaePendingResult awaits it. */
  private static final class ControlledFuture extends FutureTask<HTTPResponse> {
    ControlledFuture(AtomicLong clock, long elapsedMillis, HTTPResponse response, Exception error) {
      super(
          () -> {
            clock.addAndGet(elapsedMillis);
            if (error != null) {
              throw error;
            }
            return response;
          });
    }

    @Override
    public HTTPResponse get() throws InterruptedException, ExecutionException {
      run();
      return super.get();
    }
  }
}
