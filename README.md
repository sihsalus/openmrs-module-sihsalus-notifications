# SIHSALUS realtime notifications OMOD

This repository owns the authenticated realtime transports used by SIHSALUS:

- WebSocket ticket: `POST /openmrs/ws/sihsalus/notifications/websocket-ticket`
- WebSocket: `/openmrs/ws/sihsalus/notifications?connectionId=<server-ticket>&topics=system,queue`
- Server-Sent Events: `/openmrs/ws/sihsalus/notifications/sse?topics=system,queue`
- Administrative status: `/openmrs/ws/sihsalus/notifications/status`

The deployable artifact contains both transports. The SIHSALUS distribution consumes a released
`sihsalusnotifications-omod` version; module source is not vendored into the distribution.

## Security and delivery contract

- Both transports reuse the authenticated OpenMRS `JSESSIONID`. Anonymous and retired users are
  rejected.
- WebSocket handshakes enforce same-origin access by default. Extra origins require an explicit,
  exact `sihsalusnotifications.allowedOrigins` entry.
- A browser first obtains a server-generated `connectionId` from the ticket endpoint, which is
  same-origin unless an additional exact origin was explicitly configured.
  The ticket is bound to the authenticated OpenMRS HTTP session, expires after one minute, is
  consumed by exactly one upgrade, and cannot be replaced by a client-generated UUID. Ticket
  responses are never cacheable. This snapshots authorization while the normal OpenMRS servlet
  request context is active, instead of trusting authentication state on a JSR 356 worker thread.
- There is deliberately no REST endpoint that accepts arbitrary notifications. Trusted Java code
  in another OMOD publishes through `NotificationService`.
- Every event is either addressed to one user UUID or protected by a named OpenMRS privilege.
  Unrestricted broadcasts are rejected.
- Location-scoped events additionally require the event and the subscriber's current OpenMRS
  session location to resolve to the same nearest ancestor tagged `Facility Location`. Superuser
  status does not bypass this facility boundary. Built-in clinical events fail closed when either
  side has no tagged facility in its location hierarchy.
- Payloads must be JSON objects and are limited to 64 KiB. Topic and event names use a constrained
  machine-readable alphabet, preventing SSE field injection.
- Delivery is bounded and ephemeral. It does not replace clinical persistence, audit, queues, or
  transactional outbox processing. Slow clients are disconnected instead of creating unbounded
  memory growth. The node admits at most 500 total subscribers and 50 concurrent blocking SSE
  responses. A five-minute replay buffer is capped at 1,000 events and 4 MiB of payloads.
- The OpenMRS 2.8 web descriptor does not enable Servlet async mode. SSE therefore uses bounded
  25-second streaming responses and standard browser reconnection. WebSocket is the preferred
  long-lived transport.
- WebSockets reconnect after at most five minutes so OpenMRS session, location, and privilege
  changes are revalidated. SSE revalidates them on every bounded reconnect and honors the standard
  `Last-Event-ID` header. If a cursor is expired or unavailable after restart, SSE emits
  `SIHSALUS_RESYNC_REQUIRED` so clients can refetch authoritative data without displaying a
  duplicate clinical notice, and clears the stale cursor before the next reconnect. A replay larger
  than a connection's bounded queue also requests a resynchronization instead of silently dropping
  events.

## Publishing from another OMOD

```java
NotificationService notifications = Context.getService(NotificationService.class);

notifications.publish(NotificationRequest.forUser(
    userUuid,
    "queue",
    "QUEUE_ENTRY_UPDATED",
    "{\"queueEntryUuid\":\"...\"}"
));

notifications.publish(NotificationRequest.forPrivilege(
    "system",
    "MAINTENANCE_NOTICE",
    "{\"startsAt\":\"2026-09-02T23:00:00Z\"}",
    "View Administration Functions"
));
```

The wire event contains `id`, `topic`, `type`, `timestamp`, and `payload`. Recipient UUIDs and
required privileges are authorization metadata and are never serialized to clients.

## Domain integrations

Built-in laboratory and order-creation integrations live under
[`adapters`](api/src/main/java/org/openmrs/module/sihsalusnotifications/adapters/README.md).
They retain the existing department signals. Clinical eligibility and resource
access belong to adapters; the generic inbox/transport does not know TestOrder,
DrugOrder, observations or department privileges.

## Recovery and monitoring

SSE clients automatically replay authorized events after a recognized `Last-Event-ID`. Replay
reapplies user, privilege, topic, and session-location checks; it never bypasses current access.
The bounded history is memory-only and is cleared when the module or OpenMRS restarts. It is not a
durable notification inbox.

