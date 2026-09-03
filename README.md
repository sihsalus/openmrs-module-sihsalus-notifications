# SIHSALUS realtime notifications OMOD

This repository owns the authenticated realtime transports used by SIHSALUS:

- WebSocket: `/openmrs/ws/sihsalus/notifications?connectionId=<random-uuid>&topics=system,queue`
- Server-Sent Events: `/openmrs/ws/sihsalus/notifications/sse?topics=system,queue`
- Administrative status: `/openmrs/ws/sihsalus/notifications/status`

The deployable artifact contains both transports. The SIHSALUS distribution consumes a released
`sihsalusnotifications-omod` version; module source is not vendored into the distribution.

## Security and delivery contract

- Both transports reuse the authenticated OpenMRS `JSESSIONID`. Anonymous and retired users are
  rejected.
- WebSocket handshakes enforce same-origin access by default. Extra origins require an explicit,
  exact `sihsalusnotifications.allowedOrigins` entry.
- Every WebSocket request includes a client-generated random `connectionId` UUID. It binds exactly
  one upgrade to its authenticated handshake context and avoids sharing mutable authentication
  state between concurrent JSR 356 handshakes.
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

## Laboratory result-ready event

Version 1.2.0 publishes a built-in event after an OpenMRS `TestOrder` is successfully moved to
the `COMPLETED` fulfiller status:

- topic: `laboratory`
- type: `LAB_RESULT_READY`
- required privilege: `app:home.laboratorio`
- payload: `{ "orderUuid": "..." }`

The event is emitted only after transaction commit and is restricted to the encounter location's
nearest ancestor tagged `Facility Location`. It contains no patient demographics, result values,
diagnoses, or free text. Delivery failures are logged and never roll back the clinical order
update. The event is a refresh signal only; clients must retrieve the authoritative result through
the normal authenticated OpenMRS APIs.

Browser WebSocket clients should generate the handshake identifier with `crypto.randomUUID()` and
reconnect after close code `1001` (`reauthenticate`) or `1013` (temporary capacity/backpressure).

## Order-created events

Version 1.2.0 emits department- and facility-scoped events after a genuinely new order commits:

| OpenMRS order | Topic | Type | Required privilege |
| --- | --- | --- | --- |
| `DrugOrder` | `pharmacy` | `MEDICATION_ORDER_CREATED` | `app:home.farmacia` |
| `TestOrder` | `laboratory` | `LAB_ORDER_CREATED` | `app:home.laboratorio` |

Each payload contains only `{ "orderUuid": "..." }`. Patient identity, medication or test names,
dosage, instructions, diagnosis, and free text are deliberately excluded. Revisions, renewals,
discontinuations, and repeated saves do not emit creation events. As with result-ready events,
delivery happens after commit and is only a signal for authorized clients to refetch their queue.
An order whose encounter location has no ancestor tagged `Facility Location` produces no realtime
event; normal worklist polling remains the fallback.

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

The deployable module is produced at `omod/target/sihsalusnotifications-1.2.0.omod`.

## Runtime notes

The gateway must forward `Upgrade` and `Connection` headers for the exact WebSocket path and must
disable proxy buffering for the SSE path. Installing, upgrading, or removing the WebSocket endpoint
requires an OpenMRS restart because JSR 356 does not define dynamic endpoint removal.
