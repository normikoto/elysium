package dev.biribiri.elysium;

import dev.biribiri.elysium.mail.MailNotSentException;
import dev.biribiri.elysium.mail.Mailer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Keeps email instead of sending it, so that tests can read the links in it. Test classes share contexts, so a test should
 * look for messages to the address it used.
 */
public class RecordingMailer implements Mailer {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (failing) {
            throw new MailNotSentException("Failing for a test", null);
        }
        sent.add(new Message(to, subject, body));
    }

    public List<Message> sentTo(String address) {
        return sent.stream().filter(message -> message.to().equalsIgnoreCase(address)).toList();
    }

    /**
     * @return the token from the last link sent to this address
     */
    public String lastTokenSentTo(String address) {
        var messages = sentTo(address);
        if (messages.isEmpty()) {
            throw new AssertionError("Nothing was sent to " + address);
        }
        var matcher = TOKEN.matcher(messages.getLast().body());
        if (!matcher.find()) {
            throw new AssertionError("The last message to " + address + " has no link with a token");
        }
        return matcher.group(1);
    }

    /**
     * Makes sending fail until this is called again with false. Reset it in a {@code finally}, since contexts are shared.
     */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public record Message(String to, String subject, String body) {
    }
}
