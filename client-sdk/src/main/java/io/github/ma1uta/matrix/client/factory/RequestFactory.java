/*
 * Copyright sablintolya@gmail.com
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

package io.github.ma1uta.matrix.client.factory;

import io.github.ma1uta.jeon.exception.MatrixException;
import io.github.ma1uta.jeon.exception.RateLimitedException;
import io.github.ma1uta.matrix.EmptyResponse;
import io.github.ma1uta.matrix.ErrorResponse;
import io.github.ma1uta.matrix.client.methods.RequestParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

/**
 * Factory to invoke API.
 */
public class RequestFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestFactory.class);

    /**
     * Status code when request was rate-limited.
     */
    public static final int RATE_LIMITED = 429;

    /**
     * Status code when request was finished with success.
     */
    public static final int SUCCESS = 200;

    /**
     * Initial waiting timeout when rate-limited response is occurred.
     */
    public static final long INITIAL_TIMEOUT = 5 * 1000L;

    /**
     * When an one request finish with rate-limited response the next request will be send
     * after {@code timeout * TIMEOUT_FACTOR} milliseconds.
     */
    public static final long TIMEOUT_FACTOR = 2;

    /**
     * Maximum timeout when client will stop send request.
     */
    public static final long MAX_TIMEOUT = 5 * 60 * 1000;

    private final Client client;
    private final String homeserverUrl;
    private Executor executor;

    public RequestFactory(Client client, String homeserverUrl) {
        this.client = client;
        this.homeserverUrl = homeserverUrl;
    }

    public RequestFactory(Client client, String homeserverUrl, Executor executor) {
        this(client, homeserverUrl);
        this.executor = executor;
    }

    public Client getClient() {
        return client;
    }

    public String getHomeserverUrl() {
        return homeserverUrl;
    }

    public Executor getExecutor() {
        return executor;
    }

    /**
     * Build the request.
     *
     * @param apiClass    The target API class.
     * @param apiMethod   The target API method.
     * @param params      The request params (query, path and headers).
     * @param requestType The 'Content-Type' header of the request.
     * @return The prepared request.
     */
    protected Invocation.Builder buildRequest(Class<?> apiClass, String apiMethod, RequestParams params, String requestType) {
        UriBuilder builder = UriBuilder.fromResource(apiClass).path(apiClass, apiMethod);
        Map<String, String> encoded = new HashMap<>();
        for (Map.Entry<String, String> entry : params.getPathParams().entrySet()) {
            encoded.put(entry.getKey(), encode(entry.getValue()));
        }
        URI uri = builder.buildFromEncodedMap(encoded);

        WebTarget path = getClient().target(getHomeserverUrl()).path(uri.toString());
        for (Map.Entry<String, String> entry : params.getQueryParams().entrySet()) {
            path = path.queryParam(entry.getKey(), entry.getValue());
        }
        if (params.getUserId() != null && !params.getUserId().trim().isEmpty()) {
            path = path.queryParam("user_id", encode(params.getUserId().trim()));
        }
        Invocation.Builder request = path.request(requestType);
        if (params.getAccessToken() != null && !params.getAccessToken().trim().isEmpty()) {
            request = request.header("Authorization", "Bearer " + params.getAccessToken().trim());
        }
        for (Map.Entry<String, String> entry : params.getHeaderParams().entrySet()) {
            request.header(entry.getKey(), encode(entry.getValue()));
        }
        return request;
    }

    /**
     * Translates a string into application/x-www-form-urlencoded format using a UTF-8 encoding scheme.
     *
     * @param origin The original string.
     * @return The translated string.
     * @throws IllegalArgumentException when the origin string is empty.
     * @throws RuntimeException         when JVM doesn't support the UTF-8 (write me if this happens).
     */
    protected String encode(String origin) {
        if (origin == null) {
            String msg = "Empty value";
            LOGGER.error(msg);
            throw new IllegalArgumentException(msg);
        }
        try {
            return URLEncoder.encode(origin, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            String msg = "Unsupported encoding";
            LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * Return the function to read an entity with specified class from the response.
     *
     * @param genericType The {@link GenericType} of the entity. Used when the entity is a generic class.
     * @param <R>         The class of the instance.
     * @return the entity extractor.
     */
    protected <R> Function<Response, R> extractor(GenericType<R> genericType) {
        return response -> response.readEntity(genericType);
    }

    /**
     * Return the function to read an entity with specified class from the response.
     *
     * @param responseClass The class instance of the entity.
     * @param <R>           The class of the instance.
     * @return the entity extractor.
     */
    protected <R> Function<Response, R> extractor(Class<R> responseClass) {
        return response -> response.readEntity(responseClass);
    }

    /**
     * Invoke request in async mode.
     *
     * @param action    The action.
     * @param extractor The function to extract an entity from the response.
     * @param <R>       Class of the entity.
     * @return {@link CompletableFuture} the async result.
     */
    protected <R> CompletableFuture<R> invoke(Supplier<Future<Response>> action, Function<Response, R> extractor) {
        CompletableFuture<R> result = new CompletableFuture<>();

        if (getExecutor() != null) {
            CompletableFuture.runAsync(new Stage<>(result, action, extractor), getExecutor());
        } else {
            CompletableFuture.runAsync(new Stage<>(result, action, extractor));
        }

        return result;
    }

    /**
     * Send the POST request.
     *
     * @param apiClass      The target API.
     * @param apiMethod     The concrete API method.
     * @param params        The request params (query, path and header).
     * @param payload       The request body.
     * @param responseClass The class instance of the response body.
     * @param <T>           The class of the request body.
     * @param <R>           The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> post(Class<?> apiClass, String apiMethod, RequestParams params, T payload, Class<R> responseClass) {
        return post(apiClass, apiMethod, params, payload, responseClass, MediaType.APPLICATION_JSON);
    }

    /**
     * Send the POST request.
     *
     * @param apiClass      The target API.
     * @param apiMethod     The concrete API method.
     * @param params        The request params (query, path and header).
     * @param payload       The request body.
     * @param responseClass The class instance of the response body.
     * @param <T>           The class of the request body.
     * @param <R>           The class of the response body.
     * @param requestType   The 'Content-Type' of the request.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> post(Class<?> apiClass, String apiMethod, RequestParams params, T payload, Class<R> responseClass,
                                            String requestType) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, requestType).async().post(Entity.entity(payload, requestType)),
            extractor(responseClass));
    }

    /**
     * Send the POST request.
     *
     * @param apiClass    The target API.
     * @param apiMethod   The concrete API method.
     * @param params      The request params (query, path and header).
     * @param payload     The request body.
     * @param genericType The {@link GenericType} of the response body to fetch generic instance.
     * @param <T>         The class of the request body.
     * @param <R>         The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> post(Class<?> apiClass, String apiMethod, RequestParams params, T payload,
                                            GenericType<R> genericType) {
        return post(apiClass, apiMethod, params, payload, genericType, MediaType.APPLICATION_JSON);
    }

    /**
     * Send the POST request.
     *
     * @param apiClass    The target API.
     * @param apiMethod   The concrete API method.
     * @param params      The request params (query, path and header).
     * @param payload     The request body.
     * @param genericType The {@link GenericType} of the response body to fetch generic instance.
     * @param <T>         The class of the request body.
     * @param <R>         The class of the response body.
     * @param requestType The 'Content-Type' of the request.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> post(Class<?> apiClass, String apiMethod, RequestParams params, T payload,
                                            GenericType<R> genericType, String requestType) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, requestType).async().post(Entity.entity(payload, requestType)),
            extractor(genericType));
    }

    /**
     * Send the GET request.
     *
     * @param apiClass      The target API.
     * @param apiMethod     The concrete API method.
     * @param params        The request params (query, path and header).
     * @param responseClass The class instance of the response body.
     * @param <R>           The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <R> CompletableFuture<R> get(Class<?> apiClass, String apiMethod, RequestParams params, Class<R> responseClass) {
        return get(apiClass, apiMethod, params, responseClass, MediaType.APPLICATION_JSON);
    }

    /**
     * Send the GET request.
     *
     * @param apiClass      The target API.
     * @param apiMethod     The concrete API method.
     * @param params        The request params (query, path and header).
     * @param responseClass The class instance of the response body.
     * @param requestType   The 'Content-Type' of the request.
     * @param <R>           The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <R> CompletableFuture<R> get(Class<?> apiClass, String apiMethod, RequestParams params, Class<R> responseClass,
                                        String requestType) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, requestType).async().get(), extractor(responseClass));
    }

    /**
     * Send the GET request.
     *
     * @param apiClass    The target API.
     * @param apiMethod   The concrete API method.
     * @param params      The request params (query, path and header).
     * @param genericType The {@link GenericType} of the response body to fetch generic instance.
     * @param <R>         The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <R> CompletableFuture<R> get(Class<?> apiClass, String apiMethod, RequestParams params, GenericType<R> genericType) {
        return get(apiClass, apiMethod, params, genericType, MediaType.APPLICATION_JSON);
    }

    /**
     * Send the GET request.
     *
     * @param apiClass    The target API.
     * @param apiMethod   The concrete API method.
     * @param params      The request params (query, path and header).
     * @param genericType The {@link GenericType} of the response body to fetch generic instance.
     * @param requestType The 'Content-Type' of the request.
     * @param <R>         The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <R> CompletableFuture<R> get(Class<?> apiClass, String apiMethod, RequestParams params, GenericType<R> genericType,
                                        String requestType) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, requestType).async().get(), extractor(genericType));
    }

    /**
     * Send the PUT request.
     *
     * @param apiClass      The target API.
     * @param apiMethod     The concrete API method.
     * @param params        The request params (query, path and header).
     * @param payload       the request body.
     * @param responseClass The class instance of the response body.
     * @param <T>           The class of the request body.
     * @param <R>           The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> put(Class<?> apiClass, String apiMethod, RequestParams params, T payload,
                                           Class<R> responseClass) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, MediaType.APPLICATION_JSON).async().put(Entity.json(payload)),
            extractor(responseClass));
    }

    /**
     * Send the PUT request.
     *
     * @param apiClass    The target API.
     * @param apiMethod   The concrete API method.
     * @param params      The request params (query, path and header).
     * @param payload     the request body.
     * @param genericType The {@link GenericType} of the response body to fetch generic instance.
     * @param <T>         The class of the request body.
     * @param <R>         The class of the response body.
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public <T, R> CompletableFuture<R> put(Class<?> apiClass, String apiMethod, RequestParams params, T payload,
                                           GenericType<R> genericType) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, MediaType.APPLICATION_JSON).async().put(Entity.json(payload)),
            extractor(genericType));
    }

    /**
     * Send the DELETE request.
     *
     * @param apiClass  The target API.
     * @param apiMethod The concrete API method.
     * @param params    The request params (query, path and header).
     * @return the {@link CompletableFuture} instance to make async request.
     */
    public CompletableFuture<EmptyResponse> delete(Class<?> apiClass, String apiMethod, RequestParams params) {
        return invoke(() -> buildRequest(apiClass, apiMethod, params, MediaType.APPLICATION_JSON).async().delete(),
            extractor(EmptyResponse.class));
    }

    /**
     * Async request stage.
     *
     * @param <T> class of the response.
     */
    public static class Stage<T> implements Runnable {

        private final CompletableFuture<T> result;

        private final Supplier<Future<Response>> action;

        private final Function<Response, T> extractor;

        public Stage(CompletableFuture<T> result, Supplier<Future<Response>> action, Function<Response, T> extractor) {
            this.result = result;
            this.action = action;
            this.extractor = extractor;
        }

        @Override
        public void run() {
            long timeout = INITIAL_TIMEOUT;
            try {
                while (!(result.isDone() && result.isCancelled() && result.isCompletedExceptionally())) {
                    LOGGER.debug("Try to send request.");
                    if (timeout > MAX_TIMEOUT) {
                        String msg = "Cannot send request, maximum timeout was reached";
                        LOGGER.error(msg);
                        result.completeExceptionally(new RateLimitedException(msg));

                        LOGGER.debug("Finish invoking.");
                        break;
                    }

                    Response response = action.get().get(timeout, TimeUnit.MILLISECONDS);

                    int status = response.getStatus();
                    LOGGER.debug("Response status: {}", status);
                    switch (status) {
                        case SUCCESS:
                            LOGGER.debug("Success.");
                            result.complete(extractor.apply(response));
                            break;
                        case RATE_LIMITED:
                            LOGGER.warn("Rate limited.");
                            ErrorResponse rateLimited = response.readEntity(ErrorResponse.class);

                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Retry after milliseconds: {}", rateLimited.getRetryAfterMs());
                                LOGGER.debug("Errcode: {}", rateLimited.getErrcode());
                                LOGGER.debug("Error: {}", rateLimited.getError());
                            }

                            timeout = rateLimited.getRetryAfterMs() != null ? rateLimited.getRetryAfterMs() : timeout * TIMEOUT_FACTOR;

                            LOGGER.debug("Sleep milliseconds: {}", timeout);
                            Thread.sleep(timeout);
                            LOGGER.debug("Wake up!");
                            break;
                        default:
                            LOGGER.debug("Other error.");
                            ErrorResponse error = response.readEntity(ErrorResponse.class);

                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Errcode: {}", error.getErrcode());
                                LOGGER.debug("Error: {}", error.getError());
                            }

                            result.completeExceptionally(
                                new MatrixException(error.getErrcode(), error.getError(), error.getRetryAfterMs(), status));

                            LOGGER.debug("Finish invoking.");
                    }
                }

            } catch (InterruptedException e) {
                LOGGER.error("Interrupted", e);
                result.completeExceptionally(e);
            } catch (ExecutionException e) {
                LOGGER.error("Cannot invoke request", e);
                result.completeExceptionally(e);
            } catch (TimeoutException e) {
                LOGGER.error("Timeout expired");
                result.completeExceptionally(e);
            } catch (Exception e) {
                LOGGER.error("Unknown exception", e);
                result.completeExceptionally(e);
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Done: {}", result.isDone());
                LOGGER.debug("Cancelled: {}", result.isCancelled());
                LOGGER.debug("Exception: {}", result.isCompletedExceptionally());
            }
        }
    }
}

