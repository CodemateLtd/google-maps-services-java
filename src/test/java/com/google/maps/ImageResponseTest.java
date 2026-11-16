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
package com.google.maps;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.maps.errors.RequestDeniedException;
import com.google.maps.model.Size;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Exercises the public image endpoints through both supported OkHttp consumption modes. */
@RunWith(Parameterized.class)
public class ImageResponseTest {
  private static final byte[] IMAGE = {(byte) 0x89, 0x50, 0, (byte) 0xff, 42};
  private final boolean photo;
  private final boolean async;
  private MockWebServer server;
  private GeoApiContext context;

  @Parameterized.Parameters(name = "photo={0}, async={1}")
  public static Collection<Object[]> modes() {
    return Arrays.asList(
        new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}});
  }

  public ImageResponseTest(boolean photo, boolean async) {
    this.photo = photo;
    this.async = async;
  }

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    context =
        new GeoApiContext.Builder()
            .apiKey("AIzaFakeKey")
            .baseUrlOverride("http://127.0.0.1:" + server.getPort())
            .maxRetries(0)
            .build();
  }

  @After
  public void tearDown() throws Exception {
    if (context != null) {
      context.shutdown();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  private PendingResult<ImageResult> request() {
    if (photo) {
      return PlacesApi.photo(context, "photo-reference").maxWidth(10);
    }
    return StaticMapsApi.newRequest(context, new Size(10, 10)).center("Sydney").zoom(1);
  }

  private ImageResult receive() throws Exception {
    PendingResult<ImageResult> request = request();
    if (!async) {
      return request.await();
    }
    CompletableFuture<ImageResult> result = new CompletableFuture<>();
    request.setCallback(
        new PendingResult.Callback<ImageResult>() {
          @Override
          public void onResult(ImageResult image) {
            result.complete(image);
          }

          @Override
          public void onFailure(Throwable error) {
            result.completeExceptionally(error);
          }
        });
    try {
      return result.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw (Exception) e.getCause();
    }
  }

  private void assertFailure(
      int status, String contentType, String body, Class<?> expected, String message)
      throws Exception {
    int before = server.getRequestCount();
    server.enqueue(
        new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", contentType)
            .setBody(body));
    try {
      receive();
      fail("Expected an image request failure");
    } catch (Exception e) {
      assertEquals(expected, e.getClass());
      assertTrue(e.getMessage(), e.getMessage().contains(message));
    }
    assertEquals(before + 1, server.getRequestCount());
  }

  @Test
  public void recognizedClassicServiceError() throws Exception {
    assertFailure(
        403,
        "application/json",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Key rejected\"}",
        RequestDeniedException.class,
        "Key rejected");
  }

  @Test
  public void recognizedNestedServiceError() throws Exception {
    assertFailure(
        403,
        "application/json",
        "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Access denied\"}}",
        RequestDeniedException.class,
        "Access denied");
  }

  @Test
  public void arbitraryJsonErrorsAreNotSuccessfulNull() throws Exception {
    assertFailure(400, "application/json", "{\"error\":\"Bad photo\"}", IOException.class, "400");
    assertFailure(403, "application/json", "{}", IOException.class, "403");
    assertFailure(403, "application/json", "null", IOException.class, "403");
  }

  @Test
  public void malformedTextAndEmptyErrorsIncludeHttpStatus() throws Exception {
    assertFailure(400, "application/json", "{broken", IOException.class, "400");
    assertFailure(403, "text/plain", "Forbidden", IOException.class, "403");
    assertFailure(404, "application/json", "", IOException.class, "404");
  }

  @Test
  public void imageTypedHttpFailureIsNotAnImageResult() throws Exception {
    assertFailure(403, "image/png", "error graphic", IOException.class, "403");
    assertFailure(
        403,
        "image/png",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied image\"}",
        RequestDeniedException.class,
        "Denied image");
  }

  @Test
  public void successfulHttpRequiresNonemptyImage() throws Exception {
    assertFailure(200, "application/json", "{}", IOException.class, "200");
    assertFailure(200, "text/plain", "not an image", IOException.class, "200");
    assertFailure(200, "image/png", "", IOException.class, "200");
    assertFailure(200, "image-not-a-media-type", "data", IOException.class, "200");
    assertFailure(204, "image/png", "", IOException.class, "204");
    assertFailure(
        200,
        "application/json",
        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied\"}",
        RequestDeniedException.class,
        "Denied");
  }

  @Test
  public void preservesImageBytesAndCaseInsensitiveContentType() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "ImAgE/PNG; custom=Value")
            .setBody(new Buffer().write(IMAGE)));
    ImageResult image = receive();
    assertArrayEquals(IMAGE, image.imageData);
    assertEquals("ImAgE/PNG; custom=Value", image.contentType);
    assertEquals(1, server.getRequestCount());
    assertTrue(
        server
            .takeRequest()
            .getPath()
            .startsWith(photo ? "/maps/api/place/photo?" : "/maps/api/staticmap?"));
  }

  @Test
  public void acceptsOtherSuccessfulHttpStatusesWithoutInspectingImageContents() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "image/png")
            .setBody("warning graphic"));
    assertArrayEquals(
        "warning graphic".getBytes(java.nio.charset.StandardCharsets.UTF_8), receive().imageData);
    assertEquals(1, server.getRequestCount());
  }

  @Test
  public void followsRedirectToImage() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", server.url("/redirected-image")));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "image/jpeg")
            .setBody(new Buffer().write(IMAGE)));
    ImageResult image = receive();
    assertArrayEquals(IMAGE, image.imageData);
    assertEquals("image/jpeg", image.contentType);
    assertEquals(2, server.getRequestCount());
    server.takeRequest();
    assertEquals("/redirected-image", server.takeRequest().getPath());
  }

  @Test
  public void missingContentTypeFailsWithStatus() throws Exception {
    server.enqueue(new MockResponse().setBody(new Buffer().write(IMAGE)));
    try {
      receive();
      fail("Expected missing image content type to fail");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("200"));
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  public void nonretryableServiceErrorDoesNotConsumeQueuedImage() throws Exception {
    context.shutdown();
    context =
        new GeoApiContext.Builder()
            .apiKey("AIzaFakeKey")
            .baseUrlOverride("http://127.0.0.1:" + server.getPort())
            .maxRetries(1)
            .build();
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setResponseCode(403)
            .setBody("{\"status\":\"REQUEST_DENIED\",\"error_message\":\"Denied\"}"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody(new Buffer().write(IMAGE)));
    try {
      receive();
      fail("Expected denied request");
    } catch (RequestDeniedException e) {
      assertEquals("Denied", e.getMessage());
    }
    assertEquals(1, server.getRequestCount());
  }
}
