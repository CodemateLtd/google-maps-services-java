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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.maps.errors.ApiException;

/** Decodes classic and nested service errors without treating arbitrary bodies as API errors. */
final class ApiErrorParser {
  private ApiErrorParser() {}

  /** Returns the service error, or null when the body does not describe an API failure. */
  static ApiException parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    try {
      JsonElement json = JsonParser.parseString(new String(bytes, UTF_8));
      if (!json.isJsonObject()) {
        return null;
      }
      JsonObject object = json.getAsJsonObject();
      JsonElement error = object.get("error");
      String status;
      String message;
      if (error != null && error.isJsonObject()) {
        JsonObject details = error.getAsJsonObject();
        status = string(details, "status");
        message = string(details, "message");
        if (status == null || status.isEmpty()) {
          JsonElement errors = details.get("errors");
          if (errors != null && errors.isJsonArray()) {
            JsonArray reasons = errors.getAsJsonArray();
            for (int i = 0; i < reasons.size(); i++) {
              JsonElement entry = reasons.get(i);
              if (entry.isJsonObject()) {
                String reason = string(entry.getAsJsonObject(), "reason");
                if (reason != null) {
                  status = reason;
                }
              }
            }
          }
        }
      } else {
        status = string(object, "status");
        message = string(object, "error_message");
        if (message == null) {
          message = string(object, "errorMessage");
        }
      }
      return status == null || status.isEmpty() ? null : ApiException.from(status, message);
    } catch (JsonParseException ignored) {
      return null;
    }
  }

  private static String string(JsonObject object, String name) {
    JsonElement value = object.get(name);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : null;
  }
}
