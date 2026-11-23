/*
 * Copyright 2026 Google Inc. All rights reserved.
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.google.maps.compatibility;

import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.errors.RequestDeniedException;
import com.google.maps.model.AddressComponentType;
import com.google.maps.model.AddressType;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LocationType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the packaged SDK and its runtime dependencies as a Java 8 consumer, entirely offline.
 */
public final class Java8CompatibilityTest {
  private static final String API_KEY = "AIza-java8-compatibility-test-key";
  private static final String ADDRESS = "8 Rue de l'Été, Paris";
  private static final String DENIED_ADDRESS = "denied";
  private static final String ERROR_MESSAGE = "Fixture denied: clé invalide.";
  private static final String SUCCESS_RESPONSE =
      "{\"status\":\"OK\",\"results\":[{"
          + "\"formatted_address\":\"8 Rue de l'Été, Paris, France\","
          + "\"place_id\":\"java8-fixture-place\","
          + "\"types\":[\"street_address\"],\"partial_match\":true,"
          + "\"address_components\":[{\"long_name\":\"France\",\"short_name\":\"FR\","
          + "\"types\":[\"country\",\"political\"]}],"
          + "\"geometry\":{\"location\":{\"lat\":48.8566,\"lng\":2.3522},"
          + "\"location_type\":\"ROOFTOP\"}}]}";
  private static final String DENIED_RESPONSE =
      "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"" + ERROR_MESSAGE + "\",\"results\":[]}";

  private Java8CompatibilityTest() {}

  public static void main(String[] args) throws Exception {
    String javaVersion = System.getProperty("java.specification.version");
    if (!"1.8".equals(javaVersion)) {
      throw new IllegalStateException(
          "Expected a Java 8 consumer runtime, but found " + javaVersion);
    }

    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/maps/api/geocode/json",
          exchange -> {
            try {
              String response =
                  requests.getAndIncrement() == 0 ? SUCCESS_RESPONSE : DENIED_RESPONSE;
              byte[] body = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } finally {
              exchange.close();
            }
          });
      server.start();

      try (GeoApiContext context =
          new GeoApiContext.Builder()
              .apiKey(API_KEY)
              .baseUrlOverride("http://127.0.0.1:" + server.getAddress().getPort())
              .connectTimeout(5, TimeUnit.SECONDS)
              .readTimeout(5, TimeUnit.SECONDS)
              .disableRetries()
              .build()) {
        GeocodingResult[] results = GeocodingApi.geocode(context, ADDRESS).await().results;
        assertEquals("result count", 1, results.length);
        GeocodingResult result = results[0];
        assertEquals("formatted address", "8 Rue de l'Été, Paris, France", result.formattedAddress);
        assertEquals("place ID", "java8-fixture-place", result.placeId);
        assertEquals("latitude", 48.8566, result.geometry.location.lat);
        assertEquals("longitude", 2.3522, result.geometry.location.lng);
        assertEquals("location type", LocationType.ROOFTOP, result.geometry.locationType);
        assertEquals("partial match", true, result.partialMatch);
        assertEquals("address type count", 1, result.types.length);
        assertEquals("address type", AddressType.STREET_ADDRESS, result.types[0]);
        assertEquals("component count", 1, result.addressComponents.length);
        assertEquals("country name", "France", result.addressComponents[0].longName);
        assertEquals("country abbreviation", "FR", result.addressComponents[0].shortName);
        assertEquals("component type count", 2, result.addressComponents[0].types.length);
        assertEquals(
            "country type", AddressComponentType.COUNTRY, result.addressComponents[0].types[0]);
        assertEquals(
            "political type", AddressComponentType.POLITICAL, result.addressComponents[0].types[1]);

        try {
          GeocodingApi.geocode(context, DENIED_ADDRESS).await();
          throw new AssertionError("Expected REQUEST_DENIED to throw RequestDeniedException");
        } catch (RequestDeniedException expected) {
          assertEquals("API error type", RequestDeniedException.class, expected.getClass());
          assertEquals("API error message", ERROR_MESSAGE, expected.getMessage());
        }
      }
    } finally {
      // The server's default executor is owned and stopped by HttpServer.
      server.stop(0);
    }
  }

  private static void assertEquals(String label, Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }
}
