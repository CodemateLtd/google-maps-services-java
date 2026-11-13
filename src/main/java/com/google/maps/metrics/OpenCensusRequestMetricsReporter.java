package com.google.maps.metrics;

import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;

/**
 * An opt-in reporter that records request metrics using global OpenCensus services.
 *
 * <p>Before requests, register the views with {@link OpenCensusMetrics#registerAllViews()} and
 * supply this reporter to {@link
 * com.google.maps.GeoApiContext.Builder#requestMetricsReporter(RequestMetricsReporter)}. The
 * application must provide an OpenCensus implementation (matching the API version) and configure
 * any exporter; selecting this reporter alone does not configure a destination. OpenCensus is
 * archived; this reporter documents the existing integration rather than a new telemetry
 * recommendation.
 *
 * <p>Only the first completion of each request records metrics. The {@code api_status} tag is empty
 * on success or the fully qualified exception class name on failure, not the raw service status.
 * Latencies use wall-clock milliseconds; network latency accumulates instrumented intervals and can
 * include retry backoff, rather than measuring pure wire time.
 *
 * <p>The application owns exporter/provider flush and shutdown. Shutting down a {@code
 * GeoApiContext} does not flush metrics or shut down OpenCensus resources.
 */
public final class OpenCensusRequestMetricsReporter implements RequestMetricsReporter {
  private static final Tagger tagger = Tags.getTagger();
  private static final StatsRecorder statsRecorder = Stats.getStatsRecorder();

  public OpenCensusRequestMetricsReporter() {}

  @Override
  public RequestMetrics newRequest(String requestName) {
    return new OpenCensusRequestMetrics(requestName, tagger, statsRecorder);
  }
}
