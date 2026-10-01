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

Browser WebSocket clients should obtain a fresh one-use ticket for every connection:

```js
const response = await fetch('/openmrs/ws/sihsalus/notifications/websocket-ticket', {
  method: 'POST',
  credentials: 'same-origin',
});
if (!response.ok) throw new Error(`WebSocket ticket failed: ${response.status}`);
const { connectionId } = await response.json();
const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
const socket = new WebSocket(
  `${protocol}//${location.host}/openmrs/ws/sihsalus/notifications` +
    `?connectionId=${encodeURIComponent(connectionId)}&topics=laboratory`,
);
```

Obtain another ticket when reconnecting after close code `1001` (`reauthenticate`) or `1013`
(temporary capacity/backpressure). Never persist or reuse a ticket.

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

The deployable module is produced at `omod/target/sihsalusnotifications-1.3.0-SNAPSHOT.omod`.

## Runtime notes

The gateway must forward `Upgrade` and `Connection` headers for the exact WebSocket path and must
disable proxy buffering for the SSE path. Installing, upgrading, or removing the WebSocket endpoint
requires an OpenMRS restart because JSR 356 does not define dynamic endpoint removal.

## Physician result inbox proposal (1.3.0-SNAPSHOT)

A new result-ready signal is addressed to the user linked to the requesting
Provider, on the `clinical-results` topic. It requires
`app:hoja.clinica.ordenes` and the same nearest `Facility Location`. Recipient
eligibility and the inbox endpoints additionally require `Get Orders`,
`Get Patients` and `Get Observations`. The existing
Laboratorio signal is preserved. Recipient metadata is not serialized and SSE
still carries only `orderUuid`.

Pending reviews use OpenMRS's existing durable Alert/AlertRecipient model rather
than a new schema or browser history. Only newly completed, non-voided TestOrders
with a matching persisted root observation are eligible; numeric zero is valid.
Exactly one active User must be linked to the Provider's Person. Ambiguous,
retired, unauthorized or absent links fail closed. Existing historical completions
and amended results are outside this proposal.

Alerts contain only the internal versioned marker and order UUID, never patient
names, result values or clinical text. Writing occurs in REQUIRES_NEW after the
clinical transaction commits. A pessimistic lock on the existing order row
serializes duplicate completion callbacks before checking the durable alert.
A temporary `Manage Alerts` proxy privilege is scoped only to saving the verified
alert; it does not alter roles. Provider-to-user lookup similarly scopes `Get Users`
to this server-side lookup. Persistence or delivery failures do not undo clinical
completion. A persistence failure is logged; there is no durable outbox or automatic
repair of such a missed alert in this proposal.

- `GET /ws/sihsalus/notifications/results?offset=0`: current user's unread,
  currently authorized completed orders at the selected facility, sorted newest
  first, with pages of 20 and total count. The core AlertService loads this user's
  unread alerts before filtering/pagination; this proposal does not add a separate
  database query/index for high-volume inboxes.
- `POST /ws/sihsalus/notifications/results/{id}/review`: idempotently mark this
  user's own eligible alert recipient as read. Requires exact same-origin JSON.
  Anonymous/retired/denied users, foreign alerts and other facilities cannot review.

The list response contains patient and test names only after verifying recipient,
Provider ownership, completed result and current facility. Both endpoints send
`Cache-Control: no-store`. The API does not accept another user UUID or arbitrary
notifications. Opening an inbox performs no writes. Reviewed notifications stay in
core alerts for normal institutional retention; no purge or expiry is introduced.

This snapshot requires coordinated synthetic DEV/QLTY validation before release:
actual AlertService persistence and locking, provider/session permissions, completing
results, sender/recipient isolation, F5/backend restart, failed writes, retry, burst
updates and cleanup. Unit tests do not replace an OpenMRS database smoke test.
