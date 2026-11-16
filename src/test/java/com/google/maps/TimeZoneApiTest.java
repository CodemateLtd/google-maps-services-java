/*
 * Copyright 2014 Google Inc. All rights reserved.
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

package com.google.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.maps.errors.RequestDeniedException;
import com.google.maps.errors.ZeroResultsException;
import com.google.maps.model.LatLng;
import com.google.maps.model.TimeZoneResult;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(MediumTests.class)
public class TimeZoneApiTest {

  @Test
  public void testGetTimeZone() throws Exception {
    try (LocalTestServerContext sc =
        new LocalTestServerContext(
            "\n"
                + "{\n"
                + "   \"dstOffset\" : 0,\n"
                + "   \"rawOffset\" : 36000,\n"
                + "   \"status\" : \"OK\",\n"
                + "   \"timeZoneId\" : \"Australia/Sydney\",\n"
                + "   \"timeZoneName\" : \"Australian Eastern Standard Time\"\n"
                + "}\n")) {
      LatLng sydney = new LatLng(-33.8688, 151.2093);
      TimeZone tz = TimeZoneApi.getTimeZone(sc.context, sydney).await();

      assertNotNull(tz);
      assertEquals(TimeZone.getTimeZone("Australia/Sydney"), tz);

      // GMT+10
      assertEquals(36000000, tz.getRawOffset());
      // DST is +1h
      assertEquals(3600000, tz.getDSTSavings());

      assertTrue(tz.inDaylightTime(new Date(1388494800000L)));

      sc.assertParamValue(sydney.toUrlValue(), "location");
    }
  }

  @Test(expected = ZeroResultsException.class)
  public void testNoResult() throws Exception {
    try (LocalTestServerContext sc =
        new LocalTestServerContext("\n{\n   \"status\" : \"ZERO_RESULTS\"\n}\n")) {
      TimeZone resp = TimeZoneApi.getTimeZone(sc.context, new LatLng(0, 0)).awaitIgnoreError();
      assertNull(resp);

      sc.assertParamValue("0.00000000,0.00000000", "location");

      try (LocalTestServerContext sc2 =
          new LocalTestServerContext("\n{\n   \"status\" : \"ZERO_RESULTS\"\n}\n")) {
        TimeZoneApi.getTimeZone(sc2.context, new LatLng(0, 0)).await();
      }
    }
  }

  @Test
  public void testDetailedTimeZonePreservesServiceValuesAndLocalizedName() throws Exception {
    // Deliberately differ from JVM rules so recomputing the service values would fail the test.
    try (LocalTestServerContext sc =
        new LocalTestServerContext(
            "{\"status\":\"OK\",\"timeZoneId\":\"America/Los_Angeles\","
                + "\"timeZoneName\":\"hora de verano del Pacífico\","
                + "\"rawOffset\":-27000,\"dstOffset\":1800}")) {
      LatLng location = new LatLng(34.05, -118.25);
      TimeZoneResult result =
          TimeZoneApi.getTimeZoneResult(sc.context, location, 1710547034L, "es").await();

      assertEquals("America/Los_Angeles", result.timeZoneId);
      assertEquals("hora de verano del Pacífico", result.timeZoneName);
      assertEquals(Double.valueOf(-27000), result.rawOffset);
      assertEquals(Double.valueOf(1800), result.dstOffset);
      assertEquals("/maps/api/timezone/json", sc.path());
      sc.assertParamValue(location.toUrlValue(), "location");
      sc.assertParamValue("1710547034", "timestamp");
      sc.assertParamValues(Collections.singletonList("es"), "language");
    }
  }

  @Test
  public void testDetailedTimeZonePreservesWinterAndSummerOffsets() throws Exception {
    String[] responses = {
      "{\"status\":\"OK\",\"timeZoneId\":\"America/Los_Angeles\","
          + "\"timeZoneName\":\"Pacific Standard Time\",\"rawOffset\":-28800,\"dstOffset\":0}",
      "{\"status\":\"OK\",\"timeZoneId\":\"America/Los_Angeles\","
          + "\"timeZoneName\":\"Pacific Daylight Time\",\"rawOffset\":-28800,\"dstOffset\":3600}"
    };
    long[] timestamps = {1733428634L, 1710547034L};
    String[] names = {"Pacific Standard Time", "Pacific Daylight Time"};
    double[] dstOffsets = {0, 3600};
    for (int i = 0; i < responses.length; i++) {
      try (LocalTestServerContext sc = new LocalTestServerContext(responses[i])) {
        TimeZoneResult result =
            TimeZoneApi.getTimeZoneResult(
                    sc.context, new LatLng(39.603481, -119.682251), timestamps[i])
                .await();

        assertEquals("America/Los_Angeles", result.timeZoneId);
        assertEquals(names[i], result.timeZoneName);
        assertEquals(Double.valueOf(-28800), result.rawOffset);
        assertEquals(Double.valueOf(dstOffsets[i]), result.dstOffset);
        sc.assertParamValue(Long.toString(timestamps[i]), "timestamp");
        sc.assertParamValues(Collections.emptyList(), "language");
      }
    }
  }

  @Test
  public void testDetailedTimeZoneTimestampBoundariesAndNullLanguage() throws Exception {
    long[] timestamps = {0, -1, 2208988800L};
    for (long timestamp : timestamps) {
      try (LocalTestServerContext sc =
          new LocalTestServerContext(
              "{\"status\":\"OK\",\"timeZoneId\":\"Etc/UTC\",\"timeZoneName\":\"UTC\","
                  + "\"rawOffset\":0,\"dstOffset\":0}")) {
        TimeZoneResult result =
            TimeZoneApi.getTimeZoneResult(sc.context, new LatLng(0, 0), timestamp, null).await();

        assertEquals("Etc/UTC", result.timeZoneId);
        assertEquals("UTC", result.timeZoneName);
        assertEquals(Double.valueOf(0), result.rawOffset);
        assertEquals(Double.valueOf(0), result.dstOffset);
        sc.assertParamValues(Collections.singletonList(Long.toString(timestamp)), "timestamp");
        sc.assertParamValues(Collections.emptyList(), "language");
      }
    }
  }

  @Test
  public void testDetailedTimeZoneDistinguishesZeroAndMissingFields() throws Exception {
    try (LocalTestServerContext sc =
        new LocalTestServerContext(
            "{\"status\":\"OK\",\"timeZoneId\":\"Etc/UTC\",\"rawOffset\":0}")) {
      TimeZoneResult result =
          TimeZoneApi.getTimeZoneResult(sc.context, new LatLng(0, 0), 0).await();

      assertEquals("Etc/UTC", result.timeZoneId);
      assertEquals(Double.valueOf(0), result.rawOffset);
      assertNull(result.dstOffset);
      assertNull(result.timeZoneName);
    }
  }

  @Test
  public void testDetailedTimeZonePreservesAbsentOptionalFields() throws Exception {
    try (LocalTestServerContext sc = new LocalTestServerContext("{\"status\":\"OK\"}")) {
      TimeZoneResult result =
          TimeZoneApi.getTimeZoneResult(sc.context, new LatLng(0, 0), 0).await();

      assertNotNull(result);
      assertNull(result.timeZoneId);
      assertNull(result.timeZoneName);
      assertNull(result.rawOffset);
      assertNull(result.dstOffset);
    }
  }

  @Test(expected = ZeroResultsException.class)
  public void testDetailedTimeZoneRejectsZeroResults() throws Exception {
    try (LocalTestServerContext sc = new LocalTestServerContext("{\"status\":\"ZERO_RESULTS\"}")) {
      TimeZoneApi.getTimeZoneResult(sc.context, new LatLng(0, 0), 0).await();
    }
  }

  @Test
  public void testDetailedTimeZonePreservesRequestDeniedMessage() throws Exception {
    try (LocalTestServerContext sc =
        new LocalTestServerContext(
            "{\"status\":\"REQUEST_DENIED\",\"errorMessage\":\"Permission denied for this project\"}")) {
      try {
        TimeZoneApi.getTimeZoneResult(sc.context, new LatLng(0, 0), 0, "es").await();
        fail("Expected the service's request-denied failure");
      } catch (RequestDeniedException expected) {
        assertEquals("Permission denied for this project", expected.getMessage());
      }
    }
  }
}
