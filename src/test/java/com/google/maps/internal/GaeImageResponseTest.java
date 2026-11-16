/*
 * Copyright 2026 Google Inc. All rights reserved.
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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.gson.FieldNamingPolicy;
import com.google.maps.ImageResult;
import com.google.maps.errors.OverQueryLimitException;
import com.google.maps.errors.RequestDeniedException;
import com.google.maps.metrics.RequestMetrics;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

/**
 * Uses real URL Fetch responses and the actual pending-result parser, without App Engine services.
 */
public class GaeImageResponseTest {
  private static final byte[] IMAGE = {(byte) 0x89, 0x50, 0, (byte) 0xff, 42};
  private URLFetchService service;
  private HTTPRequest request;
  private URL url;
  private Queue<HTTPResponse> responses;

  @Before
  public void setUp() throws Exception {
    url = new URL("https://example.invalid/maps/api/staticmap");
    request = new HTTPRequest(url);
    responses = new ArrayDeque<>();
    service = mock(URLFetchService.class);
    when(service.fetchAsync(any(HTTPRequest.class)))
        .thenAnswer(invocation -> CompletableFuture.completedFuture(responses.remove()));
  }

  private HTTPResponse response(int status, String type, byte[] bytes) {
    return new HTTPResponse(
        status,
        bytes,
        url,
        type == null
            ? Collections.emptyList()
            : Collections.singletonList(new HTTPHeader("cOnTeNt-TyPe", type)));
  }

  private GaePendingResult<ImageResult, ImageResult.Response> pending(
      long timeout, int maxRetries, ExceptionsAllowedToRetry allowed) {
    return new GaePendingResult<>(
        request,
        service,
        ImageResult.Response.class,
        FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES,
        timeout,
        maxRetries,
        allowed,
        mock(RequestMetrics.class));
  }

  private ImageResult receive(HTTPResponse response) throws Exception {
    responses.add(response);
    return pending(0, 0, new ExceptionsAllowedToRetry()).await();
  }

  private void assertFailure(
      int status, String type, byte[] bytes, Class<?> expected, String message) throws Exception {
    try {
      receive(response(status, type, bytes));
      fail("Expected an image request failure");
    } catch (Exception e) {
      assertEquals(expected, e.getClass());
      assertTrue(e.getMessage(), e.getMessage().contains(message));
    }
    verify(service).fetchAsync(request);
  }

  @Test
  public void recognizedClassicServiceError() throws Exception {
    assertFailure(
        403,
        "application/json",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Key rejected\"}".getBytes(UTF_8),
        RequestDeniedException.class,
        "Key rejected");
  }

  @Test
  public void recognizedNestedServiceError() throws Exception {
    assertFailure(
        403,
        "application/json",
        "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Access denied\"}}"
            .getBytes(UTF_8),
        RequestDeniedException.class,
        "Access denied");
  }

  @Test
  public void arbitraryJsonIsNotSuccessfulNull() throws Exception {
    assertFailure(
        400,
        "application/json",
        "{\"error\":\"Bad photo\"}".getBytes(UTF_8),
        IOException.class,
        "400");
  }

  @Test
  public void malformedErrorIncludesStatus() throws Exception {
    assertFailure(403, "application/json", "{broken".getBytes(UTF_8), IOException.class, "403");
  }

  @Test
  public void textErrorIncludesStatus() throws Exception {
    assertFailure(403, "text/plain", "Forbidden".getBytes(UTF_8), IOException.class, "403");
  }

  @Test
  public void emptyErrorIncludesStatus() throws Exception {
    assertFailure(404, "application/json", new byte[0], IOException.class, "404");
  }

  @Test
  public void nullErrorBodyIncludesStatus() throws Exception {
    assertFailure(403, null, null, IOException.class, "403");
  }

  @Test
  public void imageTypedHttpErrorIsNotSuccessful() throws Exception {
    assertFailure(403, "image/png", IMAGE, IOException.class, "403");
  }

