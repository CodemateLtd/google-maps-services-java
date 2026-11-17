package com.google.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.maps.errors.RequestDeniedException;
import com.google.maps.metrics.MetricsTestSupport;
import com.google.maps.metrics.OpenCensusMetrics;
import com.google.maps.model.GeocodingResponse;
import io.opencensus.stats.AggregationData;
import io.opencensus.stats.View;
import io.opencensus.stats.ViewData;
import io.opencensus.tags.TagValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@Category(MediumTests.class)
@RunWith(Parameterized.class)
public class OpenCensusTest {
  enum Outcome {
    SUCCESS(0, 200, null),
    API_ERROR(0, 200, RequestDeniedException.class),
    TRANSPORT_ERROR(0, 0, IOException.class),
    TWO_RETRIES_THEN_SUCCESS(2, 200, null),
    RETRY_THEN_API_ERROR(1, 200, RequestDeniedException.class),
    RETRY_THEN_TRANSPORT_ERROR(1, 0, IOException.class);

    final int retries;
    final int httpCode;
    final Class<? extends Exception> errorClass;

    Outcome(int retries, int httpCode, Class<? extends Exception> errorClass) {
      this.retries = retries;
      this.httpCode = httpCode;
      this.errorClass = errorClass;
    }
  }

  @Parameterized.Parameters(name = "{0}-{1}")
  public static Collection<Object[]> outcomes() {
    List<Object[]> cases = new ArrayList<>();
    for (String mode : Arrays.asList("await", "callback")) {
      for (Outcome outcome : Outcome.values()) {
        cases.add(new Object[] {mode, outcome});
      }
    }
    return cases;
  }

  private final String mode;
  private final Outcome outcome;

  public OpenCensusTest(String mode, Outcome outcome) {
    this.mode = mode;
    this.outcome = outcome;
  }

  @Test
  public void recordsOnlyTheFinalLogicalRequestOutcome() throws Exception {
    try (MetricsTestSupport metrics = new MetricsTestSupport();
        MockWebServer server = new MockWebServer()) {
      for (int i = 0; i < outcome.retries; i++) {
        server.enqueue(response(500, "UNKNOWN_ERROR"));
      }
      if (outcome.httpCode == 0) {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      } else {
        server.enqueue(response(200, outcome.errorClass == null ? "OK" : "REQUEST_DENIED"));
      }

      OkHttpRequestHandler.Builder handler = new OkHttpRequestHandler.Builder();
      // A failed connection must be one SDK attempt, not an invisible OkHttp retry.
      handler.okHttpClientBuilder().retryOnConnectionFailure(false);
      try (GeoApiContext context =
          new GeoApiContext.Builder(handler)
              .apiKey("AIza...")
              .requestMetricsReporter(metrics.reporter())
              .baseUrlOverride("http://127.0.0.1:" + server.getPort())
              .maxRetries(outcome.retries)
              .retryTimeout(1, TimeUnit.MINUTES)
              .build()) {
        PendingResult<GeocodingResponse> pending = GeocodingApi.geocode(context, "Sydney");
        GeocodingResponse result = null;
        Throwable failure = null;
        try {
          result = "callback".equals(mode) ? awaitCallback(pending) : pending.await();
        } catch (Exception e) {
          failure = e;
        }

        if (outcome.errorClass == null) {
          assertNull(failure);
          assertNotNull(result);
          assertEquals(1, result.results.length);
          assertEquals("metrics fixture result", result.results[0].formattedAddress);
        } else {
          assertNull(result);
          assertNotNull(failure);
          assertEquals(outcome.errorClass, failure.getClass());
          if (failure instanceof RequestDeniedException) {
            assertEquals("metrics fixture denied", failure.getMessage());
          }
        }
        assertEquals(outcome.retries + 1, server.getRequestCount());
        assertFinalMetrics(metrics, outcome.httpCode, outcome.errorClass, outcome.retries);
      }
    }
  }

  private static GeocodingResponse awaitCallback(PendingResult<GeocodingResponse> pending)
      throws Exception {
    CompletableFuture<GeocodingResponse> completion = new CompletableFuture<>();
    pending.setCallback(
        new PendingResult.Callback<GeocodingResponse>() {
          @Override
          public void onResult(GeocodingResponse result) {
            completion.complete(result);
          }

          @Override
          public void onFailure(Throwable e) {
            completion.completeExceptionally(e);
          }
        });
    try {
      return completion.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw new AssertionError(e.getCause());
    }
  }

  private static MockResponse response(int code, String status) {
    return new MockResponse().setResponseCode(code).setBody(responseBody(status));
  }

  static String responseBody(String status) {
    return "{\"results\":[{\"formatted_address\":\"metrics fixture result\"}],"
        + "\"status\":\""
        + status
        + "\",\"error_message\":\"metrics fixture denied\"}";
  }

  static void assertFinalMetrics(
      MetricsTestSupport metrics,
      int httpCode,
      Class<? extends Exception> errorClass,
      int retries) {
    List<TagValue> expectedTags =
        Arrays.asList(
            TagValue.create(errorClass == null ? "" : errorClass.getName()),
            TagValue.create(Integer.toString(httpCode)),
            TagValue.create("/maps/api/geocode/json"));
    View[] views = {
      OpenCensusMetrics.Views.REQUEST_COUNT,
      OpenCensusMetrics.Views.REQUEST_LATENCY,
      OpenCensusMetrics.Views.NETWORK_LATENCY,
      OpenCensusMetrics.Views.RETRY_COUNT
    };
    for (View view : views) {
      ViewData data = metrics.snapshot(view);
      assertNotNull(data);
      Map<List<TagValue>, AggregationData> observations = data.getAggregationMap();
      assertEquals(view.getName().asString(), 1, observations.size());
      AggregationData observation = observations.get(expectedTags);
      assertNotNull(view.getName().asString() + ": " + observations, observation);
      if (view.equals(OpenCensusMetrics.Views.REQUEST_COUNT)) {
        assertEquals(1, ((AggregationData.CountData) observation).getCount());
      } else {
        AggregationData.DistributionData distribution =
            (AggregationData.DistributionData) observation;
        assertEquals(view.getName().asString(), 1, distribution.getCount());
        long bucketTotal = 0;
        for (long count : distribution.getBucketCounts()) {
          bucketTotal += count;
        }
        assertEquals(view.getName().asString(), 1, bucketTotal);
        if (view.equals(OpenCensusMetrics.Views.RETRY_COUNT)) {
          assertEquals(retries, distribution.getMean(), 0.0);
        }
      }
    }
  }
}
