# Analytics implementation choices

These are implementation decisions made under the user's explicit authorization to resolve member A details. They are not PO approval or a contract change. Main owns the consolidated decision record.

## Ingestion

- Anonymous callers must provide anonymousId. Authenticated identity always comes from CurrentUser; authenticated events store user_id and discard anonymous_id. Guest and account identities are never merged.
- Batches contain 1–50 events. Every event is validated before any write, including duplicate IDs; the transaction also prevents partial writes on persistence failures. Existing event IDs are ignored without modifying their original payload or identity.
- Only contract event names and WEB_MOBILE/WEB_DESKTOP are accepted. Event time must be within the preceding 90 days, allowing 5 minutes future clock skew. Session/anonymous identifiers use 1–64 ASCII letters, digits, underscore or hyphen. Optional objectId must be a canonical UUID.
- Unknown properties are stripped. Whitelisted values are scalar strings, validated and normalized before storage:
  - All events: area_id, category_id (UUID).
  - search_submitted: search_id (UUID).
  - results_shown: search_id and results_count (nonnegative integer with at most nine digits).
  - vote_submitted: candidate_id (UUID), preference (LIKE/SKIP).
  - visit_self_reported: answer (YES/NO).
- OTPs, tokens, private notes, query text and precise locations have no whitelisted property. These client events are telemetry, not authoritative business counters; no B/C tables are inspected to verify a reported action.

## Reporting

- Both endpoints require active SUPER_ADMIN. No separate export permission exists in the current role contract, so SUPER_ADMIN is the explicit permission for both operations.
- The default is 30 inclusive Vietnam calendar days ending today. One-sided dates use today as the missing end, or 29 days before the supplied end as the missing start. Reversed ranges, future end dates and periods exceeding 366 days are rejected.
- Area/category filters compare normalized UUID event properties; platform compares the validated enum. Missing properties do not match a filter. All values are bound parameters. No B/C table reads or arbitrary SQL filters exist.
- Counts describe analytics events only. Activity identities are distinct account IDs and distinct guest IDs, separately. Views/saves are event totals because the contract exposes only one metric each.
- A reporting session is keyed by account/guest identity, client sessionId and platform. A gap of at least 30 minutes in the filtered activity starts another session. First search/first plan within the selected period determine conversion and median; negative ordering is excluded.
- Zero-result rate counts searches with a subsequent correlated zero-result event, matching search_id, reporting session, platform and identity. Each search is counted once. Missing correlation for any search makes the rate unavailable rather than assuming a nonzero result. Rates are fractions (0.2 means 20%).
- Retention cohorts use the identity's first event across all recorded analytics. The first event must match the selected filters and period. Only cohorts with all Vietnam calendar days 1–30 completed are eligible; return activity can occur after the report's end date and must match the selected filters. No mature cohort produces null with a note.
- Empty denominator and absent valid conversion durations produce MetricValue.value=null with an explanatory note. Count metrics legitimately return zero when the filtered analytics set is empty.
- Quality statistics (missing price/hours, overdue verification, open reports) come from ordered StatisticsContributor beans with the same normalized date/filter scope. Each nullable quality field is added across disjoint providers that know that field; absent fields remain null with a note. Providers must avoid overlapping datasets, return nonnegative counts and use the supplied scope; no placeholder zero or cross-module query is used. Provider failures abort the report instead of silently returning incomplete values.
- Reports use a repeatable-read transaction and calculate directly from analytics. dataUpdatedAt is calculation time; accepted clock-skew events are not counted before their event time. No scheduled materialization or new indexes are introduced in this scoped change.
- CSV uses exactly the same report calculation, permission and filters, exports only metric/value/note, quotes every cell and prefixes spreadsheet formula-leading text with an apostrophe. Null values export as empty cells, not zero.

Validation is in AnalyticsApiTest. Maven execution is reserved to main; these tests have not been run by this agent. Large analytics history may require indexed/materialized reporting once production volume is known.