  @Test
  public void nonImageSuccessfulHttpIsNotSuccessfulNull() throws Exception {
    assertFailure(200, "application/json", "{}".getBytes(UTF_8), IOException.class, "200");
  }

  @Test
  public void emptySuccessfulImageFails() throws Exception {
    assertFailure(200, "image/png", new byte[0], IOException.class, "200");
  }

  @Test
  public void nullSuccessfulImageFails() throws Exception {
    assertFailure(200, "image/png", null, IOException.class, "200");
  }

  @Test
  public void missingContentTypeFails() throws Exception {
    assertFailure(200, null, IMAGE, IOException.class, "200");
  }

  @Test
  public void imagePrefixWithoutMediaTypeFails() throws Exception {
    assertFailure(200, "image-not-a-media-type", IMAGE, IOException.class, "200");
  }

  @Test
  public void preservesBytesAndCaseInsensitiveContentType() throws Exception {
    ImageResult result = receive(response(200, "ImAgE/PNG; custom=Value", IMAGE));
    assertArrayEquals(IMAGE, result.imageData);
    assertEquals("ImAgE/PNG; custom=Value", result.contentType);
    verify(service).fetchAsync(request);
  }

  @Test
  public void acceptsOtherSuccessfulStatusesWithoutInspectingImageContent() throws Exception {
    byte[] warningGraphic = "warning graphic".getBytes(UTF_8);
    ImageResult result = receive(response(201, "image/png", warningGraphic));
    assertArrayEquals(warningGraphic, result.imageData);
    verify(service).fetchAsync(request);
  }

  @Test
  public void redirectStatusWithoutFinalImageFails() throws Exception {
    // URL Fetch follows redirects before delivering HTTPResponse; an unfollowed redirect is not 2xx.
    assertFailure(302, "image/png", IMAGE, IOException.class, "302");
  }

  @Test
  public void recognizedServiceErrorsRetainExceptionRetryAllowlist() throws Exception {
    responses.add(
        response(
            403,
            "application/json",
            "{\"status\":\"OVER_QUERY_LIMIT\",\"error_message\":\"Try again\"}".getBytes(UTF_8)));
    responses.add(response(200, "image/png", IMAGE));
    ExceptionsAllowedToRetry allowed = new ExceptionsAllowedToRetry();
    allowed.add(OverQueryLimitException.class);
    ImageResult result = pending(1000, 1, allowed).await();
    assertArrayEquals(IMAGE, result.imageData);
    verify(service, times(2)).fetchAsync(request);
  }

  @Test
  public void serviceErrorOutsideAllowlistDoesNotRetry() throws Exception {
    responses.add(
        response(
            403,
            "application/json",
            "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied\"}".getBytes(UTF_8)));
    responses.add(response(200, "image/png", IMAGE));
    ExceptionsAllowedToRetry allowed = new ExceptionsAllowedToRetry();
    allowed.add(OverQueryLimitException.class);
    try {
      pending(1000, 1, allowed).await();
      fail("Expected denied request");
    } catch (RequestDeniedException e) {
      assertEquals("Denied", e.getMessage());
    }
    verify(service).fetchAsync(request);
  }

  @Test
  public void successfulHttpServiceErrorRemainsMeaningful() throws Exception {
    assertFailure(
        200,
        "application/json",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied\"}".getBytes(UTF_8),
        RequestDeniedException.class,
        "Denied");
  }

  @Test
  public void imageTypedServiceErrorRemainsMeaningful() throws Exception {
    assertFailure(
        403,
        "image/png",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied image\"}".getBytes(UTF_8),
        RequestDeniedException.class,
        "Denied image");
  }

  @Test
  public void successfulHttpTextIsNotAnImage() throws Exception {
    assertFailure(200, "text/plain", "not an image".getBytes(UTF_8), IOException.class, "200");
  }

  @Test
  public void jsonNullIsNotSuccessfulNull() throws Exception {
    assertFailure(403, "application/json", "null".getBytes(UTF_8), IOException.class, "403");
  }
}
