package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.inbox.Marker;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Marker")
record MarkerResponse(@JsonProperty("last_read_id") String lastReadId, int version,
                      @JsonProperty("updated_at") String updatedAt) {
    static MarkerResponse from(Marker marker) {
        return new MarkerResponse(marker.lastReadId().toString(), marker.version(), ApiTimestamps.format(marker.updatedAt()));
    }
}
