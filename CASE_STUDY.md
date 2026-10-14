**Senior QA Case Study - Submission**

Manual Testing, API & Integration, Automation

Candidate: Aaryan_Sood Email: aaryansood819@gmail.com Role: Senior QA Engineer (manual and automation) Date: 14 October 2026

**How this submission is organised**

| **Deliverable (section 7 of brief)**                  | **Where**                                                |
|-------------------------------------------------------|----------------------------------------------------------|
| Prioritised test approach and triage plan             | This document, section 1                                 |
| Manual scenario matrix (38 scenarios)                 | Workbook, sheet 'Scenario Matrix' (summary in section 2) |
| API / integration validation checklist                | Section 3 and workbook sheet 'API-Integration Checklist' |
| Root-cause hypothesis table (11 causes)               | Section 4 and workbook sheet 'RCA Hypotheses'            |
| One sample defect report                              | Section 4.2                                              |
| Automation candidate list with rationale              | Section 5.1 and workbook sheet 'Automation Candidates'   |
| Automation code sample (runnable, with mock services) | Folder 'automation_sample' (section 5.3 explains)        |
| CI, test data, flakiness, maintenance approach        | Sections 5.2 and 5.4                                     |
| Release recommendation and exit criteria              | Section 6 and workbook sheet 'Exit Criteria'             |

**Key assumptions (apply throughout)**

- No real system access: endpoints, fields and thresholds are **illustrative** and need confirming with the owners.

- Two market/configuration variants: **MKT_A** (implied analytics consent) and **MKT_B** (explicit opt-in consent, separate destination routing). Names are placeholders.

- The event pipeline is: mobile app \> analytics SDK and/or backend \> CDP source \> CDP destination \> customer engagement platform \> in-app/push message.

- Only Scan Completed is affected; login events work. This points to something specific to the scan event or its path, not a general outage.

- Because the problem appeared 'after a release' and affects 'some users' in 'more than one market', I treat a change in the release and a conditional trigger (platform, version, identity state, config) as the leading theories, but I do not assume a cause before evidence.

**1. Test strategy and triage (A1)**

**1.1 Investigation from triage to fix verification**

The principle is to **bisect the pipeline hop by hop** using one known-bad example, then widen to find the pattern, then reproduce before fixing.

| **Phase**                            | **Actions**                                                                                                                                                                                                                                                                             | **Output**                                            |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| 0\. Contain and clarify (first hour) | Confirm severity with Product; agree what 'missing' means; collect first data (1.2); check whether rollout can be paused; open incident channel with Dev, Data/CDP and Engagement owners.                                                                                               | Incident ticket, owners, rollout decision             |
| 1\. Scope the blast radius           | Compare backend ACCEPTED scan counts with CDP and destination counts, split by platform, app version, OS version, market, new vs existing user, day. Find the first day the gap appeared and align with release/config changes.                                                         | Pattern table (who is affected, since when, how many) |
| 2\. Localise the failure point       | Take 3 affected and 3 unaffected users. Trace each scanId through: app log \> outbound request \> backend log \> CDP source debugger \> CDP delivery log \> destination record \> engagement trigger log. The first hop where the affected event disappears is the failure point (3.2). | Failure hop identified                                |
| 3\. Reproduce and confirm cause      | Reproduce in test environment on a release-signed build, using the pattern from phase 1. Use controlled comparisons (RCA table, section 4.1) to confirm one cause and rule out the others.                                                                                              | Reproducible defect with evidence                     |
| 4\. Fix verification                 | Verify on the exact affected combination (pre-fix fail, post-fix pass), run negative controls on unaffected combinations, run P1 matrix and regression smoke, then canary rollout with live reconciliation.                                                                             | Evidence for sign-off (section 6)                     |
| 5\. Prevent recurrence               | Add automated contract and end-to-end event test to smoke suite; add a reconciliation alert; add analytics tracking-plan review to release checklist; blameless review.                                                                                                                 | Automation, monitoring, process change                |

**1.2 First information I would request**

- **Release facts:** app versions and build numbers affected/unaffected, release date and rollout %, list of changes in analytics code, backend, CDP tracking plan and remote config since the last good release.

- **Two or three concrete examples:** scanId, userId (test or hashed), timestamp, platform, OS version, market for an affected scan and for a comparable unaffected one.

- **Raw evidence:** app log lines, outbound request and response for the scan and the event, backend log for the scanId.

- **CDP access:** event debugger or live stream, schema-violation/blocked-event report, delivery logs per destination, dedup settings.

