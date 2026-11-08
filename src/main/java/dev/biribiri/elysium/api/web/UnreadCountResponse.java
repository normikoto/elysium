package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UnreadCount")
record UnreadCountResponse(long count) {
}
