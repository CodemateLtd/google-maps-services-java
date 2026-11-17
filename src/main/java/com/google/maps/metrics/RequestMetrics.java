package com.google.maps.metrics;

/**
 * A type to report common metrics shared among all request types.
 *
 * <p>If a request retries, there will be multiple calls to all methods below. Ignore any endRequest
 * after the first one. For example:
 *
 * <ol>
 *   <li>constructor - request starts
 *   <li>startNetwork / endNetwork - original request
 *   <li>startNetwork / endNetwork - retried request
 *   <li>endRequest - request finished (retry)
 *   <li>endRequest - request finished (original)
 * </ol>
 *
 * <p>The following metrics can be computed: Total queries, successful queries, total latency,
 * network latency
 */
public interface RequestMetrics {

  void startNetwork();

  void endNetwork();

  /**
   * Completes a logical request with its final outcome, not an earlier retry's response.
   *
   * @param exception The final failure, or null on success.
   * @param httpStatusCode The final HTTP status, or zero for a transport failure without a
   *     response.
   * @param retryCount The number of retries made for this logical request.
   */
  void endRequest(Exception exception, int httpStatusCode, long retryCount);
}
