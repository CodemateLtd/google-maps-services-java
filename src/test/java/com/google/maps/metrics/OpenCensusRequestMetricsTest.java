package com.google.maps.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.maps.SmallTests;
import com.google.maps.errors.RequestDeniedException;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.AggregationData;
import io.opencensus.stats.AggregationData.DistributionData;
import io.opencensus.stats.View;
import io.opencensus.stats.ViewData;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class OpenCensusRequestMetricsTest {
  // OpenCensus drops the non-positive boundary: the first bucket includes zero.
  private static final List<Double> LATENCY_BOUNDARIES =
      Arrays.asList(
          20.0, 25.2, 31.7, 40.0, 50.4, 63.5, 80.0, 100.8, 127.0, 160.0, 201.6, 254.0, 320.0, 403.2,
          508.0, 640.0, 806.3, 1015.9, 1280.0, 1612.7, 2031.9, 2560.0, 3225.4, 4063.7);
  private static final List<Double> RETRY_BOUNDARIES =
      Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
  private static final View[] VIEWS = {
    OpenCensusMetrics.Views.REQUEST_COUNT,
    OpenCensusMetrics.Views.REQUEST_LATENCY,
    OpenCensusMetrics.Views.NETWORK_LATENCY,
    OpenCensusMetrics.Views.RETRY_COUNT
  };

  @Test
  public void collectedViewsKeepMetricNamesUnitsAttributesAndBoundaries() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      assertView(
          collection.snapshot(OpenCensusMetrics.Views.REQUEST_COUNT),
          "request_count",
          "latency",
          "ms",
          null);
      assertView(
          collection.snapshot(OpenCensusMetrics.Views.REQUEST_LATENCY),
          "request_latency",
          "latency",
          "ms",
          LATENCY_BOUNDARIES);
      assertView(
          collection.snapshot(OpenCensusMetrics.Views.NETWORK_LATENCY),
          "network_latency",
          "network_latency",
          "ms",
          LATENCY_BOUNDARIES);
      assertView(
          collection.snapshot(OpenCensusMetrics.Views.RETRY_COUNT),
          "retry_count",
          "retry_count",
          "1",
          RETRY_BOUNDARIES);
    }
  }

  @Test
  public void successRecordsOneNetworkIntervalAndSeparateTotalLatency() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(1000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/success");
      clock.addAndGet(20);
      request.endNetwork();
      clock.addAndGet(60);
      request.endRequest(null, 200, 0);

      assertOutcome(collection, "/success", "", 200, 80, 7, 20, 1, 0, 0);
    }
  }

  @Test
  public void multipleNetworkIntervalsAccumulateButExcludeTheGapAndFinalProcessing() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(2000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/retried");
      clock.addAndGet(10);
      request.endNetwork();
      clock.addAndGet(60);
      request.startNetwork();
      clock.addAndGet(30);
      request.endNetwork();
      clock.addAndGet(27);
      request.endRequest(null, 200, 1);

      assertOutcome(collection, "/retried", "", 200, 127, 9, 40, 4, 1, 1);
    }
  }

  @Test
  public void apiFailurePreservesHttpSuccessAndFullyQualifiedExceptionStatus() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(3000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/denied");
      request.endNetwork();
      clock.addAndGet(25);
      request.endRequest(new RequestDeniedException("denied"), 200, 3);

      assertOutcome(
          collection,
          "/denied",
          "com.google.maps.errors.RequestDeniedException",
          200,
          25,
          1,
          0,
          0,
          3,
          3);
    }
  }

  @Test
  public void transportFailureRecordsHttpZeroAndRetryOverflow() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(4000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/disconnected");
      clock.addAndGet(5000);
      request.endNetwork();
      request.endRequest(new IOException("disconnected"), 0, 11);

      assertOutcome(
          collection, "/disconnected", "java.io.IOException", 0, 5000, 24, 5000, 24, 11, 10);
    }
  }

  @Test
  public void duplicateFailureCannotReplaceSuccessfulCompletionOrAddAnObservation() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(5000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/first-success");
      clock.addAndGet(20);
      request.endNetwork();
      clock.addAndGet(20);
      request.endRequest(null, 200, 2);
      clock.addAndGet(5000);
      request.endRequest(new IOException("late failure"), 0, 9);

      assertOutcome(collection, "/first-success", "", 200, 40, 4, 20, 1, 2, 2);
    }
  }

  @Test
  public void duplicateSuccessCannotRelabelFirstFailureWithAnEarlierHttpResponse() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(6000);
      RequestMetrics request = collection.reporter(clock::get).newRequest("/first-failure");
      clock.addAndGet(19);
      request.endNetwork();
      request.endRequest(new IOException("first failure"), 0, 1);
      clock.addAndGet(100);
      request.endRequest(null, 500, 8);

      assertOutcome(collection, "/first-failure", "java.io.IOException", 0, 19, 0, 19, 0, 1, 1);
    }
  }

  @Test
  public void histogramBucketsIncludeZeroExactBoundariesAndOverflow() {
    try (MetricsTestSupport collection = new MetricsTestSupport()) {
      AtomicLong clock = new AtomicLong(7000);
      long[] durations = {0, 19, 20, 25, 40, 4063, 4064};
      long[] retries = {0, 1, 2, 3, 4, 10, 11};
      for (int i = 0; i < durations.length; i++) {
        RequestMetrics request = collection.reporter(clock::get).newRequest("/buckets");
        clock.addAndGet(durations[i]);
        request.endNetwork();
        request.endRequest(null, 200, retries[i]);
      }

      List<TagValue> tags = tags("/buckets", "", 200);
      assertCount(collection, tags, 7);
      List<Long> latencyBuckets = buckets(25, 0, 0, 1, 1, 4, 23, 24);
      assertDistribution(
          onlyRow(collection, OpenCensusMetrics.Views.REQUEST_LATENCY, tags),
          7,
          8231.0 / 7,
          latencyBuckets);
      assertDistribution(
          onlyRow(collection, OpenCensusMetrics.Views.NETWORK_LATENCY, tags),
          7,
          8231.0 / 7,
          latencyBuckets);
      assertDistribution(
          onlyRow(collection, OpenCensusMetrics.Views.RETRY_COUNT, tags),
          7,
          31.0 / 7,
          buckets(11, 0, 1, 2, 3, 4, 10, 10));
    }
  }

  private static void assertView(
      ViewData data, String name, String measure, String unit, List<Double> boundaries) {
    assertNotNull(data);
    View view = data.getView();
    assertEquals("maps.googleapis.com/client/" + name, view.getName().asString());
    assertEquals("maps.googleapis.com/measure/client/" + measure, view.getMeasure().getName());
    assertEquals(unit, view.getMeasure().getUnit());
    assertEquals(
        Arrays.asList(
            TagKey.create("api_status"), TagKey.create("http_code"), TagKey.create("request_name")),
        view.getColumns());
    if (boundaries == null) {
      assertTrue(view.getAggregation() instanceof Aggregation.Count);
    } else {
      assertTrue(view.getAggregation() instanceof Aggregation.Distribution);
      assertEquals(
          boundaries,
          ((Aggregation.Distribution) view.getAggregation()).getBucketBoundaries().getBoundaries());
    }
  }

  private static void assertOutcome(
      MetricsTestSupport collection,
      String path,
      String status,
      int httpCode,
      long latency,
      int latencyBucket,
      long network,
      int networkBucket,
      long retries,
      int retryBucket) {
    List<TagValue> tags = tags(path, status, httpCode);
    assertCount(collection, tags, 1);
    assertDistribution(
        onlyRow(collection, OpenCensusMetrics.Views.REQUEST_LATENCY, tags),
        1,
        latency,
        buckets(25, latencyBucket));
    assertDistribution(
        onlyRow(collection, OpenCensusMetrics.Views.NETWORK_LATENCY, tags),
        1,
        network,
        buckets(25, networkBucket));
    assertDistribution(
        onlyRow(collection, OpenCensusMetrics.Views.RETRY_COUNT, tags),
        1,
        retries,
        buckets(11, retryBucket));
  }

  private static List<TagValue> tags(String path, String status, int httpCode) {
    return Arrays.asList(
        TagValue.create(status),
        TagValue.create(Integer.toString(httpCode)),
        TagValue.create(path));
  }

  private static void assertCount(MetricsTestSupport collection, List<TagValue> tags, long count) {
    AggregationData data = onlyRow(collection, OpenCensusMetrics.Views.REQUEST_COUNT, tags);
    assertTrue(data instanceof AggregationData.CountData);
    assertEquals(count, ((AggregationData.CountData) data).getCount());
  }

  private static AggregationData onlyRow(
      MetricsTestSupport collection, View view, List<TagValue> tags) {
    ViewData data = collection.snapshot(view);
    assertNotNull(data);
    assertEquals(view.getName().asString(), 1, data.getAggregationMap().size());
    assertTrue(data.getAggregationMap().containsKey(tags));
    return data.getAggregationMap().get(tags);
  }

  private static void assertDistribution(
      AggregationData data, long count, double mean, List<Long> bucketCounts) {
    assertTrue(data instanceof DistributionData);
    DistributionData distribution = (DistributionData) data;
    assertEquals(count, distribution.getCount());
    assertEquals(mean, distribution.getMean(), 0.000001);
    assertEquals(bucketCounts, distribution.getBucketCounts());
  }

  private static List<Long> buckets(int size, int... observations) {
    List<Long> counts = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      counts.add(0L);
    }
    for (int bucket : observations) {
      counts.set(bucket, counts.get(bucket) + 1);
    }
    return counts;
  }
}
