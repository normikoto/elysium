package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;


/**
 * Pleroma's and Akkoma's {@code EmojiReact}, of a local status. One whose emoji can't be read is ignored, since it isn't a
 * favourite either.
 */
@Component
class EmojiReactHandler implements InboundActivityHandler {

    private final ReactionReceiver receiver;
    private final JsonMapper json;

    EmojiReactHandler(ReactionReceiver receiver, JsonMapper json) {
        this.receiver = receiver;
        this.json = json;
    }

    @Override
    public String type() {
        return "EmojiReact";
    }

    @Override
    public void handle(InboundActivity activity) {
        receiver.receive(activity, json.readTree(activity.body()));
    }
}
