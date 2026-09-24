package com.drivesync.watcher.sync;

import com.drivesync.watcher.model.FileChangeEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything the watcher needs to talk to the coordinator over plain REST:
 * device registration, change submission, catch-up fetch, and blob
 * upload/download. Deliberately built on the JDK's own java.net.http.HttpClient
 * rather than adding a REST client library - one more way this project keeps
 * the watcher dependency-light (see pom.xml).
 */
public class SyncApiClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SyncApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void registerDevice(String deviceId, String displayName) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(Map.of("deviceId", deviceId, "displayName", displayName));
        HttpRequest request = jsonRequest("/api/devices/register").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        send(request, 200);
    }

    public ChangeDto submitChange(FileChangeEvent event, Long baseChangeId) throws IOException, InterruptedException {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("deviceId", event.deviceId());
        payload.put("relativePath", event.relativePath());
        payload.put("changeType", event.changeType().name());
        payload.put("contentHash", event.contentHash());
        payload.put("sizeBytes", event.sizeBytes());
        payload.put("clientDetectedAt", event.detectedAt().toString());
        payload.put("baseChangeId", baseChangeId);

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = jsonRequest("/api/sync/changes").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = send(request, 200);
        return objectMapper.readValue(response.body(), ChangeDto.class);
    }

    public List<ChangeDto> fetchChangesSince(long since, String excludeDeviceId) throws IOException, InterruptedException {
        String path = "/api/sync/changes?since=" + since + "&excludeDevice=" + excludeDeviceId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        HttpResponse<String> response = send(request, 200);
        return objectMapper.readValue(response.body(), objectMapper.getTypeFactory().constructCollectionType(List.class, ChangeDto.class));
    }

    public boolean blobExists(String hash) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sync/blobs/" + hash))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode() == 200;
    }

    public void uploadBlob(String hash, byte[] content) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sync/blobs/" + hash))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Blob upload for " + hash + " failed with HTTP " + response.statusCode());
        }
    }

    public byte[] downloadBlob(String hash) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sync/blobs/" + hash)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Blob download for " + hash + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpRequest.Builder jsonRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expectedStatus) {
            throw new IOException("Request to " + request.uri() + " failed: HTTP " + response.statusCode()
                    + " - " + response.body());
        }
        return response;
    }
}
