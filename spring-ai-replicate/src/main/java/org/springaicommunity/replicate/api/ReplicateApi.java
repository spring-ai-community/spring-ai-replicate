/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.replicate.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for the Replicate Predictions API
 *
 * @author Rene Maierhofer
 * @since 1.0.0
 */
public final class ReplicateApi {

	private static final Logger logger = LoggerFactory.getLogger(ReplicateApi.class);

	private static final String DEFAULT_BASE_URL = "https://api.replicate.com/v1";

	private static final String PREDICTIONS_PATH = "/predictions";

	private final RestClient restClient;

	private final WebClient webClient;

	private final RetryTemplate retryTemplate;

	public static final String PROVIDER_NAME = "replicate";

	public static final int DEFAULT_MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024; // 16MB

	private ReplicateApi(String baseUrl, ApiKey apiKey, RestClient.Builder restClientBuilder,
			WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler, int retryMaxAttempts,
			Duration retryFixedBackoff, int maxInMemorySize) {
		Consumer<HttpHeaders> headers = h -> {
			h.setContentType(MediaType.APPLICATION_JSON);
			h.setBearerAuth(apiKey.getValue());
		};

		this.restClient = restClientBuilder.baseUrl(baseUrl)
			.defaultHeaders(headers)
			.defaultStatusHandler(responseErrorHandler)
			.build();

		// WebClient needs increased buffer size for handling large files (images, videos, etc.)
		ExchangeStrategies strategies = ExchangeStrategies.builder()
			.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize))
			.build();

		this.webClient = webClientBuilder.clone()
			.baseUrl(baseUrl)
			.defaultHeaders(headers)
			.exchangeStrategies(strategies)
			.build();

		this.retryTemplate = RetryTemplate.builder()
			.retryOn(ReplicatePredictionNotFinishedException.class)
			.maxAttempts(retryMaxAttempts)
			.fixedBackoff(retryFixedBackoff.toMillis())
			.withListener(new RetryListener() {
				@Override
				public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
						Throwable throwable) {
					logger.debug("Polling Replicate Prediction: {}/{} attempts.", context.getRetryCount(),
							retryMaxAttempts);
				}
			})
			.build();
	}

	public static Builder builder() {
		return new Builder();
	}


    /**
     * Creates a prediction with optional headers and waits for it to complete by polling
     * the status. Uses the configured retry template.
     * For official models: Pass modelName and leave version null in request.
     * For community models: Pass null for modelName and include version in request.
     *
     * @param modelName The model name in format "owner/name" (for official models, pass null for community models)
     * @param request The prediction request (must include version field if modelName is null)
     * @param preferWait Optional wait time for sync mode. Accepts: "wait" (wait as long as it takes, up to 60s max), "5" or "wait=5" (wait up to 5 seconds, 1-60 range).
     * @param cancelAfter Optional duration after which to auto-cancel the prediction
     * @return The completed prediction response
     */
    public PredictionResponse createPredictionAndWait(String modelName, PredictionRequest request, String preferWait,
                                                      String cancelAfter) {
        PredictionResponse prediction = createPrediction(modelName, request, preferWait, cancelAfter);
        if (prediction == null || prediction.id == null) {
            throw new ReplicatePredictionException("PredictionRequest did not return a valid response.");
        }
        return waitForCompletion(prediction.id());
    }


    /**
     * Creates a Prediction with optional headers. Routes to the appropriate endpoint based on whether
     * model name or version is provided.
     * For official models: Pass modelName (e.g., "google/gemini-2.5-flash") and leave version null in request.
     * Routes to /models/{model}/predictions
     *
     * For community models: Pass null for modelName and include version in request.
     * Routes to /predictions with version in body.
     *
     * @param modelName The model name in format "owner/name" (for official models, pass null for community models)
     * @param request The prediction request
     * @param preferWait Optional wait time for sync mode. Accepts: "wait" (wait as long as it takes, up to 60s max), "5" or "wait=5" (wait up to 5 seconds, 1-60 range).
     * @param cancelAfter Optional duration after which to auto-cancel the prediction. Examples: "5m", "1h30m", "90s", "30" (defaults to seconds). Minimum: 5 seconds
     * @return The prediction response
     */
    public PredictionResponse createPrediction(String modelName, PredictionRequest request, String preferWait,
                                               String cancelAfter) {
        Assert.notNull(request, "Request must not be null");

        String uri = buildUri(modelName, request);
        RestClient.RequestBodySpec requestSpec = this.restClient.post().uri(uri);

        // Add Prefer header for sync mode if specified
        if (preferWait != null && !preferWait.isEmpty()) {
            // - "wait" -> "wait" (wait as long as it takes, up to 60s max)
            // - "5" -> "wait=5" (wait up to 5 seconds)
            // - "wait=5" -> "wait=5" (already formatted)
            String preferValue;
            if ("wait".equals(preferWait)) {
                preferValue = "wait";
            } else if (preferWait.startsWith("wait=")) {
                preferValue = preferWait;
            } else {
                preferValue = "wait=" + preferWait;
            }
            requestSpec = requestSpec.header("Prefer", preferValue);
        }

        if (cancelAfter != null && !cancelAfter.isEmpty()) {
            requestSpec = requestSpec.header("Cancel-After", cancelAfter);
        }

        ResponseEntity<PredictionResponse> response = requestSpec.body(request).retrieve().toEntity(PredictionResponse.class);
        return response.getBody();
    }

    /**
     * Replicate has two APIs: One for "official" Models and one for community Models
     * Official Models have a unique URL and are always the latest Version
     * Community Models use a shared URL and needs a Version Parameter in the Request Body.
     * @param modelName Name for official Models
     * @param request Request that may contain a Version Parameter for community Models
     * @return URL
     */
    private static String buildUri(String modelName, PredictionRequest request) {
        boolean hasModel = modelName != null && !modelName.isEmpty();
        boolean hasVersion = request.version() != null && !request.version().isEmpty();

        if (!hasModel && !hasVersion) {
            throw new IllegalArgumentException("Either model name or version must be specified");
        }
        if (hasModel && hasVersion) {
            throw new IllegalArgumentException("Cannot specify both model name and version");
        }
        String uri;
        if (hasModel) {
            // Official model: /models/{model}/predictions
            uri = "/models/" + modelName + PREDICTIONS_PATH;
        } else {
            // Community model with version: /predictions (version goes in body)
            uri = PREDICTIONS_PATH;
        }
        return uri;
    }

    /**
	 * Retrieves the current status of the Prediction
	 * @param predictionId The prediction ID
	 * @return The prediction response
	 */
	public PredictionResponse getPrediction(String predictionId) {
		Assert.hasText(predictionId, "Prediction ID must not be empty");

		return this.restClient.get()
			.uri(PREDICTIONS_PATH + "/{id}", predictionId)
			.retrieve()
			.body(PredictionResponse.class);
	}

	/**
	 * Cancels a running prediction.
	 * @param predictionId The prediction ID to cancel
	 * @return The prediction response with canceled status
	 */
	public PredictionResponse cancelPrediction(String predictionId) {
		Assert.hasText(predictionId, "Prediction ID must not be empty");

		return this.restClient.post()
			.uri(PREDICTIONS_PATH + "/{id}/cancel", predictionId)
			.retrieve()
			.body(PredictionResponse.class);
	}


	/**
	 * Waits for the completed Prediction and returns the final Response.
	 * @param predictionId id of the prediction
	 * @return the final PredictionResponse
	 */
	public PredictionResponse waitForCompletion(String predictionId) {
		Assert.hasText(predictionId, "Prediction ID must not be empty");
		return this.retryTemplate.execute(context -> pollStatusFromReplicate(predictionId));
	}

	/**
	 * Polls the prediction status from replicate.
	 * @param predictionId the Prediction's id
	 * @return the final Prediction Response
	 */
	private PredictionResponse pollStatusFromReplicate(String predictionId) {
		PredictionResponse prediction = getPrediction(predictionId);
		if (prediction == null || prediction.id == null) {
			throw new ReplicatePredictionException("Polling for Prediction did not return a valid response.");
		}
		PredictionStatus status = prediction.status();
		if (status == PredictionStatus.SUCCEEDED) {
			return prediction;
		}
		else if (status == PredictionStatus.PROCESSING || status == PredictionStatus.STARTING) {
			throw new ReplicatePredictionNotFinishedException("Prediction not finished yet.");
		}
		else if (status == PredictionStatus.FAILED) {
			String error = prediction.error() != null ? prediction.error() : "Unknown error";
			throw new ReplicatePredictionException("Prediction failed: " + error);
		}
		else if (status == PredictionStatus.CANCELED || status == PredictionStatus.ABORTED) {
			throw new ReplicatePredictionException("Prediction was canceled");
		}
		throw new ReplicatePredictionException("Unknown Replicate Prediction Status");
	}

	/**
	 * Uploads a file to Replicate for usage in a request. <a href=
	 * "https://replicate.com/docs/topics/predictions/create-a-prediction#file-upload">Replicate
	 * Files API</a>
	 * @param fileResource The file to upload
	 * @param filename The filename to use for the uploaded file
	 * @return Upload response containing the URL to later send with a request.
	 */
	public FileUploadResponse uploadFile(Resource fileResource, String filename) {
		Assert.notNull(fileResource, "File resource must not be null");
		Assert.hasText(filename, "Filename must not be empty");

		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder.part("content", fileResource)
			.headers(h -> h
				.setContentDisposition(ContentDisposition.formData().name("content").filename(filename).build()))
			.contentType(MediaType.APPLICATION_OCTET_STREAM);

		return this.webClient.post()
			.uri("/files")
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.bodyValue(builder.build())
			.retrieve()
			.bodyToMono(FileUploadResponse.class)
			.block();
	}

	/**
	 * Creates a streaming prediction response. Replicate uses SSE for Streaming.
	 * <a href="https://replicate.com/docs/topics/predictions/streaming">Replicate
	 * Docs</a>
	 * For official models: Pass modelName and leave version null in request.
	 * For community models: Pass null for modelName and include version in request.
     *
	 * @param modelName The model name in format "owner/name" (for official models, pass null for community models)
	 * @param request The prediction request (must have stream=true, and include version field if modelName is null)
	 * @return A Flux stream of prediction response events with incremental output
	 */
	public Flux<PredictionResponse> createPredictionStream(String modelName, PredictionRequest request) {
		PredictionResponse initialResponse = createPrediction(modelName, request, null, null);
		if (initialResponse == null || initialResponse.urls() == null || initialResponse.urls().stream() == null) {
			logger.error("No stream URL in response: {}", initialResponse);
			return Flux.error(new ReplicatePredictionException("No stream URL returned from prediction"));
		}
		String streamUrl = initialResponse.urls().stream();
		ParameterizedTypeReference<ServerSentEvent<String>> typeRef = new ParameterizedTypeReference<>() {
		};

		return this.webClient.get()
			.uri(streamUrl)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.retrieve()
			.bodyToFlux(typeRef)
			.handle((event, sink) -> {
				String eventType = event.event();
				if ("error".equals(eventType)) {
					String errorMessage = event.data() != null ? event.data() : "Unknown error";
					sink.error(new ReplicatePredictionException("Streaming error: " + errorMessage));
					return;
				}
				if ("done".equals(eventType)) {
					sink.complete();
					return;
				}
				if ("output".equals(eventType)) {
					String dataContent = event.data() != null ? event.data() : "";
					PredictionResponse response = new PredictionResponse(initialResponse.id(), initialResponse.model(),
							initialResponse.version(), PredictionStatus.PROCESSING, initialResponse.input(),
                            dataContent, // The output chunk
                            null, null, null, initialResponse.urls(), initialResponse.createdAt(),
                            initialResponse.startedAt(), null, initialResponse.dataRemoved(), initialResponse.source(), initialResponse.deployment(), initialResponse.deadline());

					sink.next(response);
				}
			});
	}

	/**
	 * Request to create a prediction. Maps to OpenAPI schema
	 * {@code schemas_version_prediction_request}.
	 * @param version Model version identifier. Can be in format: {@code owner/name},
	 * {@code owner/name:version_id}, or just {@code version_id}. Required when using
	 * {@code POST /predictions} endpoint
	 * @param input The input parameters for the model (required)
	 * @param webhook Optional webhook URL for async notifications
	 * @param webhookEventsFilter Optional list of webhook events to subscribe to. Valid
	 * values: "start", "output", "logs", "completed"
	 * @param stream Optional flag to request streaming output (deprecated in favor of SSE
	 * URLs in response)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PredictionRequest(@JsonProperty("version") String version,
			@JsonProperty("input") Map<String, Object> input, @JsonProperty("webhook") String webhook,
			@JsonProperty("webhook_events_filter") List<String> webhookEventsFilter,
			@JsonProperty("stream") Boolean stream) {
	}

	/**
	 * Response from Replicate prediction API.
	 * @param id Unique identifier for the prediction
	 * @param model The model identifier in format {@code owner/name}
	 * @param version The version ID of the model
	 * @param status Current status of the prediction
	 * @param input The input parameters that were provided
	 * @param output The prediction output (can be any JSON-serializable value)
	 * @param error Error message if the prediction failed
	 * @param logs Log output from the model
	 * @param metrics Performance metrics including timing and token counts
	 * @param urls URLs for interacting with the prediction
	 * @param createdAt Timestamp when the prediction was created (ISO 8601)
	 * @param startedAt Timestamp when the prediction started processing (ISO 8601)
	 * @param completedAt Timestamp when the prediction completed (ISO 8601)
	 * @param dataRemoved Whether the input/output data has been deleted (after ~1 hour)
	 * @param source How the prediction was created ("web" or "api")
	 * @param deployment Name of the deployment that created the prediction
	 * @param deadline Absolute time when prediction will be auto-canceled (ISO 8601)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PredictionResponse(@JsonProperty("id") String id, @JsonProperty("model") String model,
			@JsonProperty("version") String version, @JsonProperty("status") PredictionStatus status,
			@JsonProperty("input") Map<String, Object> input, @JsonProperty("output") Object output,
			@JsonProperty("error") String error, @JsonProperty("logs") String logs,
			@JsonProperty("metrics") Metrics metrics, @JsonProperty("urls") Urls urls,
			@JsonProperty("created_at") String createdAt, @JsonProperty("started_at") String startedAt,
			@JsonProperty("completed_at") String completedAt, @JsonProperty("data_removed") Boolean dataRemoved,
			@JsonProperty("source") String source, @JsonProperty("deployment") String deployment,
			@JsonProperty("deadline") String deadline) {
	}

	/**
	 * Prediction status.
	 */
	public enum PredictionStatus {

		@JsonProperty("starting")
		STARTING,

		@JsonProperty("processing")
		PROCESSING,

		@JsonProperty("succeeded")
		SUCCEEDED,

		@JsonProperty("failed")
		FAILED,

		@JsonProperty("canceled")
		CANCELED,

		@JsonProperty("aborted")
		ABORTED

	}

	/**
	 * Metrics from a prediction including token counts and timing.
	 * @param predictTime The amount of CPU or GPU time, in seconds, that the prediction
	 * used while running
	 * @param totalTime The total time, in seconds, that the prediction took to complete
	 * (includes queuing time)
	 * @param inputTokenCount Number of input tokens processed
	 * @param outputTokenCount Number of output tokens generated
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Metrics(@JsonProperty("predict_time") Double predictTime,
			@JsonProperty("total_time") Double totalTime, @JsonProperty("input_token_count") Integer inputTokenCount,
			@JsonProperty("output_token_count") Integer outputTokenCount) {
	}

	/**
	 * URLs for interacting with a prediction.
	 * @param get API URL to retrieve the prediction state
	 * @param cancel API URL to cancel the prediction
	 * @param stream SSE URL for streaming output (if model supports streaming)
	 * @param web Browser URL to view the prediction on Replicate's website
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Urls(@JsonProperty("get") String get, @JsonProperty("cancel") String cancel,
			@JsonProperty("stream") String stream, @JsonProperty("web") String web) {
	}

	/**
	 * Response from Replicate file upload API.
	 * @param id Unique identifier for the file resource
	 * @param contentType The content/MIME type of the file
	 * @param size The length of the file in bytes
	 * @param checksums Dictionary of checksums for the file keyed by algorithm name
	 * @param metadata User-provided metadata from when the file was created
	 * @param urls URLs associated with the file resource
	 * @param createdAt Timestamp when the file was created (ISO 8601)
	 * @param expiresAt Timestamp when the file expires (ISO 8601)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FileUploadResponse(@JsonProperty("id") String id, @JsonProperty("content_type") String contentType,
			@JsonProperty("size") Integer size, @JsonProperty("checksums") Checksums checksums,
			@JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("urls") FileUrls urls,
			@JsonProperty("created_at") String createdAt, @JsonProperty("expires_at") String expiresAt) {
	}

	/**
	 * Checksums for a file resource.
	 * @param sha256 SHA256 checksum of the file
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Checksums(@JsonProperty("sha256") String sha256) {
	}

	/**
	 * URLs for accessing an uploaded file.
	 * @param get URL to download the file
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FileUrls(@JsonProperty("get") String get) {
	}

	/**
	 * Builder to Construct a {@link ReplicateApi} instance
	 */
	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;

		private ApiKey apiKey;

		private RestClient.Builder restClientBuilder = RestClient.builder();

		private WebClient.Builder webClientBuilder = WebClient.builder();

		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;

		private Duration retryFixedBackoff = Duration.ofMillis(5000);

		private int retryMaxAttempts = 60;

		private int maxInMemorySize = DEFAULT_MAX_IN_MEMORY_SIZE;

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be empty");
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder apiKey(String apiKey) {
			Assert.notNull(apiKey, "ApiKey cannot be null");
			this.apiKey = new SimpleApiKey(apiKey);
			return this;
		}

		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder cannot be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "webClientBuilder cannot be null");
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			Assert.notNull(responseErrorHandler, "responseErrorHandler cannot be null");
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		public Builder retryFixedBackoff(Duration retryFixedBackoff) {
			Assert.notNull(retryFixedBackoff, "retryFixedBackoff cannot be null");
			this.retryFixedBackoff = retryFixedBackoff;
			return this;
		}

		public Builder retryMaxAttempts(int retryMaxAttempts) {
			Assert.isTrue(retryMaxAttempts > 0, "retryMaxAttempts must be positive");
			this.retryMaxAttempts = retryMaxAttempts;
			return this;
		}

		public Builder maxInMemorySize(int maxInMemorySize) {
			Assert.isTrue(maxInMemorySize > 0, "maxInMemorySize must be positive");
			this.maxInMemorySize = maxInMemorySize;
			return this;
		}

		public ReplicateApi build() {
			Assert.notNull(this.apiKey, "cannot construct instance without apiKey");
			return new ReplicateApi(this.baseUrl, this.apiKey, this.restClientBuilder, this.webClientBuilder,
					this.responseErrorHandler, this.retryMaxAttempts, this.retryFixedBackoff, this.maxInMemorySize);
		}

	}

	/**
	 * Exception thrown when a Replicate prediction fails or times out.
	 */
	public static class ReplicatePredictionException extends RuntimeException {

		public ReplicatePredictionException(String message) {
			super(message);
		}

	}

	/**
	 * Exception thrown when a Replicate prediction has not finished yet. Used for
	 * RetryTemplate.
	 */
	public static class ReplicatePredictionNotFinishedException extends RuntimeException {

		public ReplicatePredictionNotFinishedException(String message) {
			super(message);
		}

	}

}
