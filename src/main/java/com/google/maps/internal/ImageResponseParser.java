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

import com.google.maps.ImageResult;
import com.google.maps.errors.ApiException;
import java.io.IOException;

/** Shared image response classification, after transport retry eligibility has been checked. */
final class ImageResponseParser {
  private ImageResponseParser() {}

  static ImageResult parse(int statusCode, String contentType, byte[] bytes)
      throws ApiException, IOException {
    if (statusCode >= 200
        && statusCode < 300
        && contentType != null
        && contentType.regionMatches(true, 0, "image/", 0, 6)
        && contentType.length() > 6
        && bytes != null
        && bytes.length > 0) {
      return new ImageResult(contentType, bytes);
    }

    ApiException error = ApiErrorParser.parse(bytes);
    if (error != null) {
      throw error;
    }
    throw new IOException(
        "Invalid image response: HTTP " + statusCode + "; expected a nonempty image body");
  }
}