`GET /openmrs/ws/sihsalus/notifications/status` returns aggregate counters for subscribers,
retained events, publications, live deliveries, replay deliveries, delivery failures, and replay
misses. The endpoint requires an authenticated, non-retired user with
`View Administration Functions`, uses `Cache-Control: no-store`, and never returns event payloads,
topics, user identifiers, or location identifiers.

## Build

The module targets Java 8 bytecode and is verified on Java 8 and Java 21:

```bash
mvn --batch-mode --show-version --no-transfer-progress clean verify
```

The deployable module is produced at `omod/target/sihsalusnotifications-1.3.0-SNAPSHOT.omod`.

## Runtime notes

The gateway must forward `Upgrade` and `Connection` headers for the exact WebSocket path and must
disable proxy buffering for the SSE path. Installing, upgrading, or removing the WebSocket endpoint
requires an OpenMRS restart because JSR 356 does not define dynamic endpoint removal.

## Extensible durable inbox proposal (1.3.0-SNAPSHOT)

`NotificationInboxService` is registered as an OpenMRS service. Trusted OMODs
contribute Spring beans implementing `NotificationInboxType`, discovered through
OpenMRS's registered components. No browser may register types, choose recipients,
change permission policies or publish arbitrary notifications.

A type supplies a stable machine name, non-empty required read privileges,
`recipient(subjectUuid)` and `resolve(subjectUuid, user, facilityUuid)`. Recipient
selection and domain authorization belong to that module. `resolve` must return
null unless current ownership, facility and resource state allow access; authorized
content includes title/subtitle and optional domain context. Unknown/removed types
stay unread and are not exposed; duplicate/invalid registrations fail closed.
Adding another module requires no generic filter or service changes.

After saving its domain resource, a module calls:

```java
Context.getService(NotificationInboxService.class)
    .publishAfterCommit(MyNotificationType.NAME, subjectUuid);
```

The kernel defers publication until an active clinical transaction commits;
rollback produces no notification. When no transaction is active, it persists
immediately in its own transaction. Persisting core Alert/AlertRecipient uses
REQUIRES_NEW and a pessimistic lock on the recipient User row to serialize duplicate
creation across nodes. Deduplication is recipient + type + subject UUID, including
already-read alerts. This initial model deliberately supports one notification per
subject/type; repeatable/amended events require a separately defined domain policy.

Alert text stores only a versioned marker, type name, facility UUID and subject UUID. Current
names/details are resolved at read time, never in replay payloads or Alert text.
A scoped Manage Alerts proxy privilege is used only after server-side identity and
policy checks. Adapters own any narrowly scoped lookup privilege they require.
Notification read is not clinical review, approval, signature or workflow execution.

- `GET /ws/sihsalus/notifications/inbox?offset=0`: the current authenticated user's
  unread authorized items at the current tagged facility, newest first, pages of
  20 with total/hasMore. Each item contains id/type/subjectUuid/createdAt/content.
- `POST /ws/sihsalus/notifications/inbox/{id}/read`: idempotent own-recipient
  acknowledgement after current type permissions and domain access are rechecked.
  Requires exact same-origin JSON; foreign/unauthorized/unknown items return 404.

Both endpoints use no-store and accept no other user UUID. Retired/anonymous users
are rejected and a tagged facility is required. The kernel independently enforces the stored facility boundary before invoking a
domain resolver; a permissive resolver cannot expose an item at another facility.
The kernel cannot substitute a client-supplied audience or facility. After durable commit it emits a user/facility
scoped `NOTIFICATION_CREATED` event on `notifications`, with empty JSON payload;
signal failure cannot undo persistence. Existing department signals are unchanged.

Core AlertService loads this user's unread alerts before filtering/pagination;
there is no optimized database query/index or retention purge in this proposal.
Persistence failures are safely logged; there is no outbox or automatic repair.
Domain adapter failure causes the read to fail rather than report false empty state.

This replaces an unreleased doctor-specific prototype: no compatibility endpoint,
legacy marker migration or historical backfill is introduced. The matching frontend
must use enableNotificationInbox and its typed detail extension slot. Both PRs stay
in draft pending coordinated synthetic DEV/QLTY validation: actual AlertService
persistence/locking, account and facility isolation, permissions, completion,
acknowledgement, F5/backend restart, rollback, retry/burst behavior and cleanup.
Local Java mocks and UI previews do not establish deployed clinical validation.
