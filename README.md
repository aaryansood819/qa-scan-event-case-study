# Senior QA Case Study: missing "Scan Completed" event

**Author:** Aaryan_Sood · aaryansood819@gmail.com · https://github.com/aaryansood819/
![[![CI Active](https://github.com/aaryansood819/qa-scan-event-case-study/a](https://github.com/aaryansood819/qa-scan-event-case-study/actions/workflows/ci.yml/badge.svg)

A practical answer to a Senior QA scenario involving a mobile rewards app (Android and iOS) and an event pipeline.
After a release, some users stop getting a message that should follow a product-code scan. The app records the
scan event locally, yet it never shows up at the downstream destination for part of the user base, across several
markets, while sign-in events keep flowing normally.

The work shows how I would **triage, localise and prove** a silent data-loss defect across an
app > API > analytics/CDP > destination > messaging pipeline, how I would **automate** the checks that catch it,
and how I would make a **release decision** with measurable exit criteria.

> The original assessment brief is not included. Everything here is my own analysis. All systems, endpoints,
> thresholds and build numbers are **illustrative assumptions**; no real system, credential or customer data is used.

## Start here (10-minute review path)

| If you have... | Read |
|---|---|
| 2 min | This page, then the release recommendation in [`docs/CASE_STUDY.md`](docs/CASE_STUDY.md) (section 6) |
| 5 min | + the root-cause table (section 4.1) and the sample defect report (section 4.2) |
| 10 min | + run the automation (below) and open [`ScanEventContractTest.java`](automation_sample/src/test/java/qa/ScanEventContractTest.java) |

A guided tour mapping each part of the work to its purpose is in [`docs/REVIEWER_GUIDE.md`](docs/REVIEWER_GUIDE.md).

## What is in this repository

| Path | Content |
|---|---|
| [`docs/CASE_STUDY.md`](docs/CASE_STUDY.md) | Full written answer (readable on GitHub). Same content as the Word file |
| [`docs/Senior_QA_Case_Study_Submission.docx`](docs/Senior_QA_Case_Study_Submission.docx) | The submission document (Word) |
| [`docs/Supporting_Workbook.xlsx`](docs/Supporting_Workbook.xlsx) | 38-scenario test matrix, API/integration checklist, 11 RCA hypotheses, automation candidates, test data, exit criteria |
| [`automation_sample/`](automation_sample) | Runnable Java 17 / JUnit 5 sample with an in-process mock of the app backend and CDP destination |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | CI: tests pass on the healthy mock, **and** the suite is proven to fail when the incident is injected |

## Summary of the approach

1. **Triage by bisecting the pipeline.** Trace one `scanId` hop by hop (app > backend > CDP source > delivery >
   destination > message trigger). The first hop where it disappears is the failure point. Distinguish *not
   generated*, *rejected*, *deduplicated*, *delayed*, *misrouted* and *delivered but not eligible for a message*.
2. **Prioritise by risk** (impact x likelihood x detectability gap): silent loss ranks highest because nobody sees it.
3. **Eleven root-cause hypotheses**, each with the evidence needed and a cheap test that confirms or rules it out
   (schema change, identity mapping, SDK queue/flush, market config, release-build behaviour, backend publish,
   dedup key collision, CDP delivery, delay, message eligibility, timestamps).
4. **Automate at the lowest reliable layer.** API contract and downstream-event assertions first; thin UI layer
   later; real-device and lifecycle behaviour stays manual/exploratory.
5. **Release recommendation: block further rollout** (hold at current %), with **conditional approval of a fix**
   only when measurable exit criteria are met (reconciliation gap, duplicate rate, latency, schema/privacy,
   regression). Full rollback and backfill decisions are deferred until the blast radius is measured.

## Automation sample

```bash
cd automation_sample
mvn test                             # all 4 tests pass against the healthy mock
MOCK_FAULT=drop_ios_mkt_b mvn test   # reproduces the incident: fails only for iOS + MKT_B
MOCK_FAULT=duplicate mvn test        # detects duplicate events
```

Requirements: JDK 17+, Maven 3.8+. The only dependency is JUnit 5.

Design points worth discussing:

- **Two layers:** synchronous API contract (status, schema, outcome) *and* asynchronous downstream delivery
  (exactly one event, correct identity/market/platform, schema, privacy allow-list).
- **No fixed sleeps:** deadline-based polling with back-off that returns as soon as the event appears.
- **No retry masking:** the test never re-sends a scan to make itself pass; polling only re-reads the destination.
  The one retry test uses a fixed `Idempotency-Key` on purpose.
- **Isolation:** unique synthetic user and code per test; token comes from an environment variable.
- **Proven to detect the defect:** fault injection reproduces the incident pattern and the suite fails on it.

## Assumptions and limitations

- Two markets are modelled as `MKT_A` (implied consent) and `MKT_B` (explicit opt-in); names are placeholders.
- Daily cap = 3, delivery SLA = 10 s in the mock; production values need confirming with Product and Data.
- The mock validates the *test design*, not any real system. Against a real environment only the base URLs and
  token change.
- `MiniJson` is a small helper to avoid extra dependencies; a real framework would use Jackson/Gson and a
  JSON-Schema validator. Mobile UI automation (Appium) is described, not implemented.
- Real-device lifecycle scenarios (background, kill, offline) are specified in the matrix but not automated.

## Process and tools

I wrote the test strategy, root-cause hypotheses and release recommendation based on my own experience,
and used an AI assistant to help with the scenario matrix, wording and the Java automation sample.
I ran the tests locally with `mvn test`, reviewed everything line by line, and adjusted the content so it
reflects how I actually work. I can explain every decision in this repository.
## License

MIT. See [`LICENSE`](LICENSE).
