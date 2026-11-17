Member A decisions (UC-006 / UC-017)
==================================

Saved-list names follow OpenAPI's 1–100 character limit, reject blank input,
and strip surrounding whitespace. Duplicate list names are permitted.
Only active MEMBER owners can access lists; foreign and missing IDs return 404.
Lists and items use newest-first ordering with UUID tie-breaks.
PUT of an existing item returns 204 without refreshing added_at, even if the
place has since become unavailable. Removing an absent item also returns 204.
Deleting a list deletes only that list and its cascading items.

B must provide a PlaceQueryService bean. New item PUTs and nonempty detail GETs
return explicit 501 when no provider is installed. Empty details, list summaries,
creation, renaming, deletion, duplicate PUTs, and item removals work without B.
The provider must reject nonpublished new saves and return a permitted historical
card plus available=false for hidden/inactive saved places. Runtime SQL touches
only A's saved_list/saved_list_item tables, never B/C tables.

Notifications are in-app only, for the authenticated active recipient (including
staff). GET does not mark read. Unread count covers the entire recipient inbox,
independent of the requested page. Pagination and counts share a repeatable-read
snapshot. Mark-read preserves the first read_at and returns 204 on retries;
foreign/missing notifications return 404. Target IDs are metadata only; opening
a target requires that target endpoint's current authorization. No email/push,
marketing consent, target expansion, or new notification producers are added.

No server-side analytics event is fabricated on PUT: the analytics contract
requires a client eventId/sessionId/platform absent from this endpoint. Duplicate
PUTs do not create another item or analytics event.

Tests use real session JWTs from vn.gowithbae.TestJwtFactory and PostgreSQL
Testcontainers. B-owned rows in SavedListsApiTest are fixtures for the existing
foreign key, not a production query integration. Maven was intentionally not run
while other agents generate/compile the shared target.