- **Counts:** ACCEPTED scans in backend vs Scan Completed in CDP vs destination, per day since release.

- **Business rules:** daily cap, eligibility rules, consent rules per market, expected event latency (SLA), who owns each hop.

- **Engagement setup:** which event name triggers the message, audience, frequency cap and channel opt-in rules.

**1.3 Highest-risk scenarios and how I prioritise**

I score each scenario by **Impact x Likelihood x Detectability-gap** (1-3 each):

- **Impact:** customers losing messages/points confirmation, privacy exposure (a payload leak is Sev1 regardless of volume), revenue/engagement effect, number of users.

- **Likelihood:** closeness to what changed in the release and to the incident symptoms (platform, market, identity transitions, event path).

- **Detectability-gap:** failures that are silent (event lost without any user-visible error) rank higher because nobody notices them until a campaign underperforms.

Resulting top risks, in order: (1) successful scan not producing exactly one correct event on each platform x market; (2) wrong identity on the event (new user, logout/login, queued events); (3) rejected, duplicate and capped scans wrongly producing a success event; (4) retries and network loss causing lost or duplicate events; (5) payload privacy and schema; (6) regression of login, history and redemption. P1 = risks 1-5 plus the baseline regression; P2 = supporting edge cases; P3 = cosmetic or low-likelihood.

**1.4 Questions to clarify**

| **Owner**               | **Question**                                                                                                                                                                                                                                   |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Product                 | What is the exact definition of 'eligible scan' and cap period (daily/calendar/rolling)? Must a user who declined consent still earn points? What is the acceptable event delay? Which market(s) and how many users are affected commercially? |
| Development             | Is the Scan Completed event sent from the app SDK, the backend, or both? What changed in the release? How is eventId generated and is it stable across retries? How is identity set (anonymous \> userId) and when?                            |
| Integration / CDP owner | Which source and write-key per market? What does the tracking plan enforce (block vs warn)? What dedup window and key? Are there destination filters or transformations? Can I get event debugger and delivery logs for the test environment?  |
| Engagement owner        | What exact event name/properties trigger the message? What audience, frequency cap, channel opt-in and quiet-hour rules apply?                                                                                                                 |
| Data / Analytics        | Can we run a daily reconciliation, and is there a data-loss tolerance agreed for events?                                                                                                                                                       |

**2. Manual scenario matrix (A2)**

The full matrix with preconditions, steps, expected results, priority and layer is in the workbook (sheet 'Scenario Matrix', 38 scenarios). Coverage achieved:

| **Required coverage**                          | **Scenario IDs**                                       |
|------------------------------------------------|--------------------------------------------------------|
| Successful scans, Android and iOS, two markets | SC-01 to SC-04                                         |
| New and existing users                         | SC-05, SC-06 (new); SC-01 to SC-04 (existing)          |
| Invalid, rejected, duplicate, capped           | SC-07, SC-08, SC-09, SC-10, SC-11, SC-12, SC-13, SC-14 |
| Network interruption, retry and recovery       | SC-15, SC-16, SC-17, SC-24                             |
| Background/foreground and app lifecycle        | SC-18, SC-19, SC-31, SC-38                             |
| Login/logout and identity transitions          | SC-05, SC-20, SC-21, SC-22                             |
| Market/configuration variants                  | SC-03, SC-04, SC-26, SC-27, SC-37                      |
| Version matrix (release N vs N-1)              | SC-23, SC-38                                           |
| Schema, privacy and data quality               | SC-25, SC-26, SC-27, SC-28, SC-29                      |
| Messaging eligibility                          | SC-30, SC-31                                           |
| Regression                                     | SC-32, SC-33, SC-34                                    |
| Concurrency, volume, config                    | SC-35, SC-36, SC-37                                    |

**P1 scenarios (run first on every candidate build):**

