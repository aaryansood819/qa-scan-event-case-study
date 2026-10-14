# Reviewer guide

How the work maps to what a Senior QA review typically looks for, and where to find it.

| Review question | Where to look |
|---|---|
| Can they triage a production incident methodically? | `CASE_STUDY.md` section 1 (phased plan, first information to request, clarifying questions) |
| Do they prioritise by risk, not volume? | Section 1.3 (scoring model) and the P1 table in section 2; workbook sheet *Scenario Matrix* (38 scenarios, P1/P2/P3) |
| Do they understand integrations and where data can be lost? | Section 3 (hop-by-hop validation, failure-type table, decision order) |
| Can they reason about root cause without access? | Section 4.1 (11 hypotheses with evidence and confirm/rule-out tests) and workbook sheet *RCA Hypotheses* |
| Can they write a clear defect? | Section 4.2 (illustrative DEF-001: severity vs priority reasoning, evidence, workaround) |
| Do they know what to automate and what not to? | Section 5.1 and workbook sheet *Automation Candidates* |
| Is the automation design sound? | Section 5.2 (data, secrets, isolation, CI, flaky tests) and 5.4 (the four design questions) |
| Does the code reflect that thinking? | `automation_sample/src/test/java/qa/ScanEventContractTest.java` |
| Is there a defensible release decision? | Section 6 and workbook sheet *Exit Criteria* (measurable thresholds, with stated trade-offs) |

## Reading the code in 5 minutes

1. `ScanEventContractTest.waitForEvents` - deadline polling with back-off; read-only against the destination.
2. `successfulScanApiContractAndSingleDownstreamEvent` - API assertions first, then downstream (count == 1,
   identity, schema, privacy allow-list), looped over platform x market.
3. `rejectedAndInvalidScansNeverEmitSuccessEvent` - negative assertions on events (the case most suites miss).
4. `sameIdempotencyKeyRetryCreatesOneScanAndOneEvent` - retry safety without masking.
5. `MockServices` - the fault switch (`drop_ios_mkt_b`, `duplicate`) used to prove the suite catches the incident.

Run `MOCK_FAULT=drop_ios_mkt_b mvn test` inside `automation_sample/` and read the failure message: it names the
platform/market, the scanId/eventId and the events actually observed.

## Suggested discussion topics

- Why block rollout rather than roll back, and what evidence would change that decision.
- Which of the 11 hypotheses to test first and why.
- Trade-offs in the exit criteria (canary length vs speed of fix) and who must approve them.
- What would be needed to run the same tests against a real environment.
