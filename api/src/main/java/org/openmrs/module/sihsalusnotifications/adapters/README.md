# Included domain adapters

These adapters are bundled for compatibility with existing SIHSALUS realtime
consumers. They are separate from the generic transport/inbox APIs. Another OMOD
can contribute a trusted NotificationInboxType Spring bean without modifying these
adapters, the kernel or the generic REST filter.

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


## Laboratory requester inbox type

`laboratory-result-ready` owns TestOrder eligibility, requester/provider identity,
matching persisted observations and facility scope. Exactly one active User must
be linked to the provider's Person. Missing/ambiguous recipients fail closed.
Numeric zero is valid. Read permissions are `app:hoja.clinica.ordenes`, `Get Orders`,
`Get Patients` and `Get Observations`. Current provider ownership, facility,
completed/non-voided order, patient and encounter are rechecked when listing or
acknowledging. Content resolves patient/test names plus patientUuid only after these
checks; they are not stored in Alert text or included in the generic SSE signal.

The result advice delegates to NotificationInboxService.publishAfterCommit. The
kernel persists after clinical commit. Failures do not undo clinical completion,
but this proposal has no durable outbox/automatic missed-alert repair. Existing
completed orders, amendments, clinical sign-off and covering clinicians are outside
scope. The core API means notification read, never result review or approval.
