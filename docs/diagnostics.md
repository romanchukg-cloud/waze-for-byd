# Support and diagnostics

Build test.4 adds **Settings > Support · Waze for BYD**, automatic failure
reports, manual sending and export. Physical BYD UI and real Waze report delivery
remain unverified.

The first-use notice explains technical collection and public GitHub publication.
**Automatically send diagnostics** is selected by default. Transmission starts
only after **Continue** with that choice and verification of server support.
An existing opt-out is retained. An older notice that described automatic sending
as unavailable requires the updated notice before automatic uploads can start.

Automatic reports cover measured errors in Waze's own source, adapter or projection
integration. Ordinary successful sessions, missing user observations and uploader
errors never trigger reports. Full Waze crash/ANR detection and wheel integration
are not implemented. A successful guidance command does not prove visible output.

A persisted cooldown allows at most one new automatic report per installation per
24 hours, covering repeated fingerprints and different failures/builds. Fingerprints
contain only project, build and approved failure stage/code. Clock rollback blocks
admission; same-boot elapsed time also guards against wall-clock fast-forward.
Automatic reports occupy at most one of three queue slots, preserving two manual
slots, and never evict a manual report. Unsent automatic content expires after
24 hours, even when Retry-After is longer. Completed automatic payloads/tokens
are cleared; the confirmed issue link remains available.

The fixed HTTPS service advertises schema v2 and automatic grouping. The client
checks the advertised version, enabled intake/delivery and grouping contract before
every automatic POST. Missing, malformed, disabled or old-server capabilities
prevent automatic uploads; manual schema-v1 reports remain available. V2 adds an
explicit automatic origin and requires an approved non-upload failure event.
Server grouping uses project/build/last failure stage/code/UTC day, with receipts
linking to a canonical issue, including closed issues. The server does not post a
comment for every report. Server quotas remain 5/hour/IP, 30/day/project and
60/day/global, with seven-day private content and 30-day receipt retention.

Turning the switch off stops new automatic work/retries, removes never-attempted
automatic reports and requests cancellation of known queued receipts. It leaves
manual reports independent. Already claimed/public reports cannot be recalled.
If an automatic POST response was lost, opt-out clears local content without
replaying POST: acceptance is unknown and server delivery may still occur.

## Send a report

1. Open Support and choose a problem category.
2. Select cluster guidance, HUD guidance and stock-navigation HUD observations
   independently. Leave unknown unless you checked the physical display.
3. Choose Send diagnostics, inspect the sanitized JSON and public-report
   disclosure, then press Send. Manual sending works with automatic reporting off.
4. Pending means the request is saved locally; Queued means the server accepted
   it. Report sent appears only after confirmed GitHub delivery and a validated
   link to this repository's issue.

Reports go over HTTPS to diagnostics.localcharge.app, project waze-for-byd.
The server chooses the destination. No GitHub credentials are included in the APK.
No production test issues were created by automated tests.

The private queue holds at most three reports (at most one automatic). Offline retries
retain the exact body, UUID and report token across restarts, respect Retry-After
and stop after 24 hours. A full queue requires explicit removal of a local copy.
Queued cancellation is possible only before the server claims delivery.
For manual reports, if the initial response was lost, cancellation first recovers
the receipt using the original request. Automatic opt-out uses the rule above. Already delivering/published issues cannot be recalled.
Removing a local copy is separate and does not cancel server delivery.

A network-constrained persisted JobScheduler job handles delivery. It does not
keep an always-running service or open dialogs during background work.
If scheduling is unavailable, the report stays local; Support displays this and
offers export. App startup or opening Support retries scheduling.

## Data and export

Only approved structured navigation events are recorded: integration lifecycle,
fresh source availability, guidance dispatch results, projection surface events
and safe failure codes. Wheel events have no producer because wheel support is
not implemented.

Private recording is bounded to 1 MiB and the most recent ten-minute window;
expired events are pruned on recording, report creation and startup. Reports are
limited to 256 KiB and 500 events. Raw logcat, exception messages, preferences,
routes, coordinates, road names, accounts, screenshots, VIN and persistent
hardware IDs are excluded. Device metadata includes Android version, manufacturer
and model; firmware is unknown. Technical metadata can still identify an unusual
configuration: this is not a promise of complete anonymity.

Components remain empty until packages are registered by the server maintainer.
Unsupported settings are omitted. Successful dispatch is not proof of receiver
acceptance or visible cluster/HUD output.
Guidance event icons describe the adapter's AMap input in both transports;
standalone HUD packets convert that input to BYD instrument codes separately.

Record diagnostic session captures an anchored 60-second window of these events.
It does not record screen/audio or system logs. Recording must finish before
preview; a later preview retains the original window. Expired captures require
another recording or a report of recent events.

Export uses Android's document picker and includes only validated JSON, without
the private queue token/key. Document-provider availability on BYD needs a test.

## Validation and dependencies

Tests use a separate APK with fake delivery and no INTERNET permission. They cover
schema rejection, first-use consent, opt-out, successful-session exclusion, measured
failure hooks, cooldowns, reserved capacity, capability fallback, preview/Send,
export, stable retry identity, cancellation races, backoff, retention and receipt/link
validation. The generated automatic payload is checked against the shared server
validator locally; no production report is posted by these tests. Local STATE records
the final Android versions, counts, APK hash and update verification.

Real Waze service delivery, physical BYD UI/document picker, background restrictions
and force-stop/reboot behavior still require validation. The production capability
response was read over normal TLS. Server grouping/reconciliation is covered by the
server maintainer's fake-transport tests, not a Waze production submission.

The shared server, internal handoff and configuration examples remain local-only
under ignored support/. No server or other application's source was changed.