| **ID** | **Objective**                                                                           | **Platform**  | **Market**    | **Layer**                    |
|--------|-----------------------------------------------------------------------------------------|---------------|---------------|------------------------------|
| SC-01  | Eligible scan emits one correct Scan Completed event (existing user)                    | Android       | MKT_A         | UI + API + CDP + Destination |
| SC-02  | Same as SC-01 on iOS                                                                    | iOS           | MKT_A         | UI + API + CDP + Destination |
| SC-03  | Success path in Market B config (different consent/routing config)                      | Android       | MKT_B         | UI + API + CDP + Destination |
| SC-04  | Market B on iOS (suspected highest-risk combination)                                    | iOS           | MKT_B         | UI + API + CDP + Destination |
| SC-05  | First scan immediately after registration in same session (anonymous \> known identity) | Android       | MKT_A         | UI + API + CDP               |
| SC-06  | As SC-05 on iOS, Market B                                                               | iOS           | MKT_B         | UI + API + CDP               |
| SC-08  | Valid format but not eligible product/campaign                                          | Android + iOS | MKT_A         | API + CDP                    |
| SC-10  | Same user rescans same code                                                             | Android + iOS | MKT_A         | API + CDP                    |
| SC-12  | Rapid double-tap on Scan/Submit                                                         | Android + iOS | MKT_A         | UI + API + CDP               |
| SC-13  | Scan after daily/period cap reached                                                     | Android + iOS | MKT_B         | API + CDP                    |
| SC-14  | Boundary: last allowed scan (cap-th) still succeeds                                     | Android + iOS | MKT_A         | API + CDP                    |
| SC-16  | Request accepted by backend but response lost (timeout)                                 | Android + iOS | MKT_A         | API + UI + CDP               |
| SC-17  | Connectivity drops while analytics event is queued/sent                                 | Android + iOS | MKT_A         | Mobile SDK + CDP             |
| SC-18  | App backgrounded immediately after scan (before flush)                                  | Android + iOS | MKT_A         | Mobile SDK + CDP             |
| SC-19  | App force-killed after scan before flush                                                | Android + iOS | MKT_B         | Mobile SDK + CDP             |
| SC-20  | Logout then login as different user, then scan                                          | Android + iOS | MKT_A         | UI + API + CDP               |
| SC-21  | Event queued under U1, user logs out before flush                                       | Android + iOS | MKT_B         | Mobile SDK + CDP             |
| SC-23  | Previous app version still in field vs new release                                      | Android + iOS | MKT_A + MKT_B | UI + CDP                     |
| SC-24  | CDP ingestion returns 4xx/5xx for event                                                 | Android + iOS | MKT_A         | Integration                  |
| SC-25  | Payload contract validation                                                             | Android + iOS | MKT_A + MKT_B | API + CDP                    |
| SC-26  | No unapproved/personal data in payload                                                  | Android + iOS | MKT_B         | API + CDP                    |
| SC-27  | User has declined analytics consent                                                     | Android + iOS | MKT_B         | UI + SDK + CDP               |
| SC-30  | Event delivered but message NOT triggered (eligibility)                                 | Android + iOS | MKT_A         | Destination + Engagement     |
| SC-32  | Login / Register / Logout events still emit                                             | Android + iOS | MKT_A + MKT_B | UI + CDP                     |
| SC-33  | Points balance and transaction history reflect scan                                     | Android + iOS | MKT_A         | UI + API                     |

**3. API and integration checks (A3)**

**3.1 What I validate at each hop**

| **Validation point**        | **What to check**                                                                                                                                                                                           | **Method**                             |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| Request                     | Method, path, auth header present; Idempotency-Key sent on retries; body has userId, code, platform, market, appVersion; no personal data in body beyond what is required                                   | Capture via proxy / API log            |
| Response status             | 200 for processed scan (ACCEPTED or REJECTED with reason); 400 invalid; 401/403 auth; 409 duplicate (if so designed); 429 throttle; 5xx only on genuine fault. A rejected scan must not look like a success | Assertion on status + body             |
| Response schema             | scanId (uuid), status enum, reason enum when REJECTED, points int, eventId when ACCEPTED; no extra/undocumented fields; correct content-type                                                                | JSON-schema validation                 |
| Business outcome            | ACCEPTED only for eligible, non-duplicate, under-cap scans; points and transaction history consistent                                                                                                       | API + history endpoint                 |
| Event payload schema        | Event name exactly 'Scan Completed'; eventId/dedup key unique per scan; ISO-8601 UTC timestamp; userId; market; platform; appVersion; outcome; types and required fields per tracking plan                  | Compare to tracking plan / JSON schema |
| Identity mapping            | userId on event == authenticated user; anonymousId linked after login/registration; no cross-user leakage after logout/login                                                                                | CDP user profile lookup                |
| Privacy                     | Payload keys subset of approved allow-list; no email, phone, name, precise location or raw device IDs; consent respected per market                                                                         | Allow-list assertion                   |
| Ingestion (CDP)             | Event received by correct source/write-key; no schema violation; counts match backend ACCEPTED count within tolerance                                                                                       | CDP debugger / delivery report         |
| Routing                     | Event forwarded to expected destination(s) for that market only; filters/mappings applied; delivery status success                                                                                          | CDP destination logs                   |
| Retries / idempotency       | Same Idempotency-Key = same scanId, one event; retried delivery with same eventId is deduplicated; DLQ/alert on exhausted retries                                                                           | Replay tests; log review               |
| Downstream record           | Exactly one record per accepted scan; fields mapped correctly; arrives within agreed SLA (e.g. p95 within 60s - to agree)                                                                                   | Destination query API / UI             |
| Message trigger             | Trigger evaluation log shows event received, audience check, frequency cap and channel result                                                                                                               | Engagement platform logs               |
| Reconciliation (monitoring) | Daily count: ACCEPTED scans (backend) vs Scan Completed in CDP vs destination, split by platform x market x version; alert if gap above threshold                                                           | Dashboard / SQL                        |

