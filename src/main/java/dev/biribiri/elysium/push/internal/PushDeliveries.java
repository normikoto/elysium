package dev.biribiri.elysium.push.internal;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.AccessTokens;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.notification.Notification;
import dev.biribiri.elysium.notification.NotificationAdded;
import dev.biribiri.elysium.notification.NotificationService;
import dev.biribiri.elysium.push.PushPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Pushes each new notification to the subscriptions that want it, one job each, so that a slow or failing push service
 * holds up only its own.
 */
@Service
class PushDeliveries {

    private static final TaskDescriptor<PushJob> TASK = TaskDescriptor.of("push", PushJob.class);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * The longest a push service's {@code Retry-After} is followed. Anything longer is treated as a failure instead.
     */
    private static final Duration LONGEST_WAIT = Duration.ofHours(1);

    private final PushSubscriptionRepository subscriptions;
    private final NotificationService notifications;
    private final ActorService actors;
    private final FollowService follows;
    private final AccessTokens tokens;
    private final PushMessages messages;
    private final ServerKeys serverKeys;
    private final PushHttpClient client;
    private final InstanceProperties instance;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    PushDeliveries(PushSubscriptionRepository subscriptions, NotificationService notifications, ActorService actors,
                   FollowService follows, AccessTokens tokens, PushMessages messages, ServerKeys serverKeys,
                   PushHttpClient client, InstanceProperties instance, @Lazy SchedulerClient scheduler) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.actors = actors;
        this.follows = follows;
        this.tokens = tokens;
        this.messages = messages;
        this.serverKeys = serverKeys;
        this.client = client;
        this.instance = instance;
        this.scheduler = scheduler;
    }

    @Bean
    static Task<PushJob> push(ObjectProvider<PushDeliveries> deliveries) {
        return Jobs.task(TASK, job -> deliveries.getObject().run(job));
    }

    @ApplicationModuleListener
    void on(NotificationAdded event) {
        var notification = notifications.notification(event.recipientId(), event.notificationId());
        if (notification.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var subscriptionId : subscriptions.findWanting(event.recipientId(), notification.get().type())) {
            Jobs.schedule(scheduler, TASK, new PushJob(event.notificationId(), subscriptionId, now),
                    event.notificationId().toString(), subscriptionId.toString());
        }
    }

    /**
     * Everything is looked up again, since the subscription, the token, and the notification can each have gone or
     * changed since the job was queued.
     */
    CompletionHandler<PushJob> run(PushJob job) {
        var now = Instant.now();
        if (job.isStale(now)) {
            return Jobs.done();
        }
        var found = subscriptions.find(job.subscriptionId());
        if (found.isEmpty()) {
            return Jobs.done();
        }
        var stored = found.get();
        var token = tokens.activeToken(stored.authorizationId());
        if (token.isEmpty()) {
            subscriptions.remove(stored.subscription().id());
            return Jobs.done();
        }
        if (actors.findLocalById(stored.actorId()).isEmpty()) {
            return Jobs.done();
        }
        var notification = notifications.notification(stored.actorId(), job.notificationId());
        if (notification.isEmpty()
                || !stored.subscription().alerts().contains(notification.get().type())
                || !policyAllows(stored.subscription().policy(), stored.actorId(), notification.get())) {
            return Jobs.done();
        }
        var message = messages.render(notification.get(), stored.actorId(), token.get());
        if (message.isEmpty()) {
            return Jobs.done();
        }
        return send(stored, message.get(), now);
    }

    private boolean policyAllows(PushPolicy policy, UUID recipientId, Notification notification) {
        return switch (policy) {
            case ALL -> true;
            case FOLLOWED -> follows.isFollowing(recipientId, notification.accountId());
            case FOLLOWER -> follows.isFollowing(notification.accountId(), recipientId);
            case NONE -> false;
        };
    }

    private CompletionHandler<PushJob> send(PushSubscriptionRepository.Stored stored, byte[] message, Instant now) {
        var subscription = stored.subscription();
        var receiver = P256.decodePublic(stored.p256dh());
        var keys = serverKeys.get();
        var vapid = VapidTokens.create(subscription.endpoint(), instance.resolve("/"), keys.privateKey(), now);
        var headers = new LinkedHashMap<String, String>();
        headers.put("TTL", Long.toString(PushJob.TIME_TO_LIVE.toSeconds()));
        headers.put("Urgency", "normal");
        byte[] body;
        if (subscription.standard()) {
            body = WebPushEncryption.aes128gcm(message, receiver, stored.authSecret()).body();
            headers.put("Content-Encoding", "aes128gcm");
            headers.put("Authorization", "vapid t=" + vapid + ", k=" + keys.publicKeyBase64Url());
        } else {
            var encrypted = WebPushEncryption.aesgcm(message, receiver, stored.authSecret());
            body = encrypted.body();
            headers.put("Content-Encoding", "aesgcm");
            headers.put("Encryption", "salt=" + BASE64URL.encodeToString(encrypted.salt()));
            headers.put("Crypto-Key", "dh=" + BASE64URL.encodeToString(encrypted.serverKey())
                    + ";p256ecdsa=" + keys.publicKeyBase64Url());
            headers.put("Authorization", "WebPush " + vapid);
        }

        PushHttpClient.Response response;
        try {
            response = client.post(subscription.endpoint(), body, headers);
        } catch (BlockedTargetException e) {
            // It would fail the same way for every notification after this one.
            subscriptions.remove(subscription.id());
            return Jobs.done();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't reach the push service", e);
        }
        var status = response.status();
        if (status >= 200 && status < 300) {
            return Jobs.done();
        }
        // The subscription has ended, such as because the app was uninstalled, or it never worked. Mastodon counts 400 as
        // well, since some services answer that for endpoints they've forgotten.
        if (status == 400 || status == 404 || status == 410) {
            subscriptions.remove(subscription.id());
            return Jobs.done();
        }
        if (status == 408 || status == 429 || status >= 500) {
            var delay = response.retryAfterDelay(now);
            if (delay.isPositive() && delay.compareTo(LONGEST_WAIT) <= 0) {
                return Jobs.waitUntil(now.plus(delay));
            }
            throw new IllegalStateException("The push service answered " + status);
        }
        throw new PermanentJobFailure("The push service refused the message with " + status);
    }
}
