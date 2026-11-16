/*
 * Copyright 2026 Google LLC. All rights reserved.
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

package com.google.maps.model;

import java.io.Serializable;

/**
 * Service-provided Time Zone data for a requested location and timestamp.
 *
 * <p>Offsets are in seconds and are not recomputed using Java's time-zone rules. Optional fields
 * that are absent from the response remain {@code null}, distinct from a returned zero offset.
 *
 * <p>See the <a
 * href="https://developers.google.com/maps/documentation/timezone/requests-timezone#TimeZoneResponse">
 * Time Zone response documentation</a>.
 */
public class TimeZoneResult implements Serializable {

  private static final long serialVersionUID = 1L;

  /** The service's time-zone ID, or {@code null} if absent. */
  public String timeZoneId;

  /** The time-zone name, localized when requested, or {@code null} if absent. */
  public String timeZoneName;

  /** The UTC offset in seconds, excluding daylight saving, or {@code null} if absent. */
  public Double rawOffset;

  /**
   * The daylight-saving offset in seconds at the requested timestamp, or {@code null} if absent.
   */
  public Double dstOffset;
}