**3.2 Telling the failure types apart**

The same symptom ('no message') has several causes. I trace one scanId through the pipeline and note **the first hop where it is absent**.

| **Failure type**                | **Signature**                                                                                       | **Where to look**                            |
|---------------------------------|-----------------------------------------------------------------------------------------------------|----------------------------------------------|
| Not generated                   | Backend has ACCEPTED scan but no event record; app log shows no event creation; no outbound request | Local/queue logs show nothing created        |
| Generated but not sent (client) | App log has event; proxy shows no outbound call; SDK queue not flushed                              | SDK queue/flush logs, backgrounding, offline |
| Rejected at ingestion           | CDP receives request but returns 4xx or logs schema/validation violation                            | CDP blocked-event report with reason         |
| Deduplicated                    | CDP shows event dropped as duplicate; same or colliding eventId present                             | Compare eventIds; dedup counters             |
| Delayed                         | Event appears later; arrival time minus scan time exceeds SLA but is finite                         | Latency percentiles                          |
| Misrouted                       | Event in CDP, but sent to wrong destination/market or filtered out                                  | Destination delivery logs; filter rules      |
| Delivered, message not eligible | Event present in destination; engagement trigger log shows suppression (audience, cap, opt-out)     | Trigger evaluation log                       |

Decision order: backend ACCEPTED? \> event created in app/backend? \> outbound request sent and 2xx? \> CDP received and not blocked? \> not deduplicated? \> delivered to the right destination? \> within SLA? \> trigger evaluated and not suppressed?

**3.3 Illustrative assertions (no real endpoints or credentials)**

- POST /api/v1/scans returns 200; body.status == 'ACCEPTED'; body.scanId and body.eventId are valid UUIDs; body.points is an integer \> 0.

- Rejected scan: body.status == 'REJECTED' with reason in {DUPLICATE, NOT_ELIGIBLE, DAILY_CAP_REACHED}; points == 0; no eventId.

- Invalid request returns 4xx, never 5xx; unauthenticated returns 401 and creates nothing.

- Downstream: exactly one event with event == 'Scan Completed' and eventId == body.eventId for the scan.

- event.userId == authenticated user; market and platform equal the request; timestamp is ISO-8601 UTC and within a sane window of server time.

- set(event.keys()) is a subset of the approved allow-list (no email, phone, name, device IDs).

- Same Idempotency-Key sent twice returns the same scanId and results in one event.

- Reconciliation: count(ACCEPTED scans) - count(downstream Scan Completed) \<= agreed tolerance per platform x market x version.

**4. Root-cause analysis and defect report (A4)**

**4.1 Plausible causes, evidence and confirming tests**

Eleven hypotheses, ranked by how well they fit the facts (Scan Completed only, login fine, some users, several markets, after a release). Full table with symptoms is in the workbook.

