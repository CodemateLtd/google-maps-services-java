# Java Client for Google Maps Services

[![Build Status](https://travis-ci.org/googlemaps/google-maps-services-java.svg)](https://travis-ci.org/googlemaps/google-maps-services-java)
[![Coverage Status](https://img.shields.io/coveralls/googlemaps/google-maps-services-java.svg)](https://coveralls.io/r/googlemaps/google-maps-services-java)
[![Maven Central Version](http://img.shields.io/maven-central/v/com.google.maps/google-maps-services.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.google.maps%22%20a%3A%22google-maps-services%22)
[![Javadocs](https://www.javadoc.io/badge/com.google.maps/google-maps-services.svg)](https://www.javadoc.io/doc/com.google.maps/google-maps-services)
![GitHub contributors](https://img.shields.io/github/contributors/googlemaps/google-maps-services-java?color=green)
[![Stack Exchange questions](https://img.shields.io/stackexchange/stackoverflow/t/google-maps?color=orange&label=google-maps&logo=stackoverflow)](https://stackoverflow.com/questions/tagged/google-maps)

> [!IMPORTANT]
> **Legacy APIs End of Sale & Newer Client Libraries**
>
> Several APIs supported by this library are in [Legacy status](https://developers.google.com/maps/legacy):
> - **Places API (Legacy):** This product is in [Legacy status](https://developers.google.com/maps/legacy). As of October 2026, Places API (Legacy) can no longer be enabled for new Google Cloud projects. Existing projects remain fully supported. For new features and improved performance, [migrate to Places API (New)](https://developers.google.com/maps/documentation/places/web-service/legacy/migrate-overview).
> - **Directions API (Legacy):** This product is in [Legacy status](https://developers.google.com/maps/legacy). As of October 2026, Directions API (Legacy) can no longer be enabled for new Google Cloud projects. Existing projects remain fully supported. For new features and improved performance, [migrate to the Routes API](https://developers.google.com/maps/documentation/routes/migrate-routes).
> - **Distance Matrix API (Legacy):** This product is in [Legacy status](https://developers.google.com/maps/legacy). As of October 2026, Distance Matrix API (Legacy) can no longer be enabled for new Google Cloud projects. Existing projects remain fully supported. For new features and improved performance, [migrate to the Routes API](https://developers.google.com/maps/documentation/routes/migrate-routes).
>
> If you are looking for Java client libraries for our newer APIs, see the [Google Maps Platform APIs in the Cloud Client Libraries for Java](https://github.com/googleapis/google-cloud-java/tree/main) ([overview](https://docs.cloud.google.com/apis/docs/cloud-client-libraries), [releases](https://github.com/googleapis/google-cloud-java/releases?q=maps&expanded=true)):
> - [Places API (New)](https://github.com/googleapis/google-cloud-java/tree/main/java-maps-places)
> - [Routes API](https://github.com/googleapis/google-cloud-java/tree/main/java-maps-routing)
> - [Address Validation API](https://github.com/googleapis/google-cloud-java/tree/main/java-maps-addressvalidation)
> - [Datasets API](https://github.com/googleapis/google-cloud-java/tree/main/java-maps-mapsplatformdatasets)
>
> The new APIs will not be added to this client library.

## Description

Use Java? Want to [geocode][Geocoding API] something? Looking for [directions][Directions API]?
Maybe [matrices of directions][Distance Matrix API]? This library brings the [Google Maps API Web
Services] to your server-side Java application.

The Java Client for Google Maps Services is a Java Client library for the following Google Maps
APIs:

- [Elevation API]
- [Geocoding API]
- [Maps Static API]
- [Roads API]
- [Time Zone API]

As well as the following legacy APIs:

- [Directions API (Legacy)][Directions API]
- [Distance Matrix API (Legacy)][Distance Matrix API]
- [Places API (Legacy)][Places API]

## Requirements

- Java 1.8 or later
- A [Cloud project](https://developers.google.com/maps/get-started) with the relevant API(s) enabled
- An [API key](https://developers.google.com/maps/get-started) associated with the project above

### API Key Security

The Java Client for Google Maps Services is designed for use in both server and Android applications.
In either case, it is important to add [API key restrictions](https://developers.google.com/maps/api-security-best-practices?hl=it)
to improve the security of your API key. Additional security measures, such as hiding your key
from version control, should also be put in place to further improve the security of your API key.

You can refer to [API Security Best Practices](https://developers.google.com/maps/api-security-best-practices) to learn
more about this topic.

> [!NOTE]
> If you are using this library on Android, ensure that your application is using at least version 0.19.0 of this library so that API key restrictions can be enforced.

## Installation

You can add the library to your project via Maven or Gradle.

> [!NOTE]
> Since 0.1.18 there is now a dependency on [SLF4J](https://www.slf4j.org/). You need to add one of the adapter dependencies that makes sense for your logging setup. In the configuration samples below we are integrating [slf4j-nop](https://search.maven.org/#artifactdetails%7Corg.slf4j%7Cslf4j-nop%7C1.7.25%7Cjar), but there are others like [slf4j-log4j12](https://search.maven.org/#artifactdetails%7Corg.slf4j%7Cslf4j-log4j12%7C1.7.25%7Cjar) and [slf4j-jdk14](https://search.maven.org/#artifactdetails%7Corg.slf4j%7Cslf4j-jdk14%7C1.7.25%7Cjar) that will make more sense in other configurations. This will stop a warning message being emitted when you start using `google-maps-services`.

### Maven

```xml
<dependency>
  <groupId>com.google.maps</groupId>
  <artifactId>google-maps-services</artifactId>
  <version>(insert latest version)</version>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-simple</artifactId>
  <version>1.7.25</version>
</dependency>
```

### Gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.google.maps:google-maps-services:(insert latest version)'
    implementation 'org.slf4j:slf4j-simple:1.7.25'
}
```

You can find the latest version at the top of this README or by [searching
Maven Central](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22google-maps-services%22).

## Documentation

View the [javadoc](https://www.javadoc.io/doc/com.google.maps/google-maps-services).

Additional documentation for the included web services is available at
<https://developers.google.com/maps/>.

- [Directions API]
- [Distance Matrix API]
- [Elevation API]
- [Geocoding API]
- [Maps Static API]
- [Places API]
- [Roads API]
- [Time Zone API]

## Usage

This example uses the [Geocoding API] with an API key:

```java
GeoApiContext context = new GeoApiContext.Builder()
    .apiKey("AIza...")
    .build();
GeocodingResponse response =  GeocodingApi.geocode(context,
    "1600 Amphitheatre Parkway Mountain View, CA 94043").await();
Gson gson = new GsonBuilder().setPrettyPrinting().create();
System.out.println(gson.toJson(response.results[0].addressComponents));

// Invoke .shutdown() after your application is done making requests
context.shutdown();
```

**Note:** The `GeoApiContext` is designed to be a [Singleton](https://en.wikipedia.org/wiki/Singleton_pattern)
in your application. Please instantiate one on application startup, and continue to use it for the
life of your application. This will enable proper QPS enforcement across all of your requests.

At the end of the execution, call the `shutdown()` method of `GeoApiContext`,
otherwise the thread will remain instantiated in memory.

For more usage examples, check out [the tests](src/test/java/com/google/maps).

### Optional request metrics

Request metrics are **opt-in**; the default reporter is a no-op.
The bundled reporter uses global OpenCensus services.
[OpenCensus is archived](https://github.com/census-instrumentation/opencensus-java#readme);
this documents the existing integration, not a recommendation for new telemetry systems.

The library provides the OpenCensus API. Add its matching implementation at application runtime:

```groovy
dependencies {
    runtimeOnly 'io.opencensus:opencensus-impl:0.31.1'
}
```

Register the views once at application startup and select the reporter:

```java
import com.google.maps.GeoApiContext;
import com.google.maps.metrics.OpenCensusMetrics;
import com.google.maps.metrics.OpenCensusRequestMetricsReporter;

OpenCensusMetrics.registerAllViews();
GeoApiContext context = new GeoApiContext.Builder()
    .apiKey("AIza...")
    .requestMetricsReporter(new OpenCensusRequestMetricsReporter())
    .build();

// Shut down the context when the application has finished making requests.
context.shutdown();
```

View registration enables local collection, not export. To send metrics to a monitoring system,
add and configure your chosen [OpenCensus exporter](https://github.com/census-instrumentation/opencensus-java#how-to-setup-exporters)
before requests. Local snapshots may update asynchronously.

#### Metric contract

The four exported **view names** are:

| View name | Aggregation | Values and units |
| --- | --- | --- |
| `maps.googleapis.com/client/request_count` | Count | Completed requests (count, `1`). |
| `maps.googleapis.com/client/request_latency` | Distribution | Total elapsed request time, milliseconds (`ms`). |
| `maps.googleapis.com/client/network_latency` | Distribution | Accumulated instrumented attempt time, milliseconds (`ms`). |
| `maps.googleapis.com/client/retry_count` | Distribution | Retries per completed request, count (`1`), not a cumulative counter. |

See [`OpenCensusMetrics`](src/main/java/com/google/maps/metrics/OpenCensusMetrics.java)
for view and histogram definitions.

All four views group samples by the same three tags:

| Tag | Value |
| --- | --- |
| `request_name` | The configured API path, for example `/maps/api/geocode/json`, not the full URL, query or API key. |
| `http_code` | The decimal string of the reported HTTP status, for example `"200"` or `"500"`. OkHttp I/O failures reported without a response use `"0"`. |
| `api_status` | `""` on success (no exception); otherwise the exception's fully qualified Java class name, for example `com.google.maps.errors.InvalidRequestException`. This is **not** the raw Maps response status and success is not `"OK"`. |

Latencies use wall-clock milliseconds; network intervals can include client work and retry
backoff, not just wire time. Only the first completion records metrics. GAE does not support
callbacks and can miss failed-future completion metrics, so failure coverage differs from OkHttp.

Custom `RequestMetricsReporter` implementations must follow the
[`RequestMetrics`](src/main/java/com/google/maps/metrics/RequestMetrics.java) retry and first-completion contract.
The application owns reporter/exporter/provider resources, including required flush and shutdown
after requests stop. `GeoApiContext.shutdown()` (also called by `close()`) stops the request
handler only; it does not flush or shut down telemetry.

## Features

### Google App Engine Support

To use Google App Engine with this client library add the latest [App Engine dependency](https://mvnrepository.com/artifact/com.google.appengine/appengine-api-1.0-sdk)
to your `build.gradle` file:

```groovy
dependencies {
    implementation 'com.google.appengine:appengine-api-1.0-sdk:<latest version>'
}
```

You can then use this client library on Google App Engine with the following code change:

```java
new GeoApiContext.Builder(new GaeRequestHandler.Builder())
    .apiKey("AIza...")
    .build();
```

The `new GaeRequestHandler.Builder()` argument to `GeoApiContext.Builder`'s `requestHandlerBuilder`
tells the Java Client for Google Maps Services to utilise the appropriate calls for making HTTP
requests from Google App Engine, instead of the default [OkHttp3](https://square.github.io/okhttp/)
based strategy.

### Rate Limiting

Never sleep between requests again! By default, requests are sent at the expected rate limits for
each web service, typically 50 queries per second for free users. If you want to speed up or slow
down requests, you can do that too, using `new GeoApiContext.Builder().queryRateLimit(qps).build()`.
Note that you still need to manually handle the [delay between the initial request and successive pages](https://developers.google.com/places/web-service/search#PlaceSearchPaging) when you're paging through multiple result sets.

### Retry on Failure

Automatically retry when intermittent failures occur. That is, when any of the retriable 5xx errors
are returned from the API.

To alter or disable automatic retries, see these methods in `GeoApiContext`:

- `.disableRetries()`
- `.maxRetries()`
- `.retryTimeout()`
- `.setIfExceptionIsAllowedToRetry()`

### POJOs

Native objects for each of the API responses.

### Asynchronous or synchronous -- you choose

All requests support synchronous or asynchronous calling style.

```java
GeocodingApiRequest req = GeocodingApi.newRequest(context).address("Sydney");

// Synchronous
try {
    req.await();
    // Handle successful request.
} catch (Exception e) {
    // Handle error
}

req.awaitIgnoreError(); // No checked exception.

// Async
req.setCallback(new PendingResult.Callback<GeocodingResponse>() {
  @Override
  public void onResult(GeocodingResponse result) {
    // Handle successful request.
  }

  @Override
  public void onFailure(Throwable e) {
    // Handle error.
  }
});
```

## Building the Project

> [!NOTE]
> You will need an API key to run the tests.

```bash
# Compile and package the project
$ ./gradlew jar

# Run the tests
$ ./gradlew test
```

## Terms

This library uses Google Maps Platform services. Use of Google Maps Platform services through this library is subject to the [Google Maps Platform Terms of Service](https://cloud.google.com/maps-platform/terms).

This library is not a Google Maps Platform Core Service. Therefore, the Google Maps Platform Terms of Service (e.g. Technical Support Services, Service Level Agreements, and Deprecation Policy) do not apply to the code in this library.

## Support

This library is offered via an open source license. It is not governed by the [Google Maps Platform Support Technical Support Services Guidelines](https://cloud.google.com/maps-platform/terms/tssg), the [SLA](https://cloud.google.com/maps-platform/terms/sla), or the [Deprecation Policy](https://cloud.google.com/maps-platform/terms) (however, any Google Maps Platform services used by the library remain subject to the Google Maps Platform Terms of Service).

This library adheres to [semantic versioning](https://semver.org/) to indicate when backwards-incompatible changes are introduced. Accordingly, while the library is in version 0.x, backwards-incompatible changes may be introduced at any time.

If you find a bug, or have a feature request, please file an [issue][issues] on GitHub. If you would like to get answers to technical questions from other Google Maps Platform developers, ask through one of our [developer community channels](https://developers.google.com/maps/developer-community). If you'd like to contribute, please check the [Contributing guide][contrib] in the GitHub repository.

[contrib]: https://github.com/googlemaps/google-maps-services-java/blob/main/CONTRIB.md
[Directions API]: https://developers.google.com/maps/documentation/directions
[Distance Matrix API]: https://developers.google.com/maps/documentation/distancematrix
[Elevation API]: https://developers.google.com/maps/documentation/elevation
[Geocoding API]: https://developers.google.com/maps/documentation/geocoding
[Google Maps API Web Services]: https://developers.google.com/maps/apis-by-platform#web_service_apis
[issues]: https://github.com/googlemaps/google-maps-services-java/issues
[Maps Static API]: https://developers.google.com/maps/documentation/maps-static/
[Places API]: https://developers.google.com/places/web-service/
[Time Zone API]: https://developers.google.com/maps/documentation/timezone
[Roads API]: https://developers.google.com/maps/documentation/roads
