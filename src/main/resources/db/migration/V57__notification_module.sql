-- The inbox module became the notification module. Event publications are stored with the names of their event's class
-- and their listener's method, so ones that hadn't completed would otherwise never be retried, since neither name exists
-- any more.
UPDATE event_publication
SET listener_id = replace(listener_id, 'dev.biribiri.elysium.inbox.InboxProjection.',
                          'dev.biribiri.elysium.notification.NotificationProjection.');

UPDATE event_publication
SET listener_id = replace(listener_id, 'dev.biribiri.elysium.inbox.', 'dev.biribiri.elysium.notification.'),
    event_type  = replace(event_type, 'dev.biribiri.elysium.inbox.', 'dev.biribiri.elysium.notification.');