| **ID** | **Plausible cause**                                                                                                                                          | **Evidence needed**                                                                                                             | **Confirm / rule out**                                                                                                                      |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| RC-1   | Tracking-plan / schema change in the release (event renamed, property type changed, required field missing or null)                                          | CDP schema-violation / blocked-events report; payload diff old vs new build (proxy capture); release diff of analytics code     | Replay old-build and new-build payload to a CDP test source; if only new payload is blocked, confirmed. If both pass, rule out              |
| RC-2   | Identity mapping defect (userId missing, anonymousId only, ID format/hash changed, identity call not completed before scan)                                  | CDP user lookup by anonymousId and userId; identity-call timing in logs; compare affected vs unaffected users (new vs existing) | Run SC-05/SC-20 with proxy capture; assert userId present in the payload at scan time; compare against existing-user control                |
| RC-3   | Mobile analytics SDK queue/flush problem (batching, background flush, offline persistence, release-build-only behaviour)                                     | Proxy / network capture; SDK debug log (queued vs flushed); crash/ANR logs; flush config in release vs debug                    | Same scan on debug vs release build, foreground vs immediate background (SC-18/19); count outbound requests vs events logged                |
| RC-4   | Market / configuration issue (wrong source write-key, destination filter, consent gating, feature flag, region routing)                                      | Per-market config diff (remote config, CDP source/destination settings, consent rules); event debugger per source               | Send identical event for MKT_A vs MKT_B through CDP test source; compare routing/filters; toggle config in test env (SC-37/SC-27)           |
| RC-5   | Platform/version specific defect (e.g. R8/ProGuard strips model class on Android release build; iOS version-specific serialization or permission behaviour)  | Event counts by platform x appVersion x OS version; crash/serialization exceptions in logs                                      | Matrix test on release-signed builds N and N-1 across OS versions (SC-23); inspect logs for serialization errors                            |
| RC-6   | Backend does not emit / fails to publish event for some accepted-scan paths (async job failure, outbox lag, exception swallowed)                             | Backend logs by scanId; event-publisher queue depth, error rate, DLQ contents                                                   | Trace scanId through app \> backend \> queue \> CDP; force publisher failure in test env (SC-24) to confirm alerting vs silent loss         |
| RC-7   | Deduplication misfire (eventId/dedup key non-unique or reused, e.g. derived from code/user/second-level timestamp) so valid events are dropped as duplicates | Compare eventIds of missing vs delivered events; CDP dedup counters                                                             | Send two legitimate distinct scans with same timestamp-second/code pattern; verify both survive; verify true retry is deduped (SC-12/SC-16) |
| RC-8   | CDP \> destination delivery fault (transformation/mapping change, destination filter, throttling, partial outage, retries exhausted)                         | CDP delivery logs and error codes per destination; destination-side ingestion logs; timing of config changes                    | Replay a CDP-stored event to the destination; if delivered, source-side issue; if not, mapping/filter fault                                 |
| RC-9   | Delay misread as loss (backlog, batching interval, rate limits)                                                                                              | End-to-end latency percentiles (scan time vs destination arrival); queue depth                                                  | Measure p50/p95/p99 for N scans; compare to SLA; if all arrive within SLA/backlog window, reclassify as delay                               |
| RC-10  | Event delivered but message not triggered (campaign trigger name/case mismatch, audience, frequency cap, push/in-app opt-out, quiet hours, app state)        | Engagement platform trigger evaluation log; campaign config; user's channel preferences                                         | Send event for an eligible fresh test user in the audience; if message fires, root cause is eligibility of affected users, not pipeline     |
| RC-11  | Timestamp/clock problem (device clock skew, wrong format/timezone, future timestamp) causing events to be dropped or mis-windowed                            | Compare client timestamp vs server receive time; CDP rejection reasons                                                          | Set device clock +/- (SC-28); verify handling or server-side timestamp fallback                                                             |

**Most likely candidates before evidence:** RC-1 (schema change in release), RC-2 (identity mapping on scan path), RC-3/RC-5 (SDK or release-build behaviour by version/platform) and RC-4 (market configuration). They fit 'after a release' and 'only some users'. I would test them first because each can be confirmed or ruled out cheaply with one payload comparison.

**4.2 Sample defect report**

*Values below are **illustrative** examples of how I would record a reproduced defect; real build numbers and IDs would replace them.*

