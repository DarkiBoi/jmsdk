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

package io.github.ma1uta.matrix.client;

import io.github.ma1uta.matrix.client.api.EventContextApi;
import io.github.ma1uta.matrix.client.model.eventcontext.EventContextResponse;

/**
 * Event context methods.
 */
public class EventContextMethods {

    private final MatrixClient matrixClient;

    EventContextMethods(MatrixClient matrixClient) {
        this.matrixClient = matrixClient;
    }

    protected MatrixClient getMatrixClient() {
        return matrixClient;
    }

    /**
     * This API returns a number of events that happened just before and after the specified event. This allows clients to get the
     * context surrounding an event.
     *
     * @param roomId  The room to get events from.
     * @param eventId The event to get context around.
     * @param limit   The maximum number of events to return. Default: 10.
     * @return The events and state surrounding the requested event.
     */
    public EventContextResponse context(String roomId, String eventId, Integer limit) {
        RequestParams params = new RequestParams().pathParam("roomId", roomId).pathParam("eventId", eventId);
        if (limit != null) {
            params.queryParam("limit", Integer.toString(limit));
        }
        return getMatrixClient().getRequestMethods().get(EventContextApi.class, "context", params, EventContextResponse.class);
    }
}
