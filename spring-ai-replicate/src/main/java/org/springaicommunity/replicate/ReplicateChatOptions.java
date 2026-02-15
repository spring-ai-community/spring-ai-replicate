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

package org.springaicommunity.replicate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Base options for Replicate models. Contains common fields that apply to all Replicate
 * models regardless of type (chat, image, audio, etc.).
 *
 * @author Rene Maierhofer
 * @since 1.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplicateChatOptions implements ChatOptions {

	@JsonProperty("model")
	protected String model;

	@JsonProperty("version")
	protected String version;

	@JsonProperty("input")
	protected Map<String, Object> input = new HashMap<>();

	@JsonProperty("webhook")
	protected String webhook;

	@JsonProperty("webhook_events_filter")
	protected List<String> webhookEventsFilter;

	@JsonProperty("prefer_wait")
	protected String preferWait;

	@JsonProperty("cancel_after")
	protected String cancelAfter;

	public ReplicateChatOptions() {
	}

	protected ReplicateChatOptions(Builder builder) {
		this.model = builder.model;
		this.version = builder.version;
		this.input = builder.input != null ? new HashMap<>(builder.input) : new HashMap<>();
		this.webhook = builder.webhook;
		this.webhookEventsFilter = builder.webhookEventsFilter;
		this.preferWait = builder.preferWait;
		this.cancelAfter = builder.cancelAfter;
	}

	/**
	 * Add a custom parameter to the model input
	 */
	public ReplicateChatOptions withParameter(String key, Object value) {
		this.input.put(key, value);
		return this;
	}

	/**
	 * Add multiple parameters to the model input
	 */
	public ReplicateChatOptions withParameters(Map<String, Object> parameters) {
		this.input.putAll(parameters);
		return this;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Create a new ReplicateOptions from existing options
	 */
	public static ReplicateChatOptions fromOptions(ReplicateChatOptions fromOptions) {
		return builder().model(fromOptions.getModel())
			.version(fromOptions.getVersion())
			.input(new HashMap<>(fromOptions.getInput()))
			.webhook(fromOptions.getWebhook())
			.webhookEventsFilter(fromOptions.getWebhookEventsFilter())
			.preferWait(fromOptions.getPreferWait())
			.cancelAfter(fromOptions.getCancelAfter())
			.build();
	}

	public String getModel() {
		return this.model;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Double getFrequencyPenalty() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Integer getMaxTokens() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Double getPresencePenalty() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public List<String> getStopSequences() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Double getTemperature() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Integer getTopK() {
		return null;
	}

	@Override
	@com.fasterxml.jackson.annotation.JsonIgnore
	public Double getTopP() {
		return null;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends ChatOptions> T copy() {
		return (T) fromOptions(this);
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getVersion() {
		return this.version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public Map<String, Object> getInput() {
		return this.input;
	}

	public void setInput(Map<String, Object> input) {
		this.input = ReplicateOptionsUtils.convertMapValues(input);
	}

	public String getWebhook() {
		return this.webhook;
	}

	public void setWebhook(String webhook) {
		this.webhook = webhook;
	}

	public List<String> getWebhookEventsFilter() {
		return this.webhookEventsFilter;
	}

	public void setWebhookEventsFilter(List<String> webhookEventsFilter) {
		this.webhookEventsFilter = webhookEventsFilter;
	}

	public String getPreferWait() {
		return this.preferWait;
	}

	public void setPreferWait(String preferWait) {
		this.preferWait = preferWait;
	}

	public String getCancelAfter() {
		return this.cancelAfter;
	}

	public void setCancelAfter(String cancelAfter) {
		this.cancelAfter = cancelAfter;
	}

	public static class Builder {

		protected String model;

		protected String version;

		protected Map<String, Object> input = new HashMap<>();

		protected String webhook;

		protected List<String> webhookEventsFilter;

		protected String preferWait;

		protected String cancelAfter;

		protected Builder() {
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder version(String version) {
			this.version = version;
			return this;
		}

		private Builder input(Map<String, Object> input) {
			this.input = input;
			return this;
		}

		public Builder withParameter(String key, Object value) {
			this.input.put(key, value);
			return this;
		}

		public Builder withParameters(Map<String, Object> params) {
			this.input.putAll(params);
			return this;
		}

		public Builder webhook(String webhook) {
			this.webhook = webhook;
			return this;
		}

		public Builder webhookEventsFilter(List<String> webhookEventsFilter) {
			this.webhookEventsFilter = webhookEventsFilter;
			return this;
		}

		public Builder preferWait(String preferWait) {
			this.preferWait = preferWait;
			return this;
		}

		public Builder cancelAfter(String cancelAfter) {
			this.cancelAfter = cancelAfter;
			return this;
		}

		public ReplicateChatOptions build() {
			return new ReplicateChatOptions(this);
		}

	}

}