| **Field**                        | **Detail**                                                                                                                                                                                                                                                                                                                                 |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ID / Title                       | DEF-001: Scan Completed event not delivered downstream after an accepted scan (new users, iOS, Market B)                                                                                                                                                                                                                                   |
| Environment / build              | Test environment; iOS app release candidate 3.8.0 (build 412), release-signed; iOS 17.x iPhone; Market B account; CDP test workspace for Market B. Control: Android 3.8.0 and iOS 3.7.2 build.                                                                                                                                             |
| Preconditions                    | Brand-new synthetic user (consent granted); valid unused code VALID-\<id\>; network stable; proxy capturing app traffic; CDP debugger open.                                                                                                                                                                                                |
| Steps to reproduce               | 1\) Install build 412 and register a new user. 2) Without restarting the app, scan the valid code. 3) Note scanId and eventId from the API response. 4) Search CDP debugger and the destination for the eventId (wait up to 60 s).                                                                                                         |
| Expected result                  | Scan accepted, points awarded; exactly one Scan Completed event with the matching eventId and the new user's userId reaches CDP and the Market B destination within SLA.                                                                                                                                                                   |
| Actual result                    | Scan accepted (HTTP 200, status ACCEPTED), points shown. App log records Scan Completed. No matching event in CDP debugger or destination after 15 minutes. Login events for the same user are present. Android 3.8.0 and iOS 3.7.2 controls deliver the event (reproduced 5 of 5 on affected combination).                                |
| Impact                           | Affected users do not receive the scan-related in-app message; potential loss of scan data for engagement and reporting. Scope still being measured (affected markets, versions and volume pending reconciliation). Silent failure, no error shown to the user.                                                                            |
| Severity / priority              | Severity: High (S2) - core engagement flow broken silently, no workaround for the user, data loss; not S1 because the scan and points themselves still work and no privacy impact is shown. Priority: P1 - recurring since release, multi-market, blocks rollout expansion. Upgrade to S1 if the loss is broad or if replay is impossible. |
| Evidence                         | Attached: API request/response for the scan; app log extract; proxy capture showing no outbound event request (or the request payload if sent); CDP debugger screenshot with no matching eventId; control run on Android; build and OS details.                                                                                            |
| Suspected area (hypothesis only) | Analytics SDK / identity at time of first scan after registration (RC-2/RC-3). To be confirmed; Dev to inspect.                                                                                                                                                                                                                            |
| Workaround                       | None for users; support can replay events manually after fix if payload is available.                                                                                                                                                                                                                                                      |

**5. Automation (Workstream B)**

**5.1 Automation strategy and candidates (B1)**

I would automate stable, repetitive, high-value checks at the **lowest reliable layer** (API and contract first, a thin UI layer on top), and keep real-device and exploratory work manual. Follows the test pyramid: many API/contract checks, few UI end-to-end checks.

| **Scenario(s)**   | **What is automated**                                                 | **Why suitable**                                                                      | **Suite**                                           |
|-------------------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------|-----------------------------------------------------|
| SC-01/02/03/04    | Success scan end-to-end contract + event for Android/iOS x Market A/B | Stable, high-value, deterministic via API; runs on every build                        | Smoke (SC-01 per market) + Regression (full matrix) |
| SC-08/10/13/14    | Rejected, duplicate and cap rules incl. boundary values               | Pure business logic; data-seedable via API; many combinations cheap to run            | Regression                                          |
| SC-16 (API level) | Idempotent retry after lost response                                  | Deterministic by sending same Idempotency-Key twice; high defect-finding value        | Regression                                          |
| SC-25/26          | Payload schema + privacy allow-list                                   | Mechanical comparison against JSON schema; catches the exact release-regression class | Smoke                                               |
| SC-32/33          | Login/Register events + transaction history regression                | Stable UI/API flows; protects 'login works' baseline                                  | Smoke                                               |
| SC-05/06/20       | New-user and logout/login identity transitions                        | Repetitive, data-heavy, high risk; UI flow on emulator/simulator + payload check      | Regression (nightly)                                |
| SC-24             | Ingestion failure and recovery                                        | Needs stub of failing CDP; impossible to do reliably by hand                          | Regression                                          |
| SC-35/36          | Concurrency and burst                                                 | Needs parallel clients; impractical manually                                          | Nightly                                             |

**Better kept manual or exploratory:**

- **SC-15/17/18/19/21/38** (Network and lifecycle (offline, background, kill, upgrade)): Real-device behaviour (OS scheduling, background limits) is unreliable on emulators; do manually or on a device farm, then automate only the stable parts.

- **SC-28** (Device clock manipulation): Needs device-level settings; exploratory.

- **SC-31** (In-app/push message display): Visual, timing and OS-permission dependent; exploratory sessions per release.

- **Exploratory charters** (Unusual user paths: scan from deep link, scan with poor network + low battery, rapid account switching): Humans find unknown unknowns; time-boxed charters with notes.

**5.2 Approach, test data, environments and CI**

