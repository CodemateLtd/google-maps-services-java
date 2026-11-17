package com.google.maps.metrics;

import io.opencensus.implcore.common.MillisClock;
import io.opencensus.implcore.internal.SimpleEventQueue;
import io.opencensus.implcore.stats.StatsComponentImplBase;
import io.opencensus.implcore.tags.TagsComponentImplBase;
import io.opencensus.metrics.Metrics;
import io.opencensus.metrics.export.MetricProducer;
import io.opencensus.metrics.export.MetricProducerManager;
import io.opencensus.stats.StatsCollectionState;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.stats.View;
import io.opencensus.stats.ViewData;
import io.opencensus.tags.Tagger;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Real, isolated OpenCensus collection with synchronous recording for metrics tests. */
public final class MetricsTestSupport implements AutoCloseable {
  private final SimpleEventQueue queue = new SimpleEventQueue();
  private final StatsComponentImplBase stats;
  private final Tagger tagger = new TagsComponentImplBase().getTagger();

  public MetricsTestSupport() {
    MetricProducerManager producers = Metrics.getExportComponent().getMetricProducerManager();
    // In impl-core 0.31.1, add/remove synchronize on this same manager. Holding its monitor makes
    // the new producer identifiable without intercepting unrelated concurrent registrations.
    synchronized (producers) {
      Set<MetricProducer> existing = new HashSet<>(producers.getAllMetricProducer());
      stats = new StatsComponentImplBase(queue, MillisClock.getInstance());
      // The implementation registers an exporter globally even though its views are local. Detach
      // this fixture's producer immediately so it cannot retain the collection or affect exporters.
      Set<MetricProducer> added = new HashSet<>(producers.getAllMetricProducer());
      added.removeAll(existing);
      for (MetricProducer producer : added) {
        producers.remove(producer);
      }
    }
    OpenCensusMetrics.registerAllViews(stats.getViewManager());
  }

  public RequestMetricsReporter reporter() {
    return requestName -> new OpenCensusRequestMetrics(requestName, tagger, recorder());
  }

  public RequestMetricsReporter reporter(LongSupplier clock) {
    return requestName -> new OpenCensusRequestMetrics(requestName, tagger, recorder(), clock);
  }

  public StatsRecorder recorder() {
    return stats.getStatsRecorder();
  }

  public Tagger tagger() {
    return tagger;
  }

  /** SimpleEventQueue processes records inline; no polling, sleeps, or exporter flush is needed. */
  public ViewData snapshot(View view) {
    return stats.getViewManager().getView(view.getName());
  }

  @Override
  public void close() {
    stats.setState(StatsCollectionState.DISABLED);
    queue.shutdown();
  }
}
