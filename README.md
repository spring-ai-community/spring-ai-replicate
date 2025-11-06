# Spring AI Replicate Integration

This project provides Spring AI integration for [Replicate.com](https://replicate.com), enabling access to hundreds of AI models for text generation, image/video/audio generation, embeddings, classification, and more.

## Overview

Replicate hosts a wide variety of AI models with varying input/output schemas. Rather than creating hundreds of model-specific implementations, this integration provides four flexible adapter models that can work with any Replicate model:

- **ReplicateChatModel** - For conversational LLMs (Claude, GPT, Llama, Grok, etc.)
- **ReplicateMediaModel** - For media generation models (images, video, audio)
- **ReplicateStringModel** - For models that return string outputs (classifiers, detectors)
- **ReplicateStructuredModel** - For models with structured outputs (embeddings, JSON responses)

## Project Structure
- **spring-ai-replicate** -  API, Models and Options
- **spring-ai-replicate-spring-boot-autoconfigure** - Autoconfiguration
- **spring-ai-replicate-spring-boot-starter** - Starter Dependency Package

## Usage
Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.springaicommunity</groupId>
    <artifactId>spring-ai-replicate-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

and configure one or more models via configuration properties.
Everything you set after **.options** will be passed with the corresponding key and value.
If you set 

`spring.ai.replicate.chat.options.input.temperature=0.7`

the Model will receive "temperature=0.7".

```properties
# API Token to be used across all models
spring.ai.replicate.api-token=REPLICATE_TOKEN

# Chat model
spring.ai.replicate.chat.options.model=meta/meta-llama-3-8b-instruct
spring.ai.replicate.chat.options.input.temperature=0.7
spring.ai.replicate.chat.options.input.max_tokens=500

# Media model
spring.ai.replicate.media.options.model=black-forest-labs/flux-schnell
spring.ai.replicate.media.options.input.num_outputs=2

# String model
spring.ai.replicate.string.options.model=falcons-ai/nsfw_image_detection

# Structured model
spring.ai.replicate.structured.options.model=openai/clip
```

All model beans will be automatically created and available for injection.

## Spring Boot Auto-Configuration

You can then inject and use a Models like this:

```java
@RestController
class ChatController {
    @Autowired
    private ReplicateChatModel chatModel;

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatModel.call(message);
    }
}
```

## Set different Parameters per Request
Of course you can set the parameters and options on a per-request-basis.
Simply create a ReplicateOptions instance and pass it with you request:

```java
@Autowired
private ReplicateMediaModel mediaModel;

public void generateImages() {
    ReplicateOptions options = ReplicateOptions.builder()
            .model("black-forest-labs/flux-schnell")
            .withParameter("prompt", "a cat sitting on a laptop")
            .withParameter("num_outputs", 2)
            .build();

    MediaResponse response = mediaModel.generate(options);

    List<String> imageUrls = response.getUris();
    imageUrls.forEach(url -> System.out.println("Image URL: " + url));
}
```

**The only model that uses different Options is a ChatModel. Here we use ReplicateChatOptions instead
to better integrate into spring-ai-conventions**

```java
public void chatWithOptions() {
    ReplicateChatOptions options = ReplicateChatOptions.builder()
            .model("meta/meta-llama-3-8b-instruct")
            .withParameter("temperature", 0.8)
            .withParameter("max_tokens", 100)
            .build();

    Prompt prompt = new Prompt("Tell me a joke", options);
    ChatResponse response = chatModel.call(prompt);

    System.out.println(response.getResult().getOutput().getText());
    System.out.println("Tokens used: " + response.getMetadata().getUsage().getTotalTokens());
}
```

## Usage Examples

### 1. Chat Model (Conversational AI)

```java
@Autowired
private ReplicateChatModel chatModel;

// Synchronous / Blocking Request
public void simpleChat() {
    String response = chatModel.call("What is the capital of France?");
    System.out.println(response);
}

// Streaming Chat. You will receive the tokens as they are streamed from the model.
public void streamingChat() {
    Flux<ChatResponse> responseFlux = chatModel.stream(new Prompt("Count from 1 to 100"));

    responseFlux.subscribe(chatResponse -> {
        String chunk = chatResponse.getResult().getOutput().getText();
        System.out.print(chunk);
    });
}

// You can also create and pass different options per Request
```

### 2. Media Model (Image/Video/Audio Generation)

```java
@Autowired
private ReplicateMediaModel mediaModel;

public void generateImages() {
    ReplicateOptions options = ReplicateOptions.builder()
        .model("black-forest-labs/flux-schnell")
        .withParameter("prompt", "a cat sitting on a laptop")
        .withParameter("num_outputs", 2)
        .build();

    MediaResponse response = mediaModel.generate(options);

    List<String> imageUrls = response.getUris();
    imageUrls.forEach(url -> System.out.println("Image URL: " + url));
}
```

### 3. String Model (Classification/Detection)
There are two way to upload resources to replicate: 
- Upload the file and then reference it in your request
- Base64-Encoded

```java
@Autowired
private ReplicateStringModel stringModel;

@Autowired
private ReplicateApi replicateApi;

public void classifyImage() {
    Path imagePath = Paths.get("path/to/image.jpg");
    FileSystemResource fileResource = new FileSystemResource(imagePath);
    FileUploadResponse uploadResponse = replicateApi.uploadFile(fileResource, "image.jpg");
    String imageUrl = uploadResponse.urls().get();

    ReplicateOptions options = ReplicateOptions.builder()
        .model("falcons-ai/nsfw_image_detection")
        .withParameter("image", imageUrl)
        .build();

    StringResponse response = stringModel.generate(options);
    System.out.println("Classification: " + response.getOutput());
}

public void classifyWithBase64() {
    byte[] imageBytes = Files.readAllBytes(Paths.get("path/to/image.jpg"));
    String base64Image = "data:application/octet-stream;base64,"
        + Base64.getEncoder().encodeToString(imageBytes);

    ReplicateOptions options = ReplicateOptions.builder()
        .model("falcons-ai/nsfw_image_detection")
        .withParameter("image", base64Image)
        .build();

    StringResponse response = stringModel.generate(options);
    System.out.println("Classification: " + response.getOutput());
}
```

### 4. Structured Model (Embeddings)
Structured Models will respond with Key/Value Pairs. You can use an ObjectMapper
```java
@Autowired
private ReplicateStructuredModel structuredModel;

public void generateEmbeddings() {
    ReplicateOptions options = ReplicateOptions.builder()
        .model("openai/clip")
        .withParameter("text", "spring ai framework")
        .build();

    StructuredResponse response = structuredModel.generate(options);
    Map<String, Object> rawOutput = response.getOutput();
    MyClass output = objectMapper.convertValue(rawOutput, MyClass.class);
}
```

## Important Notes

### Multi-turn Conversations
The current implementation does **not support multi-turn conversations** with conversation history. Each call is independent.
This is due to the fact that not all models support this, those who do have different schemas and it would be rather difficult to
maintain a list of the correct mappings.

### Model Discovery
Visit [Replicate.com](https://replicate.com/explore) to discover available models. Each model has different input parameters - check the model's documentation on Replicate for specific parameter requirements.

## License

Licensed under the Apache License, Version 2.0.