| **Topic**                | **Approach**                                                                                                                                                                                                                                                                                                                                                        |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tools / language         | **Java 17** with JUnit 5 (test runner), Maven (build and dependency management), java.net.http HttpClient or REST Assured for API calls, a JSON-Schema validator for contract checks; **Appium Java client** (or native Espresso / XCUITest if the team prefers) for the mobile UI; Allure or Maven Surefire reports. Tool choice is secondary to the design below. |
| Structure                | Layers: test cases \> service clients (ScanClient, DestinationClient) \> config/data builders. UI uses screen objects, so a UI change is fixed in one place.                                                                                                                                                                                                        |
| Test data                | Synthetic users and codes generated per test through a test-only admin API or seeding script (unique UUID in each identifier). Static fixtures only for read-only reference data (expired code, malformed strings). No production or personal data.                                                                                                                 |
| Environment / config     | One config file per environment and market (base URLs, market, flags) loaded from environment variables; no environment values in test code. Tests are tagged by market and platform and parameterised from the matrix.                                                                                                                                             |
| Secrets                  | Tokens and keys supplied by the CI secret store as environment variables; never committed, never printed in logs; logs mask Authorization headers.                                                                                                                                                                                                                  |
| Isolation / independence | Each test creates its own user and code and asserts only on its own userId/eventId; no ordering dependence; tests can run in parallel. Shared state (caps) is controlled by unique users.                                                                                                                                                                           |
| Cleanup                  | Teardown deletes or expires test users and codes via the admin API; nightly sweeper removes leftovers older than 24h; cleanup failures are reported but do not fail the test.                                                                                                                                                                                       |
| CI pipeline              | On every PR/build: lint + API contract smoke (5-10 min). On release candidate: smoke on one emulator/simulator per platform for both markets. Nightly: full API/integration regression plus UI regression on a device cloud. Post-deploy: a synthetic scan every 15 minutes that checks event arrival (production-safe monitor).                                    |
| Smoke vs regression      | **Smoke** = SC-01 per market, SC-25/26 schema and privacy, SC-32 login. **Regression** = full matrix incl. rejected/duplicate/cap, idempotent retry, identity transitions, concurrency.                                                                                                                                                                             |
| Diagnostics              | On failure capture: request/response, scanId/eventId, last downstream state, app and device logs, screenshot/video (UI), build and config version. Everything attached to the report.                                                                                                                                                                               |
| Flaky tests              | Track flake rate per test. Quarantine a flaky test within 24h (still runs, does not block) and fix root cause. No blanket retries: at most one automatic retry for proven infrastructure errors, always flagged in the report. Target flake rate \< 2%.                                                                                                             |
| Reporting                | JUnit/Allure report on every run, trends by platform x market x build, failures grouped by cause (product, test, environment), Slack/Teams summary, linked defects.                                                                                                                                                                                                 |
| Maintenance              | Selectors and test data owned in one place; tracking-plan schema versioned and shared with Dev so contract tests update with the plan; review automation backlog every sprint.                                                                                                                                                                                      |

**5.3 Practical sample (B2)**

**Files:** automation_sample/pom.xml (Maven project), src/test/java/qa/ScanEventContractTest.java (JUnit 5 tests), src/test/java/qa/mock/MockServices.java (mock app backend + mock CDP/destination) and src/test/java/qa/util/MiniJson.java (small JSON helper; use Jackson or Gson in a real framework). Java 17 and Maven; the only dependency is JUnit 5. No real endpoints, credentials or customer data. I compiled and ran the tests: all pass on the healthy mock, and with an injected fault (event dropped for iOS + MKT_B, the incident pattern) the suite fails on exactly that combination with a clear diagnostic.

Run: mvn test Reproduce the incident: MOCK_FAULT=drop_ios_mkt_b mvn test Real test environment: set APP_API_BASE, DEST_API_BASE and API_TOKEN (from the CI secret store).

What the tests check:

- **API boundary:** status 200, JSON content type, status == ACCEPTED, UUID scanId/eventId, points \> 0.

- **Downstream (async):** exactly one Scan Completed event within the SLA; eventId and scanId match; userId, market, platform and outcome correct; ISO-8601 UTC timestamp; keys limited to the approved allow-list.

- **Negative:** duplicate, ineligible, malformed and capped scans never produce a success event (3 accepted events only).

- **Retry safety:** same Idempotency-Key gives one scan and one event.

- **Auth:** unauthenticated request returns 401.

Core of the asynchronous check (deadline-based polling, no fixed sleep, read-only):

private static List\<Map\<String,Object\>\> waitForEvents(String userId, int expected) throws Exception {

long deadline = System.nanoTime() + (long)(DELIVERY_SLA_S \* 1_000_000_000L);

long delayMs = 100; List\<Map\<String,Object\>\> events = List.of();

while (System.nanoTime() \< deadline) {

events = getEvents(userId); // re-READ destination only

if (events.size() \>= expected) return events;

Thread.sleep(delayMs); delayMs = Math.min((long)(delayMs \* 1.5), 1000); // back-off

}

return events; // caller asserts and prints scanId/eventId + observed events

}

**5.4 Automation design questions (B3)**

