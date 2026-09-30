package dev.everhost.address;

import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class AddressApi {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public Map<String, Object> call(AddressSettings settings, String path, Map<String, Object> body, String token)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(settings.endpoint().resolve(path)).timeout(Duration.ofSeconds(6));
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        if (path.equals("/api/servers/register") && !settings.registrationKey().isBlank())
            request.header("X-Registration-Key", settings.registrationKey());
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(RouteJson.encode(body)));
        var pending = http.sendAsync(request.build(), info -> new LimitedBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(7, TimeUnit.SECONDS); }
        catch (TimeoutException | ExecutionException ex) {
            pending.cancel(true);
            throw new IOException("Controller unavailable or response invalid; check the URL and connection.");
        } catch (InterruptedException ex) { pending.cancel(true); throw ex; }
        byte[] bytes = response.body();
        if (response.statusCode() / 100 != 2) {
            String detail = switch (response.statusCode()) {
                case 401, 403 -> "Controller rejected the owner token or registration code.";
                case 409 -> "That permanent name is already reserved.";
                case 429 -> "Controller rate limit reached; try again shortly.";
                case 400 -> "Controller rejected the address settings.";
                default -> "Controller request failed (HTTP " + response.statusCode() + ").";
            };
            throw new IOException(detail);
        }
        return RouteJson.object(new String(bytes, StandardCharsets.UTF_8));
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) size += buffer.remaining();
            if (size > 8192) { subscription.cancel(); delegate.onError(new IOException("Response too large")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
