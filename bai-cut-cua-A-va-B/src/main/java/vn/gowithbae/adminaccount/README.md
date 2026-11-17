# Account administration implementation choices

These are implementation decisions made under the user's explicit authorization to resolve member A details. They are not PO approval or a change to the API contract. Main owns the consolidated decision record.

- Users, audit reads, report reads and CSV exports require an active SUPER_ADMIN. Existing JWT/session validation stays in platform/security.
- All account mutations require a trimmed, nonblank reason, at most 1000 characters. Roles replace the complete role set; the contract permits an empty set. Every role must exist in the role catalogue. A no-op role replacement does not revoke sessions or create an audit entry.
- Self-lock and removal of any of the caller's existing roles are forbidden. DELETION_REQUESTED accounts cannot be unlocked or have roles changed here. Deletion remains main's responsibility.
- Account mutations acquire transaction advisory lock 220022, then use AuthService's account-id/email advisory lock keys and user row locks. Active SUPER_ADMIN count is checked while serialized. Other code that removes an active SUPER_ADMIN must preserve the same invariant.
- Lock and actual role changes revoke all A access sessions and consume unused OTP challenges by user ID or current email, including LOGIN challenges without a user ID. Unlock never restores sessions or challenges.
- Lock invokes all ordered AccountLifecycleParticipant callbacks inside the transaction. Role changes invoke no C hooks. Audit insertion, revocation and callbacks must all succeed for a lock to commit.
- Audit before/after fields contain only status or sorted role codes, never the profile, email, challenge or session. Actor, object, timestamp and mandatory reason are retained. There is no audit mutation endpoint.
- User search is a literal, case-insensitive substring over email/display name. Pagination is 0-based, size 1–100, with stable timestamp/ID ordering. Audit dates are inclusive Vietnam calendar days, implemented as a half-open timestamp range.

Validation is in AdminAccountsApiTest. Maven execution is reserved to main; these tests have not been run by this agent.