| **Question**                                                 | **Answer**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| What at the API boundary vs only after the downstream check? | **API boundary (synchronous):** status code, schema, business outcome (ACCEPTED/REJECTED + reason), points, scanId and eventId presence, idempotency behaviour, auth errors. **Only downstream:** that the event actually arrived, exactly once, with correct identity, schema, privacy-safe fields, correct routing/market, and within SLA; that no event exists for rejected scans; that the engagement trigger fired. An API 200 does not prove an event was delivered, which is the lesson of this incident. |
| How to avoid brittle UI selectors and fixed sleeps?          | Use accessibility IDs / resource IDs agreed with Dev, not text, XPath or coordinates; screen objects for reuse; explicit waits on observable conditions (element visible, API state), never sleep(); deterministic test data; mock the camera with injected code input in test builds; keep UI tests few and move logic checks to API.                                                                                                                                                                           |
| How to handle async delivery without slow or flaky tests?    | Poll the downstream query API with a deadline from the agreed SLA and exponential back-off, returning as soon as the event appears (fast in the normal case); run the check in parallel with other work; for negative checks use a 'canary' valid scan whose arrival bounds the window; use unique IDs so polling never matches other tests; fail with a diagnostic if the SLA is exceeded; separate a slow-delivery alarm (latency) from a lost-event failure.                                                  |
| How to stop retries masking defects or creating duplicates?  | Tests never re-send a scan to make it pass: retries are only on reading the downstream state. HTTP-client retries are disabled in tests; the scan call is sent once, except for the explicit idempotency test with a fixed Idempotency-Key. Each test uses a unique user/code so a re-run cannot collide. Any CI re-run is logged and reported as 'passed on retry' and counted as a flake. Duplicate delivery is itself asserted (count == 1).                                                                  |

**6. Release recommendation**

**Recommendation: BLOCK further rollout of the affected release (hold at the current rollout percentage), and give CONDITIONAL APPROVAL to a fix/hotfix only when the exit criteria below are met.**

Reasoning: the evidence shows a silent loss of a business-critical event in more than one market, with unknown versions, scale and cause. Login works, so the failure is specific to the scan event. Expanding the rollout would increase the number of affected users and lost data. I do not recommend pulling the app from users only on current evidence, because the scan and points flow itself works and the damage is to downstream engagement; that call belongs to Product once the blast radius is measured.

**Decisions I would defer, and why:** (1) full rollback or pulling the release, until affected versions and percentage are known; (2) whether lost events are replayed/backfilled, until we know if the payload is recoverable and whether replaying messages is acceptable to the business; (3) the root cause statement, until one hypothesis is confirmed and the others ruled out.

**Exit criteria for sign-off (thresholds are proposals to agree with Product/Data)**

| **ID** | **Criterion**                                                                                                                                       | **Measurable threshold**                                                                                             |
|--------|-----------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| EC-1   | All P1 scenarios executed on Android and iOS in Market A and Market B; pass rate                                                                    | 100% pass; 0 open Sev1/Sev2 defects                                                                                  |
| EC-2   | Root cause identified, fix verified on the affected version/OS/market combination, and negative control confirms unaffected combinations still pass | Reproduced pre-fix, passes post-fix, evidence attached                                                               |
| EC-3   | Delivery reconciliation in canary/staged rollout (backend ACCEPTED scans vs downstream Scan Completed)                                              | Gap \<= 0.5% per platform x market over \>= 48h and \>= 1,000 scans (thresholds to be agreed with Product/Data)      |
| EC-4   | Duplicate events rate                                                                                                                               | \<= 0.1%; zero duplicates for retry/idempotency tests                                                                |
| EC-5   | Delivery latency scan \> destination                                                                                                                | p95 \<= agreed SLA (proposal: 60s); 0 events beyond 15 min                                                           |
| EC-6   | Schema/privacy                                                                                                                                      | 0 schema violations in CDP; 0 unapproved fields in payload scan                                                      |
| EC-7   | Regression                                                                                                                                          | Login/Register/Redeem/history smoke pass 100%; automation suite green on 3 consecutive runs (no unexplained retries) |
| EC-8   | Messaging                                                                                                                                           | Test user in audience receives the in-app message in Market A and B on both platforms                                |
| EC-9   | Impact remediation decision                                                                                                                         | Decision recorded on missed events from the incident window (backfill / replay / accept)                             |
| EC-10  | Monitoring                                                                                                                                          | Reconciliation alert live and tested before full rollout                                                             |

Trade-offs: a longer canary window raises confidence in the delivery-rate gap but delays the fix; I would choose 48 hours / 1,000 scans per platform x market or until a statistically meaningful sample exists, and accept earlier sign-off only with Product's explicit risk acceptance and a live reconciliation alert.
