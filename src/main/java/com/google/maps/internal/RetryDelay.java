/*
 * Copyright 2026 Google LLC. All rights reserved.
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

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Shared HTTP retry pacing; all durations are milliseconds. */
final class RetryDelay {
  private static final String[] DATE_FORMATS = {
    "EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"
  };
  private static final Pattern[] DATE_SHAPES = {
    Pattern.compile("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9:]{8} GMT"),
    Pattern.compile("[A-Z][a-z]+, [0-9]{2}-[A-Z][a-z]{2}-[0-9]{2} [0-9:]{8} GMT"),
    Pattern.compile("[A-Z][a-z]{2} [A-Z][a-z]{2} ( [0-9]|[0-9]{2}) [0-9:]{8} [0-9]{4}")
  };
  static final Timing SYSTEM = new Timing();

  // Package-private, per-result injection keeps timing tests independent of global state.
  static class Timing {
    long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    double random() {
      return Math.random();
    }

    void sleep(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  private RetryDelay() {}

  /** Returns -1 when the next wait cannot fit the count or cumulative-delay budget. */
  static long next(
      String retryAfter, int retries, Integer maxRetries, long timeout, long slept, Timing timing) {
    if ((maxRetries != null && retries >= maxRetries) || slept >= timeout) {
      return -1;
    }
    long delay = retryAfter(retryAfter, timing);
    if (delay <= 0) {
      delay = (long) (500 * Math.pow(1.5, retries) * (timing.random() + 0.5));
    }
    return delay > timeout - slept ? -1 : delay;
  }

  static long add(long slept, long delay) {
    return delay > Long.MAX_VALUE - slept ? Long.MAX_VALUE : slept + delay;
  }

  private static long retryAfter(String value, Timing timing) {
    if (value == null) {
      return -1;
    }
    value = value.trim();
    if (value.isEmpty()) {
      return -1;
    }
    boolean digits = true;
    long seconds = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        digits = false;
        break;
      }
      int digit = c - '0';
      seconds = seconds > (Long.MAX_VALUE - digit) / 10 ? Long.MAX_VALUE : seconds * 10 + digit;
    }
    if (digits) {
      return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
    }

    // RFC 9110 requires recipients to accept IMF-fixdate and both obsolete HTTP-date forms.
    for (int i = 0; i < DATE_FORMATS.length; i++) {
      if (!DATE_SHAPES[i].matcher(value).matches()) {
        continue;
      }
      long now = timing.currentTimeMillis();
      SimpleDateFormat parser = new SimpleDateFormat(DATE_FORMATS[i], Locale.US);
      parser.setTimeZone(TimeZone.getTimeZone("GMT"));
      parser.setLenient(false);
      if (i == 1) {
        Calendar start = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
        start.setTimeInMillis(now);
        start.add(Calendar.YEAR, -50);
        parser.set2DigitYearStart(start.getTime());
      }
      ParsePosition position = new ParsePosition(0);
      Date date = parser.parse(value, position);
      if (date != null && position.getIndex() == value.length()) {
        long timestamp = date.getTime();
        return timestamp <= now ? 0 : timestamp - now;
      }
    }
    return -1;
  }
}
