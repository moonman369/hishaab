# hishaab-backend — Spec-Driven Build Plan

| | |
|---|---|
| **Component** | `hishaab-backend` (Spring Boot service) |
| **Repo path** | `hishaab/backend/` |
| **Design reference** | `docs/Hishaab-ai-HLD.md` (the "HLD") — the *why*. This file is the *what to build and how to check it*. |
| **Version** | Spec v1.2 — 18 Nov 2026 (email optional / SMS-only default; investments; synthetic golden set; SBI Card dropped) |
| **Audience** | Claude Code and Ayan |

---

## 0. How to use this document (read first)

### 0.1 For Claude Code — the workflow for every task

1. **Pick the next spec** from the Status Tracker (§3) whose dependencies are `DONE` and which is not `BLOCKED`.
2. **Read the whole spec**, plus the HLD sections it links to.
3. **Before writing code**, post a short plan: the rule IDs (`R-xxx`) and acceptance IDs (`AC-xxx`) you will cover, the files you will create or change, and the migration name (if any).
4. **Write tests first** from the acceptance criteria. Name each test after its AC, e.g. `AC_011_03_duplicateIdIsReportedNotStored()`.
5. **Implement** until `./mvnw verify` passes.
6. **Update this file:** set the spec's status in §3, tick its tasks, and record any decision you had to make in §6 (Decision Log).
7. **Stop and ask Ayan** when a spec is unclear, contradicts the HLD, or needs a dependency not listed in §2.2. Do not guess on anything that affects money totals or dedup.

### 0.2 Hard rules (never break these)

| # | Rule |
|---|---|
| H-1 | Money is always `long` **paise**. Never `double`, `float` or `BigDecimal` in domain code. Convert only at the API boundary if a client needs rupees. |
| H-2 | `raw_message` rows are **never updated in their content and never deleted**. Only `status` and `ignore_reason` may change. |
| H-3 | Schema changes only through **Flyway** migrations. `ddl-auto` stays `validate`. Never edit a migration that has been merged; add a new one. |
| H-4 | Modules talk only through their **public API package**. No reaching into another module's `internal` package. Enforced by the module test (SPEC-000). |
| H-5 | Never weaken, skip or delete a test to make a build pass. If a test is wrong, say so and ask. |
| H-6 | Never log full message bodies, tokens or secrets at `INFO` or above. |
| H-7 | No new dependency without listing it in §6 and asking first. |
| H-8 | No secrets in code or committed files. Config comes from env vars; `.env.example` lists them with fake values. |
| H-9 | Anything the system is unsure about becomes a **review item**. Never silently guess a match, a kind or a category. |
| H-10 | Totals exclude `PENDING` and `HIDDEN` transactions and the kinds `TRANSFER`, `CC_PAYMENT`, `REVERSAL`, `INVESTMENT` from spend/income (see SPEC-041). Investments have their own `investedPaise` total. |

### 0.3 Spec format

Each spec has: **Goal · Depends on · Scope · Data · API · Rules (R-) · Acceptance criteria (AC-) · Tests · Out of scope · Tasks**. Rules say what must always be true. Acceptance criteria are concrete Given/When/Then checks; every AC needs at least one test.

---

## 1. Context in one paragraph

Hishaab.ai turns bank SMS (uploaded raw by an Android worker; **the only source of truth**) into one clean list of transactions. Bank emails are **optional**: only if you connect Gmail does the server pull them, and then they only add evidence to transactions SMS already found (HLD D-27). Each real transaction is stored once (dedup), linked to every message that proves it (evidence), categorised (your rules first, AI only for unknown merchants), and anything unclear goes to a review queue. A daily job pulls email (only if Gmail is connected), re-runs matching, checks balances (every scan, mandatory) and pushes a report that says which mode is active. A chat agent answers questions using read-only tools. Banks in v1: **Axis (savings + credit card), SBI (savings)**; plus **mutual-fund houses (AMCs)** for SIP messages. You also record investments (SIP, RD, FD, PPF) whose instalments are confirmed from messages (SPEC-045/046). One user. See HLD §1–§4, §4.13.

---

## 2. Technical conventions

### 2.1 Stack

| Concern | Choice |
|---|---|
| Runtime | **OpenJDK 25 (LTS)** |
| Framework | Spring Boot **4.x latest stable** |
| Build | Maven (wrapper `./mvnw`) |
| Coordinates | `in.moonman:hishaab-backend`, base package `in.moonman.hishaab` |
| DB | Postgres 16+, Flyway |
| Tests | JUnit 5, AssertJ, Spring Boot Test, **Testcontainers (Postgres)** |
| Module checks | **Spring Modulith** (`ApplicationModules.verify()`) |
| API docs | springdoc-openapi |
| Errors | RFC 9457 `ProblemDetail` |

### 2.2 Approved dependencies

Add when the spec that needs them starts, not before.

| Dependency | Needed from |
|---|---|
| spring-boot-starter-web, -validation, -data-jpa, -actuator, -security | SPEC-000 |
| postgresql, flyway-core, flyway-database-postgresql | SPEC-000 |
| spring-modulith-starter-core, spring-modulith-starter-test | SPEC-000 |
| testcontainers (postgresql, junit-jupiter), spring-boot-testcontainers | SPEC-000 |
| springdoc-openapi-starter-webmvc-ui | SPEC-044 |
| spring-boot-starter-oauth2-client, -oauth2-resource-server | SPEC-050 |
| Google API client for Gmail **or** Jakarta Mail (IMAP) — decided in SPEC-020. **Optional feature:** added only when the real Gmail client is built | SPEC-020 (optional) |
| firebase-admin | SPEC-036 |
| Spring AI Gemini starter (the one matching the Boot version) | SPEC-060 |

Lombok: **not used**. Use Java records for DTOs and value objects.

### 2.3 Package layout

```
in.moonman.hishaab
├── HishaabBackendApplication
├── shared/            // money, time, ids, errors, paging — no business logic
├── ingest/            // public API at package root, internals in ingest/internal
├── mail/
├── classify/
├── parse/
├── match/
├── categorise/
├── review/
├── query/
├── chat/
├── report/
├── notify/
├── auth/
├── llm/
├── settings/
└── device/
```

Inside each module:
```
<module>/
├── <Module>Service.java      // public interface other modules may call
├── <Module>Events.java       // public event records (optional)
├── web/                      // controllers + request/response records
└── internal/                 // entities, repositories, implementation
```

Cross-module side effects go through **application events** (`@ApplicationModuleListener`), not direct calls, where the HLD pipeline is a chain (ingest → classify → parse → match → categorise).

### 2.4 Coding conventions

| Topic | Convention |
|---|---|
| IDs | `UUID` (v7 if available, else v4) for new rows; `raw_message.id` is a string hash |
| Time | Store `TIMESTAMPTZ`; use `Instant` in domain; convert to `Asia/Kolkata` only for parsing message text and API output formatting |
| Money type | `shared.Money` record wrapping `long paise`; parser helper `Money.parseInr(String)` |
| Enums | Stored as `TEXT` with a `CHECK` constraint in SQL |
| API prefix | `/api/v1/...` |
| API style | JSON, camelCase, ISO-8601 times with offset, money as integer `amountPaise` |
| Paging | Cursor-based: opaque `cursor` = base64url of `(sortKey, id)` |
| Validation | Jakarta Validation on request records; violations → 400 ProblemDetail |
| Config | `@ConfigurationProperties` records under `hishaab.*` |
| Profiles | `dev` (local, password login allowed), `test`, `prod` |
| Logging | SLF4J, structured key=value; message bodies only at `DEBUG` and masked |
| Migrations | `V{n}__{snake_case}.sql`, one concern per file. Version numbers written in specs that are not built yet are **placeholders**: the real file takes the next free number when the spec is built (built so far: V1 to V7) |

### 2.5 Global Definition of Done

A spec is `DONE` only when:
- every AC has a passing test,
- `./mvnw verify` passes (includes the Modulith structure test),
- migrations apply cleanly on an empty DB,
- new endpoints appear in the OpenAPI spec (from SPEC-044 onward),
- §3 and §6 of this file are updated.

---

## 3. Status tracker

Status: `TODO` · `IN PROGRESS` · `DONE` · `BLOCKED (reason)`

| Phase | Spec | Title | Depends on | Status |
|---|---|---|---|---|
| 0 Foundation | SPEC-000 | Skeleton, conventions, test setup | — | DONE |
| | SPEC-001 | Settings | 000 | DONE |
| 1 Ingest & parse | SPEC-010 | Devices & device tokens | 000 | DONE |
| | SPEC-011 | SMS ingest API | 001, 010 | DONE |
| | SPEC-012 | Classifier | 011 | DONE |
| | SPEC-013 | Parse templates + golden set | 012 | DONE |
| | SPEC-014 | Normalisation | 013 | DONE |
| | SPEC-015 | Processing orchestrator | 012–014 | DONE |
| 2 Email (optional) | SPEC-020 | Optional Gmail connection & mail fetcher | 015, 031, 033 | **DEFERRED** (skipped for now by Ayan, 2026-11-18; optional, nothing depends on it) |
| 3 Match & review | SPEC-030 | Accounts & merchants | 015 | DONE (transaction repoint on merges is proven in SPEC-031, AC-031-11) |
| | SPEC-031 | Matcher & transactions | 030 | TODO |
| | SPEC-032 | Special kinds | 031 | TODO |
| | SPEC-033 | Review queue | 031 | TODO |
| | SPEC-034 | Overrides & rebuild | 031, 033 | TODO |
| | SPEC-035 | Balance check | 031, 033 | TODO |
| | SPEC-036 | Scheduler, scan runs, report, push | 033, 035 (020 optional; 046 for the investment lines) | TODO |
| 3b Investments | SPEC-045 | Investments & schedules | 030, 034 | TODO |
| | SPEC-046 | Instalment matching & lifecycle | 045, 031, 032, 033 | TODO |
| 4 API & auth | SPEC-040 | Transactions query | 031 | TODO |
| | SPEC-041 | Summary | 040 | TODO |
| | SPEC-042 | Edit, split, merge | 034 | TODO |
| | SPEC-043 | Categories & rules CRUD | 031 | TODO |
| | SPEC-044 | OpenAPI contract | 040 | TODO |
| | SPEC-050 | Auth (Google, mobile, tokens, dev login) | 010 | TODO |
| 5 AI | SPEC-060 | LLM gateway | 001 | TODO |
| | SPEC-061 | Categoriser (rules + AI) | 043, 060 | TODO |
| | SPEC-062 | LLM fallback parser | 060, 033 | TODO |
| 6 Chat | SPEC-070 | Chat agent & tools | 041, 060 | TODO |
| 9 Ops | SPEC-090 | Container, deploy, backup, CI | 050 | TODO |

Recommended order: 000 → 001 → 010 → 011 → 012 → 013 + 014 → 015 → 030 → 031 → 033 → 032 → 034 → 045 → 046 → 035 → 040 → 043 → 041 → 042 → 044 → 050 → 036 → 090 → 060 → 061 → 062 → 070. **SPEC-020 (optional Gmail)** can be built any time after 031 + 033; the app is complete without it.

---

## 4. Shared contracts

These are used by more than one component. Changing them needs a decision in §6 and a matching change in the worker / apps.

### 4.1 SMS message ID (shared with the Android worker)

```
id = lowercase_hex( SHA-256( UTF8( sender + "\n" + body + "\n" + receivedAtEpochMillis ) ) )
```
- `sender`: exactly as Android reports it (e.g. `AX-AXISBK-S`), no trimming or case change.
- `body`: exactly as stored in the inbox, no trimming, no newline changes.
- `receivedAtEpochMillis`: the inbox `date` column as a decimal integer string.

### 4.2 Email message ID

```
id = "gmail:" + <Gmail message id>      // or "imap:" + <Message-ID header> if IMAP is chosen
```

### 4.3 Enums

| Enum | Values |
|---|---|
| `Source` | `SMS`, `EMAIL` |
| `RawStatus` | `NEW`, `CLASSIFIED`, `PARSED`, `IGNORED`, `FAILED`, `ERROR` |
| `MessageClass` | `TRANSACTION`, `INVESTMENT_CONFIRMATION`, `OTP`, `PROMO`, `MANDATE_REMINDER`, `FAILED_TXN`, `BALANCE_ONLY`, `STATEMENT`, `UNKNOWN` |
| `Direction` | `DR`, `CR` |
| `Channel` | `UPI`, `CARD`, `NEFT`, `IMPS`, `RTGS`, `ATM`, `AUTOPAY`, `OTHER` |
| `TxnKind` | `SPEND`, `INCOME`, `TRANSFER`, `REFUND`, `CC_PAYMENT`, `REVERSAL`, `INVESTMENT` |
| `TxnStatus` | `CONFIRMED`, `PENDING`, `HIDDEN` |
| `CategorySource` | `USER`, `RULE`, `AI`, `NONE` |
| `MatchMethod` | `FIRST`, `REF`, `WINDOW`, `MANUAL` |
| `ReviewType` | `AMBIGUOUS_MATCH`, `UNPARSED`, `LOW_CONFIDENCE`, `UNKNOWN_CATEGORY`, `UNKNOWN_KIND`, `BALANCE_GAP`, `ORPHAN_OVERRIDE`, `INSTALMENT_AMBIGUOUS`, `SIP_SCHEDULE_SUGGESTION`, `EMAIL_ONLY`, `BALANCE_STALE` |
| `ReviewStatus` | `OPEN`, `RESOLVED`, `DISMISSED`, `AUTO_CLOSED` |
| `Bank` | `AXIS`, `SBI` (template metadata; fund houses are not banks) |
| `AccountType` | `SAVINGS`, `CREDIT_CARD` |
| `RecordType` | `TRANSACTION`, `INVESTMENT_CONFIRMATION` (on `parse_template` and `parsed_record`) |
| `InvestmentType` | `SIP`, `RD`, `FD`, `PPF`, `OTHER` |
| `Frequency` | `MONTHLY`, `QUARTERLY`, `YEARLY`, `EVERY_N_DAYS`, `EVERY_N_MONTHS`, `ONE_TIME` |
| `InstalmentStatus` | `SCHEDULED`, `CONFIRMED`, `MARKED_PAID`, `MISSED`, `SKIPPED`, `FAILED` |
| `InstalmentEvidenceKind` | `BANK_DEBIT`, `AMC_CONFIRMATION`, `FAILED_DEBIT` |
| `SourceMode` | `SMS_ONLY` (default), `SMS_AND_EMAIL` |
| `MailStatus` | `DISCONNECTED`, `CONNECTED`, `ERROR`, `REAUTH_REQUIRED` |

### 4.4 Error format

All errors return `application/problem+json`:
```json
{ "type": "https://hishaab/errors/validation", "title": "Invalid request", "status": 400,
  "detail": "messages[3].receivedAt must not be null", "code": "VALIDATION_FAILED" }
```
`code` is a stable machine string; `detail` is for humans.

---

## 5. Specs

---

### SPEC-000 — Skeleton, conventions, test setup

**Goal:** A running, tested, empty service with the module structure and test harness every later spec relies on.

**Depends on:** —

**Scope**
- Packages from §2.3 created (with `package-info.java` marking each as a Modulith application module).
- `shared` package: `Money`, `Ids`, `ProblemDetail` advice, `Cursor` encode/decode.
- `application.yml` with `dev`, `test`, `prod` profiles; DB settings from env vars.
- Health endpoint open; everything else authenticated (temporary basic auth in `dev`).
- Testcontainers base class for integration tests.
- `.env.example`, `.gitignore`.

**Rules**
- R-000-1 `ApplicationModules.of(HishaabBackendApplication.class).verify()` must pass.
- R-000-2 `Money.parseInr` accepts `Rs.1,234.50`, `Rs 1234`, `INR 1,234.5`, `₹1,23,456.00`, `1234` and returns paise; rejects negatives, empty, more than 2 decimals.
- R-000-3 Any unhandled exception returns a 500 ProblemDetail with `code=INTERNAL_ERROR` and no stack trace in the body.

**Acceptance criteria**
- AC-000-1 Given the app starts on an empty DB, Flyway applies all migrations and `/actuator/health` returns `UP`.
- AC-000-2 Given the module test runs, it passes; adding a call from one module into another's `internal` package makes it fail.
- AC-000-3 `Money.parseInr("₹1,23,456.78")` = `12345678`; `Money.parseInr("Rs.50")` = `5000`; `Money.parseInr("12.345")` throws.
- AC-000-4 A request to an unknown authenticated path without credentials returns 401.

**Tests:** `ModularityTest`, `MoneyTest`, `HealthIT`, `ErrorHandlingIT`.

**Out of scope:** real auth (SPEC-050).

**Tasks**
- [x] Generate base project (Boot 4.x, Java 25, Maven)
- [x] `V1__init.sql` with `raw_message`
- [x] Add Modulith + Testcontainers dependencies
- [x] Create module packages and `package-info.java`
- [x] `shared` utilities + tests
- [x] ProblemDetail advice
- [x] Profiles + `.env.example`

---

### SPEC-001 — Settings

**Goal:** Typed, runtime-editable settings stored in the DB (scan time, sender allowlist, matching thresholds).

**Depends on:** 000

**Data** — `V2__settings.sql`
```sql
CREATE TABLE settings (
  key        TEXT PRIMARY KEY,
  value      JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Seeded keys and defaults:

| Key | Default | Used by |
|---|---|---|
| `scan.cron` | `"0 0 22 * * *"` (22:00 IST) | worker config, SPEC-036 |
| `scan.zone` | `"Asia/Kolkata"` | SPEC-036 |
| `scan.serverDelayMinutes` | `15` | SPEC-036 |
| `senders.allowlist` | `["*"]` = accept every sender (V5; was `[]`). Can be narrowed to explicit IDs later | SPEC-011, worker |
| `match.smsEmailWindowMinutes` | `180` | SPEC-031 |
| `ai.categoryConfidenceMin` | `0.80` | SPEC-061 |
| `backfill.days` | `31` | worker, SPEC-020 |
| `balance.staleDays` | `14` — *added by SPEC-035 (new migration)* | SPEC-035 |
| `mail.fromAllowlist` | `["*"]` — *added by SPEC-020 (new migration)* | SPEC-020 |

There is deliberately **no `mail.enabled` setting**: whether email is on is the state of the Gmail connection (SPEC-020), which needs a valid token, not just a flag.

**API**
- `GET /api/v1/settings` → all settings as an object.
- `PATCH /api/v1/settings` → partial update; validated per key.

**Rules**
- R-001-1 Unknown keys are rejected (400 `UNKNOWN_SETTING`).
- R-001-2 `scan.cron` must be a valid Spring 6-field cron; invalid → 400 `INVALID_CRON`.
- R-001-3 Each allowlist entry is the stable middle part of the sender ID (e.g. `AXISBK`), uppercase, 3–8 letters, or the wildcard `"*"` meaning **accept every sender**. With `"*"` the sync worker must do its own bank/fund-house filtering before upload (HLD §5.7).
- R-001-4 Changing a setting publishes `SettingsChanged(key)`.

**Acceptance criteria**
- AC-001-1 Fresh DB → `GET /settings` returns all defaults above.
- AC-001-2 `PATCH {"scan.cron":"bad"}` → 400 `INVALID_CRON`, value unchanged.
- AC-001-3 `PATCH {"senders.allowlist":["AXISBK","sbiinb"]}` stores `["AXISBK","SBIINB"]`.
- AC-001-4 A successful patch publishes `SettingsChanged`.

**Tasks**
- [x] Migration + seed
- [x] `SettingsService` with typed getters
- [x] Controller + validation
- [x] Event publishing

---

### SPEC-010 — Devices & device tokens

**Goal:** Register the phone and give it a token that can only upload SMS and read its config.

**Depends on:** 000

**Data** — `V3__device.sql`
```sql
CREATE TABLE device (
  id            UUID PRIMARY KEY,
  name          TEXT NOT NULL,
  token_hash    TEXT NOT NULL UNIQUE,
  fcm_token     TEXT,
  sms_cursor_ms BIGINT,
  last_sync_at  TIMESTAMPTZ,
  revoked_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE raw_message ADD COLUMN device_id UUID REFERENCES device(id);
```

**API**
- `POST /api/v1/devices` (user auth) `{name}` → `{deviceId, deviceToken}` — token shown **once**.
- `GET /api/v1/devices` (user auth) → list (no tokens).
- `DELETE /api/v1/devices/{id}` (user auth) → revoke.
- `PATCH /api/v1/devices/me` (device auth) `{fcmToken}`.
- `GET /api/v1/device/config` (device auth) → `{senderAllowlist, scanCron, scanZone, backfillDays, cursorMs}`.

**Rules**
- R-010-1 Token = 32 random bytes, base64url, prefix `hdt_`. Only its SHA-256 is stored.
- R-010-2 Device auth = `Authorization: Bearer hdt_...`. Grants role `DEVICE` only.
- R-010-3 `DEVICE` may call only `/api/v1/ingest/**`, `/api/v1/device/**`, `PATCH /api/v1/devices/me`. Everything else → 403.
- R-010-4 Revoked token → 401.

**Acceptance criteria**
- AC-010-1 Creating a device returns a token starting `hdt_`; DB holds only its hash.
- AC-010-2 Device token on `GET /api/v1/transactions` → 403.
- AC-010-3 After revoke, the same token on `/device/config` → 401.
- AC-010-4 `/device/config` returns current settings values and the device's `cursorMs`.

**Note:** until SPEC-050, "user auth" = the temporary dev basic auth.

**Tasks**
- [x] Migration
- [x] Token generation + hashing
- [x] Security filter for device tokens
- [x] Controllers

---

### SPEC-011 — SMS ingest API

**Goal:** Accept raw bank SMS from the worker safely, idempotently, in batches. HLD §4.3, §5.3.

**Depends on:** 001, 010

**API** — `POST /api/v1/ingest/sms` (device auth)

Request:
```json
{ "messages": [
  { "id": "<sha256 hex>", "sender": "AX-AXISBK-S", "body": "...", "receivedAtMs": 1763400000000 }
]}
```
Response 200:
```json
{ "accepted": ["<id>"], "duplicate": ["<id>"],
  "rejected": [ { "id": "<id>", "reason": "HASH_MISMATCH" } ],
  "cursorMs": 1763400000000 }
```

**Rules**
- R-011-1 Batch size 1–500, else 400.
- R-011-2 Server recomputes the ID (§4.1). Mismatch → item rejected `HASH_MISMATCH`.
- R-011-3 Sender's middle part not in `senders.allowlist` → rejected `SENDER_NOT_ALLOWED` (defence in depth; the worker should already filter). If the allowlist contains `"*"`, every sender is accepted.
- R-011-4 Existing ID → listed in `duplicate`, not stored again, not an error.
- R-011-5 Each accepted message is stored with `source=SMS`, `status=NEW`, `device_id`, and publishes `RawMessageReceived(id)` **after commit**.
- R-011-6 `cursorMs` in the response = highest `receivedAtMs` among `accepted` ∪ `duplicate`, and is saved on the device row together with `last_sync_at`. It never moves backwards.
- R-011-7 One bad item never fails the whole batch.

**Acceptance criteria**
- AC-011-1 3 valid new messages → all `accepted`, 3 rows, 3 events.
- AC-011-2 Same batch sent twice → second response has all 3 in `duplicate`; still 3 rows.
- AC-011-3 One message with a wrong ID → that one `rejected HASH_MISMATCH`, the others accepted.
- AC-011-4 Sender `VM-PROMOS` with allowlist `[AXISBK]` → rejected `SENDER_NOT_ALLOWED`.
- AC-011-5 Batch whose highest time is older than the device's stored cursor → cursor unchanged.
- AC-011-6 501 messages → 400 `BATCH_TOO_LARGE`.
- AC-011-7 Allowlist `["*"]` → a fund-house sender and a bank sender are both accepted.

**Tests:** `IngestSmsIT` (Testcontainers), `SmsIdTest` (shared vectors also used by the worker).

**Tasks**
- [x] Request/response records + validation
- [x] Hash check + allowlist check
- [x] Insert with `ON CONFLICT DO NOTHING`
- [x] Cursor update
- [x] After-commit event
- [x] Publish 3 fixed test vectors (input → hash) in `src/test/resources/contract/sms-id-vectors.json` for the worker to reuse

---

### SPEC-012 — Classifier

**Goal:** Decide what a raw message is before any parsing. HLD §4.3 "Classify first".

**Depends on:** 011

**Scope:** rule-based classifier per bank. Keyword and pattern sets live in `src/main/resources/classify/*.yml`.

**Rules**
- R-012-1 Output one `MessageClass` (§4.3) plus a short `reason` string.
- R-012-2 Check order: `OTP` → `INVESTMENT_CONFIRMATION` → `PROMO` → `FAILED_TXN` → `MANDATE_REMINDER` → `BALANCE_ONLY` → `STATEMENT` → `TRANSACTION` → `UNKNOWN`. First match wins.
- R-012-3 `TRANSACTION` needs both an amount pattern and a completed-action word (e.g. *debited*, *credited*, *spent*, *withdrawn*, *sent*, *received*). Future tense (*will be debited*, *due*, *scheduled*) is `MANDATE_REMINDER`.
- R-012-4 Result: `TRANSACTION` and `INVESTMENT_CONFIRMATION` → status `CLASSIFIED`, publish `MessageClassified`; `FAILED_TXN` → `FAILED`; `UNKNOWN` → status `CLASSIFIED` with class `UNKNOWN` (handled by SPEC-015); all others → `IGNORED` with `ignore_reason` = class.
- R-012-5 The classifier is a pure function `classify(sender, body) → Classification` with no DB access, so it is easy to test.

**Acceptance criteria** (bodies below are illustrative; real ones come from the golden set)
- AC-012-1 "123456 is your OTP for txn of Rs 500..." → `OTP` (even though it contains an amount).
- AC-012-2 "Rs 499 will be debited on 20-11 for your mandate..." → `MANDATE_REMINDER`.
- AC-012-3 "Txn of Rs 250 failed/declined..." → `FAILED_TXN`.
- AC-012-4 "Avl Bal in A/c XX1234 is Rs 10,000" (no debit/credit) → `BALANCE_ONLY`.
- AC-012-5 "Rs 450.00 debited from A/c XX1234 ... UPI Ref 4321..." → `TRANSACTION`.
- AC-012-6 Every message in the golden set (SPEC-013) is classified as its fixture says.
- AC-012-7 A fund-house allotment ("Your SIP Purchase … Rs.999.95 … processed … NAV …") → `INVESTMENT_CONFIRMATION`; it is never a `TRANSACTION`.
- AC-012-8 A fund-house reminder ("… SIP … will be debited on …" / "… is due for debit …") and a mandate-registration notice → `MANDATE_REMINDER`.
- R-012-6 `INVESTMENT_CONFIRMATION` = mentions a folio **and** says the purchase was processed/allotted **and** mentions units or NAV. It is checked right after OTP so an allotment is never mistaken for a bank debit (SPEC-046, HLD D-24).

**Tasks**
- [x] Classification record + rules loader
- [x] Ordered rule engine
- [x] Unit tests for AC-012-1..5
- [x] Hook into golden runner when SPEC-013 lands

---

### SPEC-013 — Parse templates + golden set

**Goal:** Pull fields out of `TRANSACTION` messages using versioned, bank-specific regex templates, proven by a golden test set.

**Depends on:** 012 · Samples received 2026-11-18 (Axis SMS + email, SBI SMS + email, fund-house SIP SMS + email). They are used **only as a reference for message shapes**; see the fixture rule below. No SBI Card (HLD D-23).

**Golden set layout**
```
src/test/resources/golden/
  axis/sms/<shape>-<n>.json
  axis/email/...
  sbi/sms/...
  sbi/email/...
  amc/sms/...        # mutual-fund houses (SIP reminders, allotments)
  amc/email/...
```
Each fixture:
```json
{
  "input":    { "source": "SMS", "sender": "AX-AXISBK-S", "body": "...", "receivedAtMs": 1763400000000 },
  "expected": { "class": "TRANSACTION",
                "fields": { "amountPaise": 45000, "direction": "DR", "accountLast4": "1234",
                            "channel": "UPI", "counterpartyRaw": "SWIGGY", "refNo": "432112345678",
                            "txnTime": "2026-11-17T21:42:00+05:30", "balanceAfterPaise": null },
                "templateId": "axis.sms.upi_debit" }
}
```

**Fixture rule (HLD D-25):** fixtures are **synthetic**. Each one copies the *shape* of a real message (wording, punctuation, line breaks, field order, ref/date formats) but every value is invented: names, merchants, refs, account digits, balances, folios, phone numbers, links. No real value from the samples may appear in the repo. Email bodies are reconstructed from rendered emails as `subject + "\n\n" + text` (R-020-3); re-check them against the real `text/plain` part when SPEC-020 lands.

**Coverage rule (replaces the earlier 30% hold-out):** every template has at least one fixture copying a real sample's shape **plus at least two synthetic variants** (different amounts incl. thousands separators, merchants, dates, spacing/line breaks, letter case). Every sample shape that fits no template is a fixture too, expecting no template (→ UNPARSED or ignored).

**Templates** — `src/main/resources/parse-templates/<bank>.yml` (top-level key `templates:`, then a list)
```yaml
templates:
- id: axis.sms.upi_debit
  version: 1
  bank: AXIS
  source: SMS
  senderPattern: "AXISBK"
  regex: "(?s)INR (?<amount>[\\d,]+\\.\\d{2}) debited.*?A/c no\\. XX(?<last4>\\d{4}).*?UPI/P2[AM]/(?<ref>\\d{12})/(?<cp>[^\\n]+)"
  fixed: { direction: DR, channel: UPI }
  accountType: SAVINGS            # SAVINGS | CREDIT_CARD (used by R-030-1)
  recordType: TRANSACTION         # TRANSACTION | INVESTMENT_CONFIRMATION
```
Named groups recognised: `amount`, `dir`, `last4`, `channel`, `ref`, `cp`, `date`, `time`, `datetime`, `bal`. `fixed:` sets fields the text doesn't spell out. `senderPattern` is a regex that must fully match the sender's middle part (SMS, e.g. `AXISBK`) or the lowercased from-address (EMAIL). Templates are case-insensitive and should use `\s+` wherever banks vary spacing or line breaks.

**Data** — `V4__parse.sql`: `parse_template` (id, version, bank, source, sender_pattern, regex, regex_hash, fixed JSONB, account_type, record_type, active; PK `(id, version)`) and `parsed_record` (HLD §4.7 columns, plus `template_id`, `template_version`, `parser` `TEMPLATE|LLM`, `record_type`, `confidence`; one per raw message).

**Rules**
- R-013-1 Templates are loaded from YAML at startup and upserted into `parse_template` by `(id, version)`. Changing a regex means a **new version**; old versions stay for audit.
- R-013-2 Candidate templates = matching `source` and `senderPattern`. Try in YAML file order (files sorted by name); the first template whose regex is **found** in the body and whose captures normalise cleanly (SPEC-014) wins.
- R-013-3 A template match must yield at least `amount` and `direction`; otherwise it doesn't count as a match.
- R-013-4 No match → no `parsed_record`; message goes to the unparsed path (SPEC-015).
- R-013-5 Template parse confidence = `1.0`.
- R-013-6 Fund-house allotment templates have `recordType: INVESTMENT_CONFIRMATION`; `ref` = folio digits, `cp` = scheme name. These records never become transactions (R-031-8); SPEC-046 consumes them.

**Acceptance criteria**
- AC-013-1 A parameterised golden test runs every fixture; each produces exactly the expected class, template ID and fields.
- AC-013-2 Every active template is used by at least one fixture (no untested template).
- AC-013-3 Changing a template's regex without bumping `version` fails a startup check (hash of regex stored per version).

**Tasks**
- [x] Receive samples (Ayan) — used as shape reference only
- [x] Fixture loader + parameterised golden test
- [x] Template YAML loader + DB upsert
- [x] Template engine
- [x] Templates for Axis, SBI (SMS + email) and fund-house SIP allotments (SMS + email)

---

### SPEC-014 — Normalisation

**Goal:** Turn template captures into clean typed fields.

**Depends on:** 013

**Rules**
- R-014-1 `amount`/`bal` → paise via `Money.parseInr`.
- R-014-2 `dir` words → `DR` (*debited, spent, withdrawn, sent, paid, purchase*) or `CR` (*credited, received, deposited, refund*). Unknown word → parse fails.
- R-014-3 Dates: accept at least `dd-MM-yy`, `dd-MM-yyyy`, `dd/MM/yy`, `dd/MM/yyyy`, `ddMMMyy`, `dd-MMM-yy`, `dd-MMM-yyyy`, `dd MMM yyyy`, `MMM dd, yyyy`, with optional `HH:mm[:ss]`. Month names are case-insensitive English; two-digit years are 20yy. Interpreted in `Asia/Kolkata`.
- R-014-4 `txnTime`: date+time from text if present; date only → that date at the received time-of-day, but if that is in the future then 23:59:59 of that date; nothing → `receivedAt`.
- R-014-5 `ref`: digits only kept; must be 6–22 digits, else `null`.
- R-014-6 `cp` (counterparty): trim, collapse spaces, strip trailing ref fragments; keep original case in `counterparty_raw`.
- R-014-7 `last4`: exactly 4 digits else `null`.
- R-014-8 `channel` inferred if not captured: contains `UPI` → UPI; `NEFT`/`IMPS`/`RTGS` → that; `ATM` → ATM; `card`/`POS` → CARD; `NACH`/`ECS`/`mandate`/`autopay` → AUTOPAY; else OTHER.

**Acceptance criteria**
- AC-014-1 Table-driven tests for each date format in R-014-3.
- AC-014-2 Date-only text dated "today" with received time 21:40 → txnTime 21:40 IST that day.
- AC-014-3 "UPI Ref No 4321 1234 5678" → `432112345678`.
- AC-014-4 Golden set still passes (normalisation is part of its expected output).

**Tasks**
- [x] `Normaliser` + unit tests
- [x] Date parser with format list

---

### SPEC-015 — Processing orchestrator

**Goal:** Drive each raw message through classify → parse → normalise → match, safely and repeatably.

**Depends on:** 012, 013, 014

**Rules**
- R-015-1 Processing is triggered by `RawMessageReceived` and by `processPending()` (called by SPEC-036 and on startup).
- R-015-2 Only one processor runs at a time: `pg_try_advisory_lock(<fixed key>)`. If not acquired, the trigger is skipped; pending work is picked up by the next run.
- R-015-3 Messages are processed in `received_at` order, oldest first, in batches of 100.
- R-015-4 Each message is processed in **its own DB transaction**. A failure in one marks it `ERROR` with the exception class in `ignore_reason` and moves on.
- R-015-5 `TRANSACTION` + template match → `parsed_record` → status `PARSED` → publish `RecordParsed(parsedRecordId)`.
- R-015-6 `TRANSACTION` or `INVESTMENT_CONFIRMATION` + no template, or class `UNKNOWN` with an amount pattern → status `PARSED` is **not** set; a review item `UNPARSED` is created (until SPEC-062 adds LLM fallback).
- R-015-8 `INVESTMENT_CONFIRMATION` + template match → `parsed_record` with `record_type=INVESTMENT_CONFIRMATION`, status `PARSED`, publish `RecordParsed`; the matcher skips it (R-031-8) and SPEC-046 consumes it.
- R-015-7 `reprocess(rawMessageId)` and `reprocessAll()` exist for rebuilds (SPEC-034) and clear derived rows for those messages first.

**Acceptance criteria**
- AC-015-1 Two triggers at once → only one processes; no message processed twice.
- AC-015-2 A message that throws during parsing → `ERROR`; the next message in the batch is still processed.
- AC-015-3 An OTP message ends `IGNORED` with reason `OTP` and no parsed record.
- AC-015-4 A transaction-like message with no template → one `UNPARSED` review item, no transaction.

**Tasks**
- [x] Advisory lock helper in `shared`
- [x] Orchestrator + listeners
- [x] Reprocess API (internal service; no HTTP yet)

---

### SPEC-020 — Optional Gmail connection & mail fetcher

**Goal:** Let you *optionally* connect Gmail so bank emails become **extra evidence** for transactions that SMS already found. The default is SMS-only and the app is fully usable without this spec. HLD D-27, §4.4, §7.2.

**Depends on:** 015, 031, 033 · The real Gmail client waits on HLD O-02 / Q-2. **Nothing else depends on this spec.**

**Decision needed before the real client (record in §6):**
- Option A — **Gmail API** (`gmail.readonly`): needs an OAuth client. Risk: if the OAuth app stays in Google's "Testing" status, refresh tokens may expire after about a week (→ `REAUTH_REQUIRED`, R-020-7). Must verify current Google policy and either publish the app or accept re-consent.
- Option B — **IMAP** with a Google app password (requires 2-Step Verification). Simpler token handling, but we cannot revoke an app password (R-020-10).

**Data** — `V?__mail_connection.sql` (placeholder number, §2.4)
```sql
CREATE TABLE mail_connection (
  id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),     -- one user, one mailbox
  provider         TEXT NOT NULL CHECK (provider IN ('GMAIL_API', 'IMAP')),
  status           TEXT NOT NULL CHECK (status IN ('DISCONNECTED', 'CONNECTED', 'ERROR', 'REAUTH_REQUIRED')),
  account_masked   TEXT,                -- e.g. "a•••@gmail.com"; the full address is never logged or returned
  token_encrypted  BYTEA,               -- AES-GCM (JDK), key from env HISHAAB_TOKEN_ENC_KEY; NULL when disconnected
  connected_at     TIMESTAMPTZ,
  disconnected_at  TIMESTAMPTZ,
  last_fetch_at    TIMESTAMPTZ,
  last_error       TEXT,
  backfill_done_at TIMESTAMPTZ,
  CHECK (status <> 'DISCONNECTED' OR token_encrypted IS NULL)
);
```
No row, or `status = DISCONNECTED`, means **SMS-only mode**. The same migration adds the setting `mail.fromAllowlist` (default `["*"]`, SPEC-001).

**Scope** — in `mail`:
```java
public interface MailSource {                       // real Gmail/IMAP client + a fake for tests
  List<RawEmail> fetchSince(Instant since, Set<String> fromAddresses);
  void revoke();                                    // best effort; IMAP: no-op
}
record RawEmail(String id, String from, String subject, String textBody, Instant receivedAt) {}

public interface MailConnectionService {
  MailConnectionView status();   // {mode, status, provider, accountMasked, connectedAt, lastFetchAt, lastError}
  SourceMode mode();             // SMS_ONLY unless a connection exists that is not DISCONNECTED
  MailConnectionView disconnect();
}
```

**API** (user auth)
- `GET /api/v1/mail/connection` → `{mode, status, provider, accountMasked, connectedAt, lastFetchAt, lastError}` — never the token.
- `POST /api/v1/mail/connection` `{provider}` → Gmail API: `{authorizationUrl}` (separate consent, scope `gmail.readonly` only); IMAP: body also carries the app password, validated immediately → `200` with the status.
- `GET /api/v1/mail/oauth/callback` → stores the token, status `CONNECTED`, starts the backfill, redirects to Settings.
- `DELETE /api/v1/mail/connection` → disconnect → `200` with the new status (and `revoked: true|false`).

**Rules**
- R-020-1 Only emails from configured sender addresses (`settings: mail.fromAllowlist`) are fetched. Default `["*"]` (all senders) until bank and fund-house addresses are pinned (Q-3, Q-8); classification then decides what is a transaction, an allotment or noise.
- R-020-2 HTML bodies are converted to plain text before storage (keep line breaks; drop scripts/styles/images).
- R-020-3 Stored as `raw_message` with `source=EMAIL`, `id` per §4.2, `sender` = from address, `body` = `subject + "\n\n" + textBody`.
- R-020-4 `fetchSince` start = last successful fetch time minus 1 day overlap (duplicates are harmless due to IDs). The first fetch after (re)connecting = now − `backfill.days` (**backfill**).
- R-020-5 The OAuth client ID/secret come from env vars. The user's token is stored only in `mail_connection.token_encrypted` (AES-GCM, key `HISHAAB_TOKEN_ENC_KEY`, listed in `.env.example` with a fake value); never logged (H-6), never returned by the API.
- R-020-6 **SMS-only is the default.** With no connection or `DISCONNECTED`, the mail step does nothing and makes no network call; every other feature works the same.
- R-020-7 Mail is fetched only while `status = CONNECTED`. An auth failure while fetching → `REAUTH_REQUIRED`; any other failure → `ERROR` with `last_error`. Either way fetching pauses until you reconnect, and the scan carries on with SMS (R-036-2).
- R-020-8 Connecting validates by fetching one page from the mailbox; failure → stays SMS-only, 400 `MAIL_CONNECT_FAILED`.
- R-020-9 **Enabling never duplicates.** Backfilled and new emails go through the normal pipeline; under R-031-9 an email can only attach to an existing transaction as evidence or open a review item. It never creates a transaction, so the transaction count cannot go up because email was turned on.
- R-020-10 **Disconnect:** stop fetching immediately; revoke the token at the provider (Gmail API: Google's token-revocation endpoint; IMAP: we cannot revoke an app password, so the response tells you to delete it in your Google account); **delete `token_encrypted`**; set `DISCONNECTED` and `disconnected_at`. A failed revocation still deletes the local token and is reported (`revoked: false`). Raw emails already stored stay (H-2), and so does their evidence on transactions; open `EMAIL_ONLY` items stay answerable.
- R-020-11 Connect/disconnect write `audit_log` and publish `MailConnectionChanged(status)`; the mode shows on the dashboard (SPEC-041) and in every scan report (SPEC-036).

**Acceptance criteria**
- AC-020-1 With a fake `MailSource` returning 3 emails (1 from a non-allowlisted sender) → 2 stored.
- AC-020-2 Running the fetch twice over the same window → no extra rows.
- AC-020-3 HTML fixture → expected plain text.
- AC-020-4 **Enable on existing SMS data → zero new duplicates.** Given 30 days of SMS-derived transactions (N of them) and a fake mailbox holding emails for 20 of them (10 carrying the same ref, 10 without a ref but inside the window) plus 2 emails with no SMS counterpart: after connect + backfill + processing, the transaction count is **still N**, each of the 20 has one extra `EMAIL` evidence row, 2 `EMAIL_ONLY` review items are open, and `spentPaise` for the period is unchanged.
- AC-020-5 **Disconnect removes the token.** Connect, then `DELETE /api/v1/mail/connection` → the fake's `revoke()` is called once, `token_encrypted IS NULL`, status `DISCONNECTED`, the next scan makes no mail call, and stored emails plus their evidence rows are unchanged. With `revoke()` failing, the token is still deleted and the response says `revoked: false`.
- AC-020-6 `GET /api/v1/mail/connection` shows mode, status, masked account and `lastFetchAt` after a fetch, and never the token.
- AC-020-7 Fetch with an expired token → status `REAUTH_REQUIRED`; the same scan still processes SMS and finishes `PARTIAL`.
- AC-020-8 Connect with bad credentials → 400 `MAIL_CONNECT_FAILED`; mode stays `SMS_ONLY`.

**Tasks**
- [ ] Decide A/B (Ayan) and record — only needed for the real client
- [ ] Migration (`mail_connection`, `mail.fromAllowlist`)
- [ ] `MailSource` interface + fake; `MailConnectionService`; token encryption
- [ ] Connect / callback / status / disconnect API
- [ ] Backfill on connect; HTML → text
- [ ] Real Gmail (or IMAP) implementation
- [ ] Wire into SPEC-036 (mail step only when `CONNECTED`)

---

### SPEC-030 — Accounts & merchants

**Goal:** Resolve `accountLast4` + bank to an account, and raw counterparty strings to merchants.

**Depends on:** 015

**Data** — built as `V7__accounts_merchants.sql`: `account`, `account_alias`, `merchant`, `merchant_alias` (HLD §4.7). Unique `(bank, last4)` on account; `account_alias(id, account_id, bank, last4, kind TEXT CHECK (kind IN ('DEBIT_CARD','CREDIT_CARD','OTHER')))` with unique `(bank, last4)` and a check that an alias `(bank, last4)` is not also an account's own `(bank, last4)`; unique `raw_name_normalised` on merchant alias.

**Rules**
- R-030-1 Unknown `(bank, last4)` → account auto-created with `type` = the matching template's `accountType` (`SAVINGS` or `CREDIT_CARD`) and `nickname = bank + " ••" + last4` (cards: `bank + " card ••" + last4`).
- R-030-2 Alias normalisation: uppercase, remove `*`, digits runs ≥ 4, and common suffixes (`PVT LTD`, `LIMITED`, `INDIA`, `BLR`, city codes list), collapse spaces.
- R-030-3 Unknown normalised alias → new merchant with `type`: `PERSON` if the raw string looks like a personal UPI ID or a name of 2–3 words with no business keywords; else `MERCHANT`.
- R-030-4 Merchants can be merged (`mergeMerchants(from, into)`): aliases move, transactions repoint.
- R-030-5 **Card aliases** (Q-9): a debit card's last 4 can be registered as an alias of a savings account. Resolution order for `(bank, last4)`: account's own last4 → `account_alias` → otherwise auto-create (R-030-1).
- R-030-6 Merging an account into another (`mergeAccounts(from, into)`, e.g. an auto-created "Axis ••8820" into "Axis ••7311"; same bank and type only, else 400 `ACCOUNT_MERGE_MISMATCH`) moves its aliases, publishes `AccountMerged` (listeners repoint their rows in the same transaction: transactions in SPEC-031, investments in SPEC-045), turns `from`'s last4 into an alias of `into`, and deletes `from`. It survives rebuilds because accounts and aliases are not derived data (R-034-3 never deletes them); the audit entry is added with SPEC-034's `audit_log`.
- R-030-7 API (user auth): `GET /api/v1/accounts`, `PATCH /api/v1/accounts/{id}` (nickname), `POST /api/v1/accounts/{id}/aliases` `{last4, kind}`, `DELETE /api/v1/accounts/{id}/aliases/{aliasId}`, `POST /api/v1/accounts/{id}/merge-into/{targetId}` (optional body `{aliasKind}`). Merchants (HLD §4.11): `GET /api/v1/merchants?q=&limit=`, `PATCH /api/v1/merchants/{id}` `{name?, type?, alwaysAsk?}` (fix a wrong PERSON/MERCHANT guess), `POST /api/v1/merchants/{id}/merge-into/{targetId}` (publishes `MerchantMerged`).

**Acceptance criteria**
- AC-030-1 `SWIGGY*BLR` and `Swiggy Ltd` resolve to the same merchant.
- AC-030-2 `rahul.k@okaxis` → `PERSON`.
- AC-030-3 Merge moves aliases and transactions; the old merchant is deleted.
- AC-030-4 With alias `8820 → Axis ••7311`, an Axis AutoPay email for card XX8820 resolves to the savings account; no new account is created.
- AC-030-5 Merging auto-created "Axis ••8820" into "Axis ••7311" moves its transactions, creates the alias, deletes the old account; after rebuild the result is the same.
- AC-030-6 Adding alias `7311` to another account when an account already owns `7311` → 400 `ALIAS_CONFLICT`.

**Tasks**
- [x] Migration
- [x] Resolver services + tests

---

### SPEC-031 — Matcher & transactions

**Goal:** Turn parsed records into transactions with evidence, without duplicates. HLD §4.4.

**Depends on:** 030

**Data** — `V7__transactions.sql`: `transaction`, `transaction_evidence` (HLD §4.7) plus:
```sql
CREATE UNIQUE INDEX ux_txn_ref_dir ON transaction(ref_no, direction)
  WHERE ref_no IS NOT NULL AND status <> 'HIDDEN';
CREATE UNIQUE INDEX ux_evidence_record ON transaction_evidence(parsed_record_id);
```
(Ref + direction, because a reversal can reuse the original ref with the opposite direction.)

**Rules**
- R-031-1 **Ref match:** record has `refNo` and a transaction with same `(refNo, direction)` exists → attach evidence, `match_method=REF`. Never creates a second transaction.
- R-031-2 **Same-source rule (L3):** a record is never matched by window to a transaction that already has evidence from the **same source**. Two SMS → two transactions unless refs match.
- R-031-3 **Window match (L2):** no ref match → candidates = transactions with equal `amountPaise`, `direction`, account, and **no evidence from this record's source**, with `|txnTime − record.txnTime| ≤ match.smsEmailWindowMinutes`.
   - 0 candidates → **SMS record:** new transaction, `match_method=FIRST`, `status=CONFIRMED`; **email record:** see R-031-9.
   - 1 candidate and channels equal (or one unknown) → attach, `match_method=WINDOW`.
   - otherwise → **SMS record:** new transaction `status=PENDING` + review `AMBIGUOUS_MATCH` listing candidates; **email record:** no transaction, review `AMBIGUOUS_MATCH` on the record listing candidates.
- R-031-4 A new transaction copies fields from its first record; later evidence fills **only null** fields (e.g. an email bringing the ref no.), never overwrites.
- R-031-5 When later evidence adds a `refNo` that makes another `PENDING` transaction's open `AMBIGUOUS_MATCH` resolvable, that review item is `AUTO_CLOSED` and the pair is merged.
- R-031-6 New transactions start `kind=SPEND` for `DR`, `INCOME` for `CR`; SPEC-032 may change it. Publish `TransactionCreated` / `TransactionUpdated`.
- R-031-7 Whole match step for one record runs in one DB transaction; unique-index violation → retry once as a ref match.
- R-031-9 **An email never creates a transaction** (HLD D-27). An email record that matches no transaction by ref (R-031-1) or window (R-031-3) opens review `EMAIL_ONLY` (dedup key `EMAIL_ONLY:<parsedRecordId>`) and stays unlinked. Answers: `ADD` → one `CONFIRMED` transaction with the email as evidence (`match_method=MANUAL`, stored as a `user_override` so rebuilds keep it); `LINK` → attach to the chosen transaction; `IGNORE`.
- R-031-10 Whenever an SMS creates or changes a transaction, open `EMAIL_ONLY` items whose record now matches it (ref, or window rules) are attached and `AUTO_CLOSED`. So an email that arrives before its SMS ends up as evidence, not a duplicate.
- R-031-11 Listens to `AccountMerged` / `MerchantMerged` (SPEC-030) synchronously and repoints `transaction.account_id` / `merchant_id` in the same transaction.
- R-031-8 Records with `record_type=INVESTMENT_CONFIRMATION` (fund-house allotments) are **never** turned into transactions or used as window/ref candidates (HLD D-24); they go to SPEC-046.

**Acceptance criteria**
- AC-031-1 SMS with ref R, then email with ref R → 1 transaction, 2 evidence rows.
- AC-031-2 SMS without ref, email 40 min later same amount/account/direction → 1 transaction (`WINDOW`).
- AC-031-3 Email arrives 5 h later (window 180) → still **1** transaction (the SMS one) + one `EMAIL_ONLY` review item (R-031-9).
- AC-031-4 Two SMS, same amount/account, 2 min apart, no refs → **2 transactions**.
- AC-031-5 Two SMS candidates exist for one email → **no new transaction**; one `AMBIGUOUS_MATCH` review on the email listing both candidates.
- AC-031-6 Email adds ref to an SMS-only transaction → `ref_no` filled, nothing else overwritten.
- AC-031-7 The same parsed record processed twice → still one evidence row.
- AC-031-8 Email-only payment (no SMS ever) → no transaction, one `EMAIL_ONLY` item; answering `ADD` → one `CONFIRMED` transaction with the email as evidence; an SMS for it arriving later attaches by ref/window and does not create a second one.
- AC-031-9 Email first, its SMS 30 min later → the SMS creates the transaction, the email attaches as evidence, and the `EMAIL_ONLY` item is `AUTO_CLOSED`.
- AC-031-10 SMS-only mode (no email records at all): every SMS scenario above gives the same result as with Gmail connected.
- AC-031-11 Merging account A into B (or merchant A into B) moves A's transactions to B; totals per account/merchant add up before and after (completes AC-030-3 / AC-030-5).

**Tasks**
- [ ] Migration
- [ ] Matcher service (pure decision function + persistence wrapper)
- [ ] Scenario tests AC-031-1..7

---

### SPEC-032 — Special kinds

**Goal:** Stop transfers, card bill payments and reversals from inflating totals. HLD §4.4 table.

**Depends on:** 031

**Rules**
- R-032-1 **CC payment:** a `DR` on a savings account and a `CR` on a credit-card account, same amount, within 72 h, where either text mentions the card/biller (`card`, `credit card`, `CC payment`, `BillDesk`, card last4) → both `kind=CC_PAYMENT`, linked via `linked_txn_id`.
- R-032-2 **Own transfer:** `DR` on one own savings account + `CR` on another own savings account, same amount, within 24 h → both `TRANSFER`.
- R-032-3 **Reversal:** a `CR` with the same `refNo` as an earlier `DR` (or text says *reversed/reversal/refund of failed*) → both `REVERSAL`, `status=HIDDEN`.
- R-032-4 **Refund:** `CR` from a merchant that has an earlier `DR` (any amount ≤ original) within 60 days, text contains *refund* → `REFUND`, linked to the most recent such `DR`.
- R-032-5 Any rule that matches more than one candidate → review `UNKNOWN_KIND`; no automatic change.
- R-032-6 User-set kind (override) is never changed by these rules.
- R-032-7 `INVESTMENT` is set only by SPEC-046 (instalment match or your review answer); these rules never set or clear it.

**Data** — `V8__txn_links.sql`: `ALTER TABLE transaction ADD COLUMN linked_txn_id UUID REFERENCES transaction(id);`

**Acceptance criteria**
- AC-032-1 SBI debit ₹12,000 "Axis credit card payment" + Axis card "Payment of INR 12000 has been received" next day → both `CC_PAYMENT`, excluded from spend.
- AC-032-2 Axis → SBI ₹5,000 same day → both `TRANSFER`.
- AC-032-3 UPI DR ₹250 ref R, then CR ₹250 ref R → both `REVERSAL` + `HIDDEN`.
- AC-032-4 Two possible CC-payment partners → `UNKNOWN_KIND` review, kinds unchanged.

**Tasks**
- [ ] Migration
- [ ] Kind detector (runs on `TransactionCreated`)
- [ ] Tests

---

### SPEC-033 — Review queue

**Goal:** One place for every question the system needs answered. HLD §4.8.

**Depends on:** 031

**Data** — `review_item` **already exists** (created by SPEC-015 as `V6__review_item.sql`, with all review types and `dedup_key UNIQUE`); this spec adds the FK to `transaction` and the API. The V6 `type` CHECK and `ReviewService.ReviewType` do **not** yet include `EMAIL_ONLY` and `BALANCE_STALE`: the first spec that opens them (031 / 035) adds them in a new migration. Originally planned as `V9__review.sql`: `review_item` (HLD §4.7) with `transaction_id` nullable, `raw_message_id` nullable, `dedup_key TEXT UNIQUE` (so the same question is never opened twice).

**Payload shapes**

| Type | `payload` | Allowed `resolution` |
|---|---|---|
| `AMBIGUOUS_MATCH` | `{transactionId?, parsedRecordId?, candidateIds[]}` (`parsedRecordId` when the record is an email) | `{action:"MERGE", into:<id>}` or `{action:"KEEP_SEPARATE"}` |
| `UNPARSED` | `{rawMessageId}` | `{action:"MANUAL", fields:{...}}` or `{action:"IGNORE"}` |
| `LOW_CONFIDENCE` | `{parsedRecordId, fields}` | `{action:"CONFIRM"}` / `{action:"CORRECT", fields}` / `{action:"IGNORE"}` |
| `UNKNOWN_CATEGORY` | `{transactionId, suggestion?}` | `{categoryId, scope:"THIS"\|"ALWAYS", applyPast?:bool}` |
| `UNKNOWN_KIND` | `{transactionId, candidates[]}` | `{kind, linkTo?}` |
| `BALANCE_GAP` | `{accountId, from, to, expectedPaise, actualPaise}` | `{action:"ACK"}` or `{action:"ADD_MANUAL", fields}` |
| `ORPHAN_OVERRIDE` | `{overrideId}` | `{action:"DROP"}` or `{action:"REAPPLY", transactionId}` |
| `INSTALMENT_AMBIGUOUS` | `{transactionId?, rawMessageId?, candidateInstalmentIds[]}` | `{instalmentId}` or `{action:"NONE"}` |
| `EMAIL_ONLY` | `{parsedRecordId, rawMessageId, candidateIds[]}` | `{action:"ADD"}`, `{action:"LINK", transactionId}` or `{action:"IGNORE"}` |
| `BALANCE_STALE` | `{accountId, lastBalanceAt?, staleDays}` | `{action:"ACK"}` (re-opens if still stale after another `staleDays`) |
| `SIP_SCHEDULE_SUGGESTION` | `{transactionId?, rawMessageId?, suggestion:{name, type, amountPaise, accountId?, scheduleDay}}` | `{action:"CREATE_SCHEDULE", investment:{…}}`, `{action:"LINK", investmentId}` or `{action:"NOT_INVESTMENT"}` |

**API**
- `GET /api/v1/review?status=OPEN&type=` → list (newest first).
- `POST /api/v1/review/{id}/resolve` `{resolution}`.
- `POST /api/v1/review/{id}/dismiss`.
- `POST /api/v1/review/{id}/reopen`.

**Rules**
- R-033-1 Resolving applies the change **and** writes a `user_override` (SPEC-034) so it survives rebuilds.
- R-033-2 Resolution validated against the type's allowed shapes; invalid → 400.
- R-033-3 Resolving a `PENDING` transaction's last open item sets it `CONFIRMED`.
- R-033-4 `dedup_key` per type, e.g. `AMBIGUOUS_MATCH:<txnId>`.
- R-033-5 Each open/resolve publishes an event; open count is cheap to query.

**Acceptance criteria**
- AC-033-1 Opening the same question twice → one item.
- AC-033-2 `MERGE` resolution → evidence moves, duplicate transaction removed, survivor `CONFIRMED`.
- AC-033-3 `KEEP_SEPARATE` → both `CONFIRMED`.
- AC-033-4 Invalid resolution shape → 400, item still `OPEN`.

**Tasks**
- [ ] Migration
- [ ] Service + handlers per type
- [ ] Controller

---

### SPEC-034 — Overrides & rebuild

**Goal:** Your edits survive any rebuild of derived data. HLD D-06, D-22.

**Depends on:** 031, 033

**Data** — `V10__overrides_audit.sql`: `user_override` (HLD §4.7) and `audit_log(id, at, actor, entity, entity_id, action, before JSONB, after JSONB)`.

**Rules**
- R-034-1 An override is anchored to the **earliest raw message** among the transaction's evidence (`anchor_raw_message_id`), plus `field`, `value`, `created_at`.
- R-034-2 Override fields: `CATEGORY`, `KIND`, `NOTE`, `HIDE`, `MERGE_WITH` (other anchor), `SPLIT` (parts), `MANUAL_FIELDS`, `INSTALMENT_STATUS`. An `INSTALMENT_STATUS` override is anchored by `instalment_key = <investmentId>:<dueDate>` instead of a raw message (SPEC-045).
- R-034-3 `rebuild()`: take the processing lock → delete `transaction_evidence`, `transaction`, `parsed_record`, open non-user review items → reprocess all raw messages oldest-first → apply overrides in `created_at` order.
- R-034-4 Override whose anchor no longer maps to a transaction after rebuild → review `ORPHAN_OVERRIDE`.
- R-034-5 Every user change writes `audit_log`.
- R-034-6 `rebuild()` is exposed only as `POST /api/v1/admin/rebuild` for user auth, and returns a summary `{transactionsBefore, transactionsAfter, orphans}`.

**Acceptance criteria**
- AC-034-1 Set category of txn X → rebuild → X (found via anchor) has the same category, `category_source=USER`.
- AC-034-2 Merge two txns via review → rebuild → still merged.
- AC-034-3 Delete the anchor's evidence path (simulate parser change) → rebuild → one `ORPHAN_OVERRIDE`.
- AC-034-4 Rebuild on unchanged data → same transaction count and same totals as before.

**Tasks**
- [ ] Migration
- [ ] Override writer used by review and edit APIs
- [ ] Rebuild routine + endpoint
- [ ] Rebuild equivalence test

---

### SPEC-035 — Balance check

**Goal:** Detect missing or misread messages using the balance printed in bank SMS. In SMS-only mode (the default) there is no second source to cross-check, so this check is **mandatory on every scan**. HLD §4.9, D-27.

**Depends on:** 031, 033

**Rules**
- R-035-1 Per account, take confirmed+pending transactions ordered by `txn_time` that carry `balance_after_paise`.
- R-035-2 For each consecutive pair (A, B) with balances: `expected = A.balance + Σ CR − Σ DR` over transactions strictly after A up to and including B. If `expected ≠ B.balance` → review `BALANCE_GAP` with both values and the period.
- R-035-3 Hidden reversals are **included** in this sum (money did move).
- R-035-4 Runs at the end of **every** scan (SPEC-036), in both modes, for transactions touched since the last check. It is mandatory: no setting turns it off. If the check itself fails, the scan is `PARTIAL` and the problem push is sent (R-036-6).
- R-035-5 A gap that becomes consistent after new data arrives → its review item `AUTO_CLOSED`.
- R-035-6 Gap and stale checks apply to `SAVINGS` accounts only: credit-card SMS print the available *limit*, which is not a balance.
- R-035-7 **Stale balance:** a savings account that has transactions but whose newest balance-bearing transaction is older than `balance.staleDays` (default 14; new setting added by this spec), or which has none at all for that long → review `BALANCE_STALE` (dedup key `BALANCE_STALE:<accountId>`). Auto-closes when a balance-bearing SMS arrives. Accounts with no transactions in the period are not flagged.
- R-035-8 Each account exposes its check status (`OK`, `GAP`, `STALE`) and the date of its last balance-bearing SMS (Accounts screen, scan report).

**Acceptance criteria**
- AC-035-1 Balances 10,000 → (−450) → 9,550 → no gap.
- AC-035-2 Balances 10,000 → (−450) → 9,000 → `BALANCE_GAP` expected 9,550 actual 9,000.
- AC-035-3 A late SMS for the missing −550 arrives → gap auto-closes.
- AC-035-4 Savings account with transactions but last balance SMS 20 days ago, `staleDays` 14 → one `BALANCE_STALE`; scanning again → still one.
- AC-035-5 A balance-bearing SMS then arrives → the `BALANCE_STALE` item is `AUTO_CLOSED`, status `OK`.
- AC-035-6 A credit-card account never gets `BALANCE_GAP` or `BALANCE_STALE`.
- AC-035-7 With email off, every scan records that the balance check ran (scan step result), whatever else failed.

**Tasks**
- [ ] Checker + tests

---

### SPEC-036 — Scheduler, scan runs, report, push

**Goal:** The daily job: (pull email, only if connected), process, check balances, report, notify. Works fully in SMS-only mode. HLD §4.9, D-27.

**Depends on:** 033, 035 · SPEC-020 optional (its mail step is skipped when it isn't built or Gmail isn't connected)

**Data** — `V11__scan_run.sql`: `scan_run` (HLD §4.7) + `trigger TEXT` (`CRON|MANUAL|STARTUP`) + `source_mode TEXT` (`SMS_ONLY|SMS_AND_EMAIL`, the mode at the start of the run) + `mail_status TEXT` (null in SMS-only) + `steps JSONB` (result per step).

**API**
- `GET /api/v1/scan-runs/latest`, `GET /api/v1/scan-runs?cursor=`
- `POST /api/v1/scan-runs` → starts a manual run, returns its id (202).

**Rules**
- R-036-1 Server cron = `scan.cron` shifted by `scan.serverDelayMinutes`, in `scan.zone`. Re-registered on `SettingsChanged(scan.*)` without restart.
- R-036-2 Steps: mail fetch (**only if Gmail is `CONNECTED`**; otherwise recorded as `SKIPPED_SMS_ONLY`) → `processPending()` → balance check (**always**, R-035-4) → build report → push. Each step's failure is recorded; later steps still run where possible. A failing mail step makes the run `PARTIAL` but never stops SMS processing or the balance check.
- R-036-3 Only one scan at a time (same advisory lock family).
- R-036-4 Report = **mode** (`SMS only` / `SMS + email`, plus Gmail status and last fetch time when connected), counts (new SMS, new emails, transactions created/merged, review opened, errors), balance-check result (accounts `OK` / `GAP` / `STALE`), spend for the day, open review count, and **investments**: instalments confirmed in the run (count + Σ), due in the next 7 days (count + Σ), and missed (newly missed in the run + open missed count).
- R-036-5 Push via FCM to every non-revoked device with an `fcm_token`: title "Hishaab daily scan", body e.g. "12 new · ₹3,420 spent · 1 SIP confirmed · 2 need review · SMS only" (add "· 1 SIP missed" when any; the last part is the mode: "SMS only" or "SMS + email", or "email needs reconnect"). No merchant or fund names in the push text.
- R-036-6 A scan with `errors > 0` or a failed step also sends a push "Scan had problems — open Sync status".

**Acceptance criteria**
- AC-036-1 Changing `scan.cron` reschedules (verify via scheduler registry in test).
- AC-036-2 With Gmail connected, a run whose mail fetch fails still processes SMS, runs the balance check and records `status=PARTIAL`.
- AC-036-3 Report numbers match DB counts for the run window.
- AC-036-4 Push sender is called once per device with the expected text (FCM mocked).
- AC-036-6 **Full scan with email off:** no Gmail connection, new SMS uploaded → the run processes them, runs the balance check, builds the report and pushes; the mail source is never called; `scan_run.status=SUCCESS`, `source_mode=SMS_ONLY`, `emails_in=0`, mail step `SKIPPED_SMS_ONLY`; the report and push say "SMS only"; `GET /summary` returns `sourceMode=SMS_ONLY`.
- AC-036-7 Gmail status `REAUTH_REQUIRED` → mail step skipped with a warning, run `PARTIAL`, push ends "email needs reconnect"; everything else as in AC-036-6.
- AC-036-5 With one instalment confirmed in the run, one due in 3 days and one newly missed, the report shows confirmed 1, upcoming 1, missed 1 with the right sums.

**Tasks**
- [ ] Migration
- [ ] Dynamic scheduler
- [ ] Scan runner + report
- [ ] FCM sender (interface + Firebase impl + fake)

---

### SPEC-040 — Transactions query

**Goal:** Fast, filterable, sortable transaction lists for the UI. HLD §6.4.

**Depends on:** 031

**API** — `GET /api/v1/transactions`

| Param | Type |
|---|---|
| `from`, `to` | ISO date (IST), inclusive |
| `direction` | `DR`/`CR` |
| `kind` | multi |
| `accountId` | multi |
| `categoryId` | multi, includes descendants |
| `merchantId` | multi |
| `minPaise`, `maxPaise` | long |
| `channel` | multi |
| `source` | `SMS`/`EMAIL`/`BOTH` |
| `status` | multi (default `CONFIRMED,PENDING`) |
| `categorySource` | multi |
| `q` | text (merchant, note, ref) |
| `sort` | `time_desc` (default), `time_asc`, `amount_desc`, `amount_asc`, `merchant_asc` |
| `limit` | 1–200, default 50 |
| `cursor` | opaque |

Response:
```json
{ "items": [ { "id": "...", "amountPaise": 45000, "direction": "DR", "kind": "SPEND",
               "status": "CONFIRMED", "txnTime": "2026-11-17T21:42:00+05:30",
               "account": {"id":"...","nickname":"Axis ••1234"},
               "merchant": {"id":"...","name":"Swiggy","type":"MERCHANT"},
               "category": {"id":"...","name":"Food"}, "categorySource": "RULE",
               "channel": "UPI", "refNo": "4321...", "sources": ["SMS","EMAIL"],
               "openReviewCount": 0 } ],
  "nextCursor": "..." }
```

`GET /api/v1/transactions/{id}` adds: `evidence[]` (each with source, sender, received time, **raw body**), `history[]` (audit), `linkedTxn`, `note`.

**Rules**
- R-040-1 All filtering happens in SQL; no in-memory filtering of full tables.
- R-040-2 Cursor is stable for each sort (tie-break on `id`).
- R-040-3 Indexes: `(txn_time desc, id)`, `(amount_paise, id)`, `(category_id)`, `(merchant_id)`, `(account_id)`, trigram index for `q` if `pg_trgm` is available (record decision).
- R-040-4 Filter object shape matches `packages/core` in the UI (same names).

**Acceptance criteria**
- AC-040-1 Seed 500 txns; paging through with `limit=50` returns all 500 exactly once in order, for each sort.
- AC-040-2 `categoryId=Food` includes `Food → Groceries` transactions.
- AC-040-3 Default `status` excludes `HIDDEN`.
- AC-040-4 Detail endpoint returns all evidence with raw bodies.

**Tasks**
- [ ] Query builder (JPA Criteria or jOOQ-free native SQL builder — record choice)
- [ ] Indexes migration
- [ ] Controllers + tests

---

### SPEC-041 — Summary

**Goal:** All totals for dashboards and chat, computed in one place. HLD D-18.

**Depends on:** 040

**API** — `GET /api/v1/summary?from&to&groupBy=category|merchant|account|day|week|month` + same filters as SPEC-040.

Response:
```json
{ "spentPaise": 342000, "receivedPaise": 5000000, "refundsPaise": 12000, "investedPaise": 300000,
  "pendingPaise": 124000, "pendingCount": 2,
  "groups": [ { "key": "...", "label": "Food", "spentPaise": 81200, "count": 23 } ],
  "lastSyncAt": "...", "lastScanAt": "...", "openReviewCount": 3,
  "sourceMode": "SMS_ONLY", "mail": { "status": "DISCONNECTED", "lastFetchAt": null } }
```

**Rules (the definition of "spend")**
- R-041-1 `spentPaise` = Σ `DR` where `kind=SPEND` and `status=CONFIRMED`, **minus** Σ `REFUND` (`CR`) linked to those.
- R-041-2 `receivedPaise` = Σ `CR` where `kind=INCOME` and `status=CONFIRMED`.
- R-041-3 `TRANSFER`, `CC_PAYMENT`, `REVERSAL`, `INVESTMENT`, `HIDDEN` never count in spend or received.
- R-041-6 `investedPaise` = Σ over instalments with status `CONFIRMED` or `MARKED_PAID` and `due_date` in the period (amount = the linked bank-debit transaction's amount if any, else `expected_paise`), plus `CONFIRMED` `INVESTMENT` transactions not linked to any instalment. Never part of `spentPaise`.
- R-041-4 `pendingPaise` = Σ amount of `PENDING` (shown separately, never in totals).
- R-041-5 Chat (SPEC-070) must call this service, not recompute.
- R-041-7 `sourceMode` and `mail` come from SPEC-020's `MailConnectionService`; until SPEC-020 is built they are always `SMS_ONLY` / `DISCONNECTED`. Totals are computed the same way in both modes (emails only ever add evidence).

**Acceptance criteria**
- AC-041-1 Fixture month with spends, a refund, a CC payment, a transfer, a reversal, a pending and two SIP instalments (one with a bank debit, one only fund-house confirmed) → totals equal hand-computed values in the test; SIPs appear only in `investedPaise`.
- AC-041-2 Group totals add up to `spentPaise`.

**Tasks**
- [ ] `SummaryService` + SQL
- [ ] Controller + fixture test

---

### SPEC-042 — Edit, split, merge

**Goal:** Let the user fix things directly.

**Depends on:** 034

**API**
- `PATCH /api/v1/transactions/{id}` `{categoryId?, categoryScope?: "THIS"|"ALWAYS", applyPast?, kind?, note?, hidden?}`
- `POST /api/v1/transactions/{id}/split` `{parts:[{amountPaise, categoryId, note?}]}`
- `POST /api/v1/transactions/merge` `{ids:[a,b]}`
- `POST /api/v1/transactions/{id}/unmerge` `{evidenceIds:[...]}`

**Rules**
- R-042-1 Every change writes a `user_override` and `audit_log`.
- R-042-2 Category with `ALWAYS` creates/updates a merchant (or person) rule (SPEC-043); `applyPast=true` re-categorises past transactions of that merchant **except** ones with `category_source=USER`.
- R-042-3 Split parts must sum exactly to the original amount; parts become child rows (`parent_txn_id`), parent becomes `HIDDEN`.
- R-042-4 Merge requires same direction and same amount; else 409 `MERGE_MISMATCH`.

**Acceptance criteria**
- AC-042-1 `ALWAYS` + `applyPast` → past non-user txns of that merchant updated; a user-set one untouched.
- AC-042-2 Split ₹1,000 into 600 + 400 → two children, totals unchanged.
- AC-042-3 Split parts summing to 999 → 400.
- AC-042-4 Merge ₹450 DR with ₹500 DR → 409.

**Data** — `V11__split.sql`: `parent_txn_id UUID` on `transaction`.

**Tasks**
- [ ] Migration
- [ ] Endpoints + tests

---

### SPEC-043 — Categories & rules CRUD

**Goal:** Editable nested categories and the rules that assign them. HLD §4.5.

**Depends on:** 031

**Data** — `V12__categories.sql`: `category`, `category_rule` (HLD §4.7) + seed default tree:
Food (Groceries, Eating out, Delivery), Transport (Fuel, Cab, Public), Shopping, Bills (Electricity, Mobile, Internet, Rent), Health, Entertainment, Travel, Education, Investments, Transfers to people, Other.

**API** — `GET/POST/PATCH/DELETE /api/v1/categories`, `GET/POST/PATCH/DELETE /api/v1/rules`, `POST /api/v1/rules/{id}/apply-past`.

**Rules**
- R-043-1 Deleting a category requires `moveTo` target; transactions and rules move there.
- R-043-2 Category tree max depth 2.
- R-043-3 Rule types: `MERCHANT` (merchant id), `PERSON` (merchant id of type PERSON), `KEYWORD` (case-insensitive substring of counterparty_raw). Higher `priority` wins; ties → `MERCHANT` > `PERSON` > `KEYWORD`.
- R-043-4 Categorisation precedence (shared with SPEC-061): user override → rule → AI → `NONE` + review.
- R-043-5 Merchant `always_ask=true` skips rules and AI and always opens `UNKNOWN_CATEGORY`.

**Acceptance criteria**
- AC-043-1 Delete "Shopping" with `moveTo=Other` → all its txns now Other.
- AC-043-2 Creating a 3rd-level category → 400.
- AC-043-3 Keyword rule "IRCTC" → Travel applies to new matching txns.

**Tasks**
- [ ] Migration + seed
- [ ] CRUD + rule engine (non-AI part) + tests

---

### SPEC-044 — OpenAPI contract

**Goal:** A generated, versioned API spec the apps build their client from. HLD §4.11.

**Depends on:** 040

**Rules**
- R-044-1 springdoc generates `/v3/api-docs`; a Maven step writes it to `backend/openapi/openapi.json` during `verify`.
- R-044-2 Every endpoint has an `operationId` (`listTransactions`, `resolveReviewItem`, …) and documented error responses.
- R-044-3 CI fails if `openapi.json` changes without being committed (contract drift check).
- R-044-4 Swagger UI only in `dev`.

**Acceptance criteria**
- AC-044-1 `./mvnw verify` produces `openapi.json` containing all `/api/v1` paths.
- AC-044-2 Removing a field from a response record changes the file (detected by the drift check).

**Tasks**
- [ ] Add springdoc
- [ ] Export step + drift check

---

### SPEC-045 — Investments & schedules

**Goal:** You record your investments (SIP, recurring deposit, FD/locked funds, PPF, other); the system generates the expected instalments from each schedule and lets you set any instalment's status by hand. HLD §4.13, D-24.

**Depends on:** 030 (accounts), 034 (overrides)

**Data** — `V12__investments.sql`
```sql
CREATE TABLE investment (
  id            UUID PRIMARY KEY,
  name          TEXT NOT NULL,
  type          TEXT NOT NULL CHECK (type IN ('SIP','RD','FD','PPF','OTHER')),
  amount_paise  BIGINT NOT NULL CHECK (amount_paise > 0),
  account_id    UUID REFERENCES account(id),          -- null = any/unknown account
  frequency     TEXT NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','YEARLY','EVERY_N_DAYS','EVERY_N_MONTHS','ONE_TIME')),
  interval_n    INT CHECK (interval_n BETWEEN 1 AND 3650),
  schedule_day  INT CHECK (schedule_day BETWEEN 1 AND 31),
  start_date    DATE NOT NULL,
  end_date      DATE,
  maturity_date DATE,                                  -- lock-in / maturity, informational
  match_text    TEXT[] NOT NULL DEFAULT '{}',          -- folio, scheme, mandate words (SPEC-046)
  grace_days    INT NOT NULL DEFAULT 3 CHECK (grace_days BETWEEN 0 AND 30),
  active        BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE TABLE instalment (
  id             UUID PRIMARY KEY,
  investment_id  UUID NOT NULL REFERENCES investment(id),
  due_date       DATE NOT NULL,
  expected_paise BIGINT NOT NULL CHECK (expected_paise > 0),
  status         TEXT NOT NULL DEFAULT 'SCHEDULED'
                 CHECK (status IN ('SCHEDULED','CONFIRMED','MARKED_PAID','MISSED','SKIPPED','FAILED')),
  status_source  TEXT NOT NULL DEFAULT 'AUTO' CHECK (status_source IN ('AUTO','USER')),
  note           TEXT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (investment_id, due_date)
);
ALTER TABLE user_override ADD COLUMN instalment_key TEXT;              -- '<investmentId>:<yyyy-MM-dd>'
ALTER TABLE user_override ALTER COLUMN anchor_raw_message_id DROP NOT NULL;
ALTER TABLE user_override ADD CONSTRAINT user_override_one_anchor
  CHECK (num_nonnulls(anchor_raw_message_id, instalment_key) = 1);
```
Settings (SPEC-001 keys): `investments.horizonDays` = `45`.

**API** (user auth)
- `POST /api/v1/investments` → 201 `{investment, nextDueDates[6]}`
- `POST /api/v1/investments/preview` (same body) → `{nextDueDates[6]}` — for the Add form, stores nothing
- `GET /api/v1/investments?active=` → list with `nextDueDate`, `lastStatus`
- `GET /api/v1/investments/{id}`, `PATCH /api/v1/investments/{id}` (partial)
- `DELETE /api/v1/investments/{id}` → deactivates (`active=false`); history kept
- `GET /api/v1/investments/{id}/instalments?from&to`
- `GET /api/v1/instalments?status=&from=&to=` (status multi) — used for "upcoming" and "missed" lists
- `PATCH /api/v1/instalments/{id}` `{status?, note?}` or `{revert:true}` (undo your manual status)

**Rules**
- R-045-1 Validation: amount > 0 paise. `MONTHLY`, `QUARTERLY`, `YEARLY`, `EVERY_N_MONTHS` use `schedule_day` (default = day of `start_date`). `EVERY_N_DAYS` and `EVERY_N_MONTHS` need `interval_n ≥ 1`. `ONE_TIME` ignores day and interval and must have no `end_date`. Violations → 400 `INVALID_SCHEDULE`.
- R-045-2 Due dates:
  - Month-based (`MONTHLY` = every 1 month, `QUARTERLY` = 3, `YEARLY` = 12, `EVERY_N_MONTHS` = n): periods start at `start_date`'s month; the date in each period is `min(schedule_day, last day of that month)` — so 29–31 fall on the last day of shorter months (Feb → 28/29).
  - `EVERY_N_DAYS`: `start_date + k·n` days.
  - `ONE_TIME`: exactly `start_date`.
  - The first instalment is the first generated date **≥ `start_date`**; `end_date` is inclusive.
- R-045-3 Instalments are stored for due dates up to `today + investments.horizonDays`, refreshed by the daily scan (SPEC-036) and right after any investment change. `expected_paise` = the investment amount when the instalment is generated.
- R-045-4 Editing schedule fields regenerates only instalments with `due_date ≥ today`, `status=SCHEDULED` and `status_source=AUTO`. Past instalments and anything you set keep their values.
- R-045-5 Deactivating stops generation and deletes future `SCHEDULED`/`AUTO` instalments; everything else is kept.
- R-045-6 Manual status: `PATCH /instalments/{id}` sets `status` (any of the six) and `status_source=USER`, and writes a `user_override` (`field=INSTALMENT_STATUS`, `instalment_key`, `value={status, note}`) plus `audit_log`. `{revert:true}` deletes that override and sets `status_source=AUTO`; the automatic status is then recomputed (R-045-7, SPEC-046).
- R-045-7 Missed by date: an instalment with `status=SCHEDULED`, `status_source=AUTO` and `today > due_date + grace_days` (IST) becomes `MISSED`. Evaluated by the daily scan and after generation.
- R-045-8 Rebuild (R-034-3) regenerates instalments from investments, then reapplies `INSTALMENT_STATUS` overrides by key. An override whose key no longer exists (schedule changed) → review `ORPHAN_OVERRIDE`.
- R-045-9 `maturity_date` is informational (shown in the UI); it does not create instalments.
- R-045-10 **Your schedule is the source of truth for instalments** (Q-7): instalments are generated from the schedule whether or not any SMS/email ever arrives, and you can mark any instalment `MARKED_PAID` (or any other status) at any time. This is how SIPs whose bank debit has no template yet are recorded; matching (SPEC-046) only adds automatic confirmation on top.

**Acceptance criteria**
- AC-045-1 Monthly, day 31, start 2027-01-15 → 2027-01-31, 2027-02-28, 2027-03-31, 2027-04-30; and 2028-02-29 in a leap year.
- AC-045-2 Quarterly, day 5, start 2026-11-05 → 2026-11-05, 2027-02-05, 2027-05-05.
- AC-045-3 Every 45 days from 2026-01-01 → 2026-01-01, 2026-02-15, 2026-04-01.
- AC-045-4 Every 2 months, day 10, start 2026-01-20 → first instalment 2026-03-10.
- AC-045-5 One-time → exactly one instalment, on `start_date`.
- AC-045-6 Monthly, day 5, end 2027-03-05 → last instalment 2027-03-05.
- AC-045-7 Amount changed 1,000 → 1,500 on 2026-11-18: instalments before today keep 1,000; future AUTO ones become 1,500; a future one you marked `SKIPPED` is untouched.
- AC-045-8 Mark an instalment `MARKED_PAID` → rebuild → still `MARKED_PAID` with `status_source=USER`; `revert` → back to the automatic status.
- AC-045-9 Due 2026-11-05, grace 3: on 2026-11-08 → `SCHEDULED`; on 2026-11-09 → `MISSED`.
- AC-045-10 `EVERY_N_DAYS` without `interval_n` → 400 `INVALID_SCHEDULE`.
- AC-045-11 A SIP with no messages at all: instalments are still generated each month; marking one `MARKED_PAID` counts it in `investedPaise` (R-041-6); without marking, it becomes `MISSED` after the grace days.

**Tests:** `ScheduleGeneratorTest` (pure, table-driven), `InvestmentIT`, `InstalmentOverrideIT`.

**Out of scope:** NAV, units, valuation, returns, tax; creating schedules from messages (SPEC-046 only *suggests*).

**Tasks**
- [ ] Migration
- [ ] `ScheduleGenerator` (pure) + tests
- [ ] Investment service + instalment materialiser
- [ ] Manual status + override + revert
- [ ] Controllers

---

### SPEC-046 — Instalment matching & lifecycle

**Goal:** Confirm or fail instalments automatically from bank debits and fund-house messages, keep investments out of spend, and never guess. HLD §4.13, D-24.

**Depends on:** 045, 031, 032, 033

**Data** — `V13__instalment_matching.sql`
```sql
CREATE TABLE instalment_evidence (
  id               UUID PRIMARY KEY,
  instalment_id    UUID NOT NULL REFERENCES instalment(id),
  kind             TEXT NOT NULL CHECK (kind IN ('BANK_DEBIT','AMC_CONFIRMATION','FAILED_DEBIT')),
  transaction_id   UUID REFERENCES transaction(id),
  parsed_record_id UUID REFERENCES parsed_record(id),
  raw_message_id   TEXT REFERENCES raw_message(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(transaction_id, raw_message_id) >= 1)
);
-- A transaction or message proves at most one instalment.
CREATE UNIQUE INDEX ux_inst_ev_txn ON instalment_evidence(transaction_id) WHERE transaction_id IS NOT NULL;
CREATE UNIQUE INDEX ux_inst_ev_raw ON instalment_evidence(raw_message_id) WHERE raw_message_id IS NOT NULL;
```
Settings (SPEC-001 keys): `investments.windowDaysBefore` = `2`, `investments.windowDaysAfter` = `5`, `investments.amcTolerancePpm` = `100` (0.01%), `investments.sipHints` = `["SIP","NACH","ACH","MUTUAL FUND","FOLIO","BSE STAR","CAMS","KFIN","ICCL"]`.

**Rules**
- R-046-1 Inputs: `TransactionCreated`/`TransactionUpdated` for `DR` transactions (→ `BANK_DEBIT`); `RecordParsed` with `record_type=INVESTMENT_CONFIRMATION` (→ `AMC_CONFIRMATION`); raw messages classified `FAILED_TXN` with a currency amount (→ `FAILED_DEBIT`).
- R-046-2 Candidates: instalments of **active** investments with `status_source=AUTO`, status `SCHEDULED`, `MISSED` or `FAILED`, no evidence of the same kind yet, and message date `d` (IST date of `txn_time`) within `[due_date − windowDaysBefore, due_date + windowDaysAfter]`.
- R-046-3 Amount: `BANK_DEBIT` / `FAILED_DEBIT` → exactly `expected_paise`. `AMC_CONFIRMATION` → `0 ≤ expected_paise − amount ≤ max(1, ceil(expected_paise × amcTolerancePpm / 1 000 000))` (stamp duty: ₹1,000 → ₹999.95).
- R-046-4 Account: when both the investment and the transaction have an account, they must be equal. Fund-house messages carry no account; skip the check.
- R-046-5 Text: when the investment has `match_text`, at least one entry must occur in the message text (case-insensitive, whitespace collapsed; folio compared on digits only). Empty `match_text` → no text check.
- R-046-6 Decision: **exactly 1** candidate → add `instalment_evidence`; `BANK_DEBIT`/`AMC_CONFIRMATION` → `CONFIRMED`, `FAILED_DEBIT` → `FAILED`; a `BANK_DEBIT` transaction gets `kind=INVESTMENT` (unless you overrode its kind). **2 or more** → review `INSTALMENT_AMBIGUOUS` (dedup key = source id), nothing changes, and a bank-debit transaction is set `PENDING` until answered.
- R-046-7 **0 candidates, bank debit that looks like an investment** (text contains any `investments.sipHints` entry, word-boundary, case-insensitive) → transaction `PENDING` + review `SIP_SCHEDULE_SUGGESTION` with a suggested schedule (amount, account, day of month, counterparty as name, type `SIP`). Answers: `CREATE_SCHEDULE` → creates the investment (SPEC-045) and re-runs matching; `LINK` → match to that investment's instalment in the window; `NOT_INVESTMENT` → `kind=SPEND`, `CONFIRMED`, stored as a `KIND` override.
- R-046-8 0 candidates for a fund-house allotment → review `SIP_SCHEDULE_SUGGESTION` (message-based). Never creates a transaction (R-031-8). 0 candidates for a failed debit → nothing.
- R-046-9 Late evidence: an instalment confirmed by an allotment can still get its `BANK_DEBIT` (the transaction becomes `INVESTMENT`; status stays `CONFIRMED`). A `MISSED` or `FAILED` instalment that later matches a debit/allotment → `CONFIRMED`.
- R-046-10 A `USER` status is never changed by these rules. Evidence may still be attached so you can see it.
- R-046-11 `instalment_evidence` and every `AUTO` status are derived: rebuild (R-034-3) clears and re-runs them; `INSTALMENT_STATUS` overrides are reapplied.

**Acceptance criteria**
- AC-046-1 SIP ₹1,000 day 5 on SBI; SBI debit ₹1,000 on 05-11 with fund-house text → instalment `CONFIRMED`, transaction `kind=INVESTMENT`; `spentPaise` unchanged, `investedPaise` +1,000.
- AC-046-2 Fund-house allotment Rs.999.95 dated 05-11 for that SIP (folio in `match_text`) → `CONFIRMED`; no transaction created.
- AC-046-3 Both arrive → one instalment, two evidence rows, one transaction.
- AC-046-4 Two SIPs of ₹1,000 due the same day, no `match_text` → a matching bank debit opens `INSTALMENT_AMBIGUOUS`; both stay `SCHEDULED`; the transaction is `PENDING`.
- AC-046-5 Same two SIPs with folios in `match_text` → each allotment confirms its own SIP.
- AC-046-6 "NACH … MUTUAL FUND" debit ₹2,500 with no schedule → `PENDING` + `SIP_SCHEDULE_SUGGESTION`; answering `CREATE_SCHEDULE` → investment created, instalment `CONFIRMED`, transaction `INVESTMENT` + `CONFIRMED`.
- AC-046-7 Failed-debit message for the SIP amount inside the window → `FAILED`; a successful debit two days later → `CONFIRMED`.
- AC-046-8 You set `SKIPPED`; a matching debit arrives → status stays `SKIPPED` (USER); evidence attached.
- AC-046-9 Allotment Rs.999.00 for a ₹1,000 SIP (outside tolerance) → not matched → `SIP_SCHEDULE_SUGGESTION`.
- AC-046-10 Rebuild → identical instalment statuses and evidence.
- AC-046-11 Summary for a month with ₹3,000 of SIPs and ₹5,000 of spend → `spentPaise` 5,000, `investedPaise` 3,000.

**Tests:** `InstalmentMatcherTest` (pure decision function), `InstalmentMatchingIT` (scenarios AC-046-1..11).

**Out of scope:** bank-side SIP debit templates until a sample exists (Q-7); NAV/units tracking.

**Tasks**
- [ ] Migration + settings keys
- [ ] Matcher (pure) + persistence wrapper + listeners
- [ ] Review handlers for `INSTALMENT_AMBIGUOUS` and `SIP_SCHEDULE_SUGGESTION`
- [ ] Summary (`investedPaise`) and report lines (SPEC-041, SPEC-036)
- [ ] Scenario tests

---

### SPEC-050 — Auth

**Goal:** Real login. HLD §7.1, D-14.

**Depends on:** 010

**Methods**

| Method | Profiles | Result |
|---|---|---|
| Google OAuth2 login (web) | all | Session cookie (`HttpOnly`, `Secure`, `SameSite=Lax`) |
| Mobile: `POST /api/v1/auth/mobile/google` `{idToken}` | all | `{accessToken (JWT, 15 min), refreshToken (30 days)}` |
| `POST /api/v1/auth/refresh` | all | New pair; old refresh token revoked (rotation) |
| API token `Authorization: Bearer hat_...` | all | Scoped access |
| Device token `hdt_...` | all | `DEVICE` role (SPEC-010) |
| Form login (email + password) | **dev only** | Session cookie |

**Data** — `V13__auth.sql`: `app_user(id, email, created_at)`, `refresh_token(id, user_id, token_hash, expires_at, revoked_at)`, `api_token(id, name, token_hash, scopes TEXT[], created_at, revoked_at, last_used_at)`.

**Rules**
- R-050-1 Only `hishaab.auth.allowedEmail` (env `HISHAAB_ALLOWED_EMAIL`) may log in; any other Google account → 403 and nothing stored.
- R-050-2 Google ID tokens verified for signature, `aud` = our client IDs, `email_verified=true`.
- R-050-2a Login asks only for identity scopes (`openid email profile`), **never Gmail access**. Gmail is a separate, optional consent in SPEC-020.
- R-050-3 JWT signed with a key from env (`HISHAAB_JWT_KEY`); never a hard-coded key.
- R-050-4 Refresh token reuse after rotation → revoke the whole family, 401.
- R-050-5 The form-login endpoint and its security config bean exist **only** under `@Profile("dev")`. In `prod`, `/login` with a password returns 404.
- R-050-6 CSRF protection on for cookie sessions; off for bearer-token requests.
- R-050-7 API token scopes: `read`, `write`, `admin`. `admin` needed for `/admin/**`.

**Acceptance criteria**
- AC-050-1 Allowed email → logged in; another email → 403, no `app_user` row.
- AC-050-2 In `prod` profile, password login path → 404 (test with profile override).
- AC-050-3 Reusing a rotated refresh token → 401 and the newer token is also revoked.
- AC-050-4 `read`-scoped API token on `PATCH /transactions/{id}` → 403.

**Tasks**
- [ ] Migration
- [ ] Google login (web) + allowlist
- [ ] Mobile token exchange + JWT + refresh rotation
- [ ] API tokens
- [ ] Dev-only form login
- [ ] Replace SPEC-000 temporary basic auth

---

### SPEC-060 — LLM gateway

**Goal:** One safe door for every Gemini call. HLD §4.6, D-12.

**Depends on:** 001

**Scope**
```java
public interface LlmGateway {
  <T> LlmResult<T> structured(LlmPurpose purpose, String system, String user, Class<T> schema);
  ChatStream chat(ChatRequest request);           // used by SPEC-070
}
enum LlmPurpose { CATEGORISE, PARSE_FALLBACK, CHAT }
record LlmResult<T>(T value, double confidence, String model, long latencyMs, long inTokens, long outTokens) {}
```

**Data** — `V14__llm_log.sql`: `llm_call_log(id, at, purpose, model, request_masked TEXT, response TEXT, in_tokens, out_tokens, latency_ms, error)`.

**Rules**
- R-060-1 **Masking before send:** digit runs of 6+ → `#`s (keeps amounts like `450.00`); emails → `<email>`; phone numbers → `<phone>`; UPI IDs → `<upi>` except the handle part used for merchant detection when purpose = `CATEGORISE` (send merchant name only).
- R-060-2 Only a **billed** Google Cloud project key is allowed: config flag `hishaab.llm.billedProject=true` must be set or the gateway refuses to start in `prod`.
- R-060-3 Timeout 20 s, 1 retry on 5xx/timeout, then error result (callers fall back to review).
- R-060-4 Structured calls must parse into `schema`; parse failure = error result, never a partial object.
- R-060-5 Every call logged (masked request).
- R-060-6 Kill switch: `hishaab.llm.enabled=false` → all calls return a "disabled" error immediately.

**Acceptance criteria**
- AC-060-1 Masking unit tests: `"Ref 432112345678 to rahul@okaxis call 9876543210, Rs 450.00"` → digits, UPI ID and phone masked, `450.00` kept.
- AC-060-2 `prod` profile with `billedProject=false` → startup fails with a clear message.
- AC-060-3 Model returns invalid JSON → error result, logged.
- AC-060-4 Kill switch → no network call made.

**Tasks**
- [ ] Spring AI Gemini setup
- [ ] Masker + tests
- [ ] Gateway + log + kill switch

---

### SPEC-061 — Categoriser (rules + AI)

**Goal:** Category for every new transaction, consistently. HLD §4.5, D-11.

**Depends on:** 043, 060

**Rules**
- R-061-1 Precedence (R-043-4). AI only when no rule matches and merchant is not `always_ask`.
- R-061-2 AI input: merchant display name, merchant type, amount (rupees), channel, IST hour of day, category tree (names + ids), up to 20 of the user's most recent `USER`-source examples for similar merchants.
- R-061-3 AI output schema `{categoryId, confidence}`; unknown `categoryId` = failure.
- R-061-4 `confidence ≥ ai.categoryConfidenceMin` → apply with `category_source=AI`, `ai_model_version` set. Else → `UNKNOWN_CATEGORY` review with the suggestion in the payload.
- R-061-5 **Never** for `PERSON` merchants: always review (AI cannot know why you paid a person).
- R-061-6 AI results never create rules. Only user confirmations do (via SPEC-033/042).
- R-061-7 Cache: same merchant asked again within 24 h with no new rules → reuse last AI answer (no second call).

**Acceptance criteria**
- AC-061-1 Merchant with rule → no LLM call.
- AC-061-2 Unknown merchant, fake LLM returns 0.92 → applied as AI.
- AC-061-3 Fake LLM returns 0.5 → review with suggestion.
- AC-061-4 Person payment → review, no LLM call.
- AC-061-5 User-set category never changed by a later categoriser run.

**Tasks**
- [ ] Categoriser service (listens to `TransactionCreated`)
- [ ] Prompt template in `resources/prompts/categorise.st`
- [ ] Tests with fake gateway

---

### SPEC-062 — LLM fallback parser

**Goal:** Read bank formats we have no template for, without trusting the result blindly.

**Depends on:** 060, 033

**Rules**
- R-062-1 Used only when R-015-6 applies.
- R-062-2 Output schema = normalised fields (SPEC-014) + `confidence`. Result stored as `parsed_record` with `parser=LLM`.
- R-062-3 Always creates a `LOW_CONFIDENCE` review item. The record enters matching only after the user confirms or corrects it.
- R-062-4 The amount returned must literally appear in the original body (after formatting); else reject the result.
- R-062-5 Confirmed LLM parses are listed in a weekly "template candidates" log so Ayan can write a real template.

**Acceptance criteria**
- AC-062-1 Unknown-format message → `parsed_record(parser=LLM)` + `LOW_CONFIDENCE` review; no transaction yet.
- AC-062-2 Confirm → transaction created through the normal matcher.
- AC-062-3 Fake LLM returns an amount not in the text → rejected, `UNPARSED` review instead.

**Tasks**
- [ ] Fallback parser + prompt
- [ ] Review handler wiring
- [ ] Tests

---

### SPEC-070 — Chat agent & tools

**Goal:** Answer money questions with exact numbers. HLD §4.10, D-13.

**Depends on:** 041, 060

**Data** — `V15__chat.sql`: `chat_session`, `chat_message` (HLD §4.7). Plus DB role `hishaab_ro` with `SELECT` only on the tables tools need.

**API**
- `POST /api/v1/chat` `{sessionId?, message}` → `text/event-stream` with events `token`, `tool`, `done` (`done` carries `basedOn` filters).
- `GET /api/v1/chat/sessions`, `GET /api/v1/chat/sessions/{id}`.

**Tools** (each is a thin wrapper over `query`/`review` services)

| Tool | Args | Returns |
|---|---|---|
| `search_transactions` | filter object (SPEC-040), `limit ≤ 50` | rows (merchant, amount, date, category) |
| `summarise` | `from, to, groupBy, filters` | SPEC-041 response |
| `compare_periods` | `a{from,to}, b{from,to}, filters` | two summaries + diff |
| `top_merchants` | `from, to, n ≤ 20` | list |
| `list_review_items` | — | open items (type + short text) |
| `get_categories` | — | tree |

**Rules**
- R-070-1 Tools run through a `DataSource` connected as `hishaab_ro`. No write is possible.
- R-070-2 Max 6 tool calls per user message; then answer with what is known.
- R-070-3 System prompt: answer only from tool results; if data is missing, say so; never invent numbers; amounts in ₹ with Indian grouping.
- R-070-4 The `done` event includes every filter object used, so the UI can open the same view.
- R-070-5 Raw SMS/email bodies are **never** sent to the model.
- R-070-6 History sent to the model: last 10 messages of the session.

**Acceptance criteria**
- AC-070-1 Fake LLM that calls `summarise(Food, Oct)` → stream contains the exact paise value from SPEC-041 and a `basedOn` with that filter.
- AC-070-2 A tool attempting an `UPDATE` via the RO connection fails (DB-level test).
- AC-070-3 7th tool call in one turn is refused.
- AC-070-4 Request payload to the model contains no `raw_message.body` text (assert on gateway log).

**Tasks**
- [ ] Migration + RO role
- [ ] Tool layer + Spring AI tool registration
- [ ] SSE controller
- [ ] Tests with fake gateway

---

### SPEC-090 — Container, deploy, backup, CI

**Goal:** Run safely on the Oracle VM. HLD §8.

**Depends on:** 050

**Scope**
- `backend/Dockerfile` (multi-stage; runtime = JRE 25; non-root user).
- `infra/docker-compose.yml`: `nginx`, `backend`, `postgres` (not published to host), `backup`.
- `infra/nginx/hishaab.conf`: TLS, `/api/` → backend, `/` → web static; SSE-friendly settings for `/api/v1/chat` (no buffering).
- `infra/backup/backup.sh`: nightly `pg_dump` → encrypt → Oracle Object Storage; keep 30 days.
- GitHub Actions: build + `./mvnw verify` on every push; build/push image and deploy on `main`.

**Rules**
- R-090-1 All secrets via env file on the VM, never in the repo. Includes `HISHAAB_TOKEN_ENC_KEY` (encrypts the optional Gmail token, SPEC-020); the app starts without it and only refuses to *connect* Gmail when it is missing.
- R-090-2 Postgres port never exposed publicly.
- R-090-3 `prod` profile active in the container.
- R-090-4 Restore procedure documented in `infra/backup/RESTORE.md` and tested once before go-live.

**Acceptance criteria**
- AC-090-1 `docker compose up` on a clean VM → `/actuator/health` UP through Nginx over HTTPS.
- AC-090-2 Backup file appears in object storage; restore into a fresh container yields the same row counts.
- AC-090-3 CI fails on a failing test or contract drift.

**Tasks**
- [ ] Dockerfile
- [ ] Compose + Nginx
- [ ] Backup + restore doc
- [ ] CI workflows

---

## 6. Decision log (append-only)

| Date | Spec | Decision | Why | Decided by |
|---|---|---|---|---|
| 2026-11-17 | all | OpenJDK 25 LTS, Spring Boot 4.x, Maven | Current LTS; HLD D-07 | Ayan |
| 2026-11-17 | 000 | Spring Modulith for module borders | Enforces H-4 automatically | Plan |
| 2026-11-17 | 000 | No Lombok; records for DTOs | Fewer JDK-upgrade issues | Plan |
| 2026-11-17 | 031 | Ref uniqueness on `(ref_no, direction)` | Reversals reuse the ref with opposite direction | Plan |
| 2026-11-17 | 000 | Spring Boot 4.1.2, Spring Modulith 2.1.2, Testcontainers 2.0.5 (Boot-managed) | Latest stable at build time; Modulith 2.1 is the line for Boot 4.1 | Claude |
| 2026-11-17 | 000 | Boot 4 modular starters: `spring-boot-starter-webmvc` stands in for `-web`, `spring-boot-starter-flyway` wraps `flyway-core` | Boot 4 renamed `-web` and moved Flyway auto-config into its own starter; same libraries as §2.2, no new ones | Claude |
| 2026-11-17 | 000 | H-2 enforced in DB: `V1__init.sql` trigger blocks DELETE and changes to content columns of `raw_message` | Defence in depth: a bug or manual SQL cannot destroy the source of truth | Claude |
| 2026-11-17 | 000 | Temporary basic auth (`hishaab.security.basic-auth`) on in `dev`/`test`, off in `prod`; creds from `HISHAAB_DEV_USER`/`HISHAAB_DEV_PASSWORD` | Prod exposes only `/actuator/health` until SPEC-050 | Claude |
| 2026-11-17 | 000 | 401/403/404/validation errors are ProblemDetails with `code` (`UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_FAILED`, else HTTP status name) | §4.4 says *all* errors use problem+json | Claude |
| 2026-11-17 | 000 | `Ids.newId()` = hand-rolled UUIDv7 (JDK 25 has no v7 factory) | §2.4 prefers v7; avoids a dependency | Claude |
| 2026-11-17 | 000 | AC-000-2 negative case uses a test-only fixture (`fixture.modulith.violating`) verified with an ImportOption that includes test classes | Proves the check fails on an `internal` call without planting a violation in main code | Claude |
| 2026-11-17 | 000 | Integration tests call the running server with JDK `HttpClient` (no MockMvc/test starters) | Exercises the real filter chain; no extra dependencies | Claude |
| 2026-11-17 | 000 | One Postgres Testcontainer shared by all Spring test contexts in the JVM; tests use unique IDs and restore settings they change | `raw_message` rows can't be deleted (H-2), and per-context containers made the suite slow | Claude |
| 2026-11-17 | 001 | Settings stored/read via `JdbcTemplate` (`?::jsonb`), not JPA; read from DB on every call (no cache) | Avoids Hibernate JSON-mapper/Jackson 3 coupling; reads are rare, and no cache means no stale values | Claude |
| 2026-11-17 | 001 | PATCH validates every key before writing; any error → nothing changes. Invalid non-cron values → 400 `INVALID_SETTING_VALUE` | Spec names only `UNKNOWN_SETTING`/`INVALID_CRON`; one generic code for the rest | Claude |
| 2026-11-17 | 001 | `SettingsChanged(key)` published only for keys whose value actually changed (jsonb `IS DISTINCT FROM`) | No spurious reschedules on no-op PATCHes | Claude |
| 2026-11-17 | 001 | Ranges: `scan.serverDelayMinutes` 0–720, `match.smsEmailWindowMinutes` 1–1440, `backfill.days` 1–365, `ai.categoryConfidenceMin` 0–1; `scan.cron` must have exactly 6 fields (no `@daily` macros); allowlist duplicates collapse | Spec gives no bounds; these reject obvious mistakes. Easy to widen | Claude |
| 2026-11-17 | 010 | Role rules: `DEVICE` only on `/api/v1/ingest/**`, `/api/v1/device/**`, `PATCH /api/v1/devices/me`; every other path needs `USER` (so a user calling device endpoints also gets 403) | R-010-3, applied symmetrically so each endpoint has exactly one kind of caller | Claude |
| 2026-11-17 | 010 | An `Authorization: Bearer hdt_…` header that is unknown/revoked → 401 immediately, never falls through to other schemes | R-010-4; no ambiguity about which identity a request has | Claude |
| 2026-11-17 | 010 | Token hash = SHA-256 hex of the full token string incl. `hdt_`; revoke is idempotent (204), unknown id → 404 `DEVICE_NOT_FOUND`; `/device/config` returns `cursorMs: null` before the first upload | Details the spec left open | Claude |
| 2026-11-17 | 011 | Item checks in order: shape → hash → allowlist → insert. Null fields, non-hex id, `receivedAtMs ≤ 0`, sender > 64 or body > 10 000 chars, or NUL characters → item rejected `INVALID_MESSAGE` (not a batch 400) | R-011-7: one bad item never fails the batch; Postgres TEXT cannot store NUL | Claude |
| 2026-11-17 | 011 | Empty batch → 400 `EMPTY_BATCH`; 501+ → 400 `BATCH_TOO_LARGE` | R-011-1 names only the second code | Claude |
| 2026-11-17 | 011 | Response `cursorMs` = the device's stored cursor after the update (= batch max when it is newer; unchanged for older batches; null if nothing storable was ever uploaded). `last_sync_at` updates on every successful call | Worker never sees the cursor go backwards (AC-011-5) | Claude |
| 2026-11-17 | 011 | Sender middle part = `^(?:XX-)?([A-Z]{3,8})(?:-X)?$` on the uppercased sender; phone numbers never match | HLD §5.7; same rule as allowlist entries (R-001-3) | Claude |
| 2026-11-17 | 011 | The same ID twice in one batch → first `accepted`, second `duplicate` | Follows from `ON CONFLICT DO NOTHING` | Claude |
| 2026-11-17 | 011 | `SenderIds.middlePart` moved to `shared` | Needed by ingest (allowlist), classify (bank rules) and later parse (`senderPattern`) | Claude |
| 2026-11-17 | 012 | **Safety bias:** every ignore class (OTP, PROMO, FAILED_TXN, MANDATE_REMINDER, BALANCE_ONLY, STATEMENT) refuses messages that also report a completed money movement (past-tense debited/credited/spent/… not preceded by "will be"/"to be"). OTP additionally needs an actual 4–8 digit code next to the OTP wording | Wrongly ignoring silently drops spend from totals (H-9); wrongly classing as TRANSACTION is caught downstream (no template → UNPARSED review). E.g. the "Never share your OTP" footer on transaction SMS must not hide the transaction | Claude — **Ayan to confirm** |
| 2026-11-17 | 012 | R-012-4 implemented as a pure mapping (`Classification.rawStatus()/ignoreReason()`); the DB status update and `MessageClassified` publish happen in the SPEC-015 orchestrator. `FAILED_TXN` also sets `ignore_reason = FAILED_TXN` | R-012-5 keeps the classifier pure; SPEC-015 owns per-message transactions and locking | Claude |
| 2026-11-17 | 012 | Rule file format: `senders` (middle parts; empty = all), `fragments` (`{{name}}` macros), `classes.<CLASS>[] = {name, all[], none[]}`; text lowercased + whitespace collapsed; bank files are checked before common rules within a class; reason = `<CLASS>:<rule name>`. Only `common.yml` exists until real samples arrive | Spec asked for per-bank YAML; bank-specific patterns without samples would be guesses | Claude |
| 2026-11-17 | 012 | Rules loaded with Spring `YamlMapFactoryBean` (SnakeYAML is already on the classpath via Boot) | Boot `Binder` can rewrite uppercase/underscore map keys like `MANDATE_REMINDER`; no new dependency | Claude |
| 2026-11-18 | all | **SBI Card dropped** from v1; banks = Axis (savings + credit card), SBI (savings); fund houses (AMCs) added as an SMS/email source | You don't hold an SBI Card (HLD D-23) | Ayan |
| 2026-11-18 | 013 | **Golden set is synthetic** (HLD D-25): real samples used only as shape references; every value invented; fund names fictional ("Alpha MF"…) and AMC templates named by shape, so holdings aren't revealed. A test guards against Gmail addresses and non-`example.invalid` links in fixtures | Samples carried names, account numbers, folios, live links | Ayan |
| 2026-11-18 | 013 | 30% hold-out **dropped**; replaced by the coverage rule: per template ≥ 1 shape fixture + ≥ 2 synthetic variants (enforced by `GoldenSetTest`), and every non-template shape is a fixture expecting no template | Sample counts per shape were 1–4; variants test the same robustness | Ayan |
| 2026-11-18 | 012 | New class `INVESTMENT_CONFIRMATION` (fund-house allotment), checked right after OTP; `processed`, `withdrawal`, "transaction has been done" count as completed actions; amounts also as `(INR) 1000.00` and SBI's currency-less `debited by 28.0`; `not_future` also rejects "being"/"not (be)"; PROMO yields to statements; SIP reminders ("due for debit", "upcoming SIP") and mandate/SIP registrations → `MANDATE_REMINDER` | Learned from the real Axis/SBI/AMC shapes; keeps allotments out of transactions (D-24) | Claude |
| 2026-11-18 | 013 | Templates: Axis SMS (UPI debit/credit, card debit on savings, credit-card spend, card payment received, account credit with ref text), Axis email (UPI table, account credit, card AutoPay), SBI SMS (UPI debit, credit), SBI email (debit-card cash withdrawal only), 6 fund-house allotment SMS shapes. `senderPattern` is a full-match regex on the SMS middle part (`AXIS[A-Z]{0,4}`, `SBI[A-Z]{0,5}\|[A-Z]{0,4}SBI`, AMC `.*`) or the email address; exact IDs still pending (Q-4, Q-8) | One template per shape; first `find()` that normalises wins | Claude |
| 2026-11-18 | 013 | Deliberately **no template** (→ ignored or UNPARSED review): FD booked/closed, statements, mandate created, SIP reminders/registrations, SBI debit-card types other than cash withdrawal, fund-house allotment **emails** (they state no amount; the same allotment arrives by SMS with an amount) | "Don't force a match" | Claude |
| 2026-11-18 | 013 | `parse_template` keeps every `(id, version)`; startup sets only YAML-listed versions active. Hash covers source, sender pattern, regex, fixed, account/record type and bank. `parsed_record` gets `record_type` and CHECKs for enums, `^[0-9]{4}$` last4 and `^[0-9]{6,22}$` ref | AC-013-3 and DB-level guard rails | Claude |
| 2026-11-18 | 014 | Dates also accept `dd-MMM-yyyy`, `MMM d, yyyy` (SBI email) and single-digit days (`5Nov26`); combined `datetime` captures split on the trailing time. Channel inference uses word boundaries except `UPI`/`CARD` (substring, to catch `BLOCKUPI`/`BLOCKCARD`); a fixed/captured channel always wins. Direction words also accept `debit`, `credit`, `DR`, `CR`, `refunded` | Seen in samples; `\bATM\b` avoids "TREATMENT" | Claude |
| 2026-11-18 | 045/046 | Investments split in two specs: 045 (schedules, instalments, manual status; needs 030, 034) and 046 (matching, INVESTMENT kind, reviews; needs 045, 031–033). Only docs now: their dependencies are not built yet | Ship the schedule part without waiting for matching | Claude |
| 2026-11-18 | 046 | Fund-house allotment amount is net of 0.005% stamp duty → match tolerance `investments.amcTolerancePpm` = 100 (0.01%) below the expected amount, never above; bank debits must match exactly. Unmatched SIP-looking debits stay `PENDING` + review (never silently spend or invested) | Real shapes show ₹999.95 for ₹1,000; H-9 | Claude — **Ayan to confirm** |
| 2026-11-18 | 046 | Stamp-duty tolerance (≤ 0.01 % below, never above) and "unmatched SIP-looking debit → `PENDING` + review" **confirmed** | | Ayan |
| 2026-11-18 | 045 | Schedules generate instalments regardless of messages; manual `MARKED_PAID` always available (R-045-10) | Q-7: a SIP must be trackable even with no parsable bank debit | Ayan |
| 2026-11-18 | 001/011/020 | Sender allowlists open by default: `senders.allowlist = ["*"]` (V5 migration; only replaces the untouched `[]`), `mail.fromAllowlist = ["*"]`. `"*"` is an allowed allowlist entry. **Consequence:** the worker gets `["*"]` from `/device/config`, so it must apply its own bank/fund-house filter or it would upload every SMS (OTPs, personal) | Q-8: fund-house sender IDs unknown; filtering moves to the sync job | Ayan |
| 2026-11-18 | 030 | Card aliases + account merge (R-030-5..7) | Q-9: debit-card messages carry the card last 4, not the account's | Ayan |
| 2026-11-18 | all | Migration numbers in unbuilt specs are placeholders; the next free version is used at build time | Specs are built out of numeric order, so fixed numbers collide | Claude |
| 2026-11-18 | 015 | `review_item` table + minimal `ReviewService.open/deleteOpenFor…` built now (`V6`), because R-015-6 must open UNPARSED items before SPEC-033 exists; SPEC-033 adds the API, resolution handlers and the `transaction` FK | Dependency gap in the plan; no behaviour invented beyond the spec's own payload shapes | Claude |
| 2026-11-18 | 015 | Listeners use Spring `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` instead of Modulith `@ApplicationModuleListener` | That annotation needs `spring-modulith-events-api`, which is not on the approved list (§2.2). Same semantics here. Add the dependency later if the event publication registry is wanted | Claude — **Ayan: add dependency?** |
| 2026-11-18 | 015 | Advisory lock = session-level `pg_try_advisory_lock` on a dedicated pooled connection held for the run (`AdvisoryLocks.PROCESSING`); per-message work uses its own transactions. `reprocess`/`reprocessAll` wait for the lock (60 s / 10 min) instead of skipping | R-015-2 + R-015-4 together need a lock that outlives single transactions; rebuilds must not silently do nothing | Claude |
| 2026-11-18 | 015 | A trigger that finds the lock taken returns immediately; the running processor re-checks for `NEW` after releasing the lock (up to 5 rounds) so no message is stranded. Messages are picked by `status = NEW` only; `ERROR` needs an explicit reprocess | Completes R-015-2 without a queue; no retry storms | Claude |
| 2026-11-18 | 015 | Classifier and template disagree on record type (`TRANSACTION` vs `INVESTMENT_CONFIRMATION`) → UNPARSED review (reason `TEMPLATE_CLASS_MISMATCH`), never a guess. "UNKNOWN with an amount" uses the classifier's own `amount` fragment (`ClassifyService.mentionsAmount`) | H-9; one definition of "looks like money" | Claude |
| 2026-11-18 | 015 | `hishaab.processing.auto-trigger` (default `true`, `false` in the test profile) switches off the upload/startup triggers so tests drive processing deterministically; `ProcessingAutoTriggerIT` proves the wiring | The test DB is shared across Spring contexts | Claude |
| 2026-11-18 | all | **Email is optional; SMS-only is the default** (HLD D-27). The app is fully usable without Gmail; mail is fetched only while a valid connection exists; connect/disconnect any time, with status + last fetch visible | Feature change requested by Ayan | Ayan |
| 2026-11-18 | 031 | **An email never creates a transaction**: it attaches by ref/window or opens `EMAIL_ONLY` (add / link / ignore); open `EMAIL_ONLY` items auto-attach when the SMS arrives later (R-031-9/10). AC-031-3 and AC-031-5 changed accordingly | The only way to guarantee that enabling email never duplicates SMS transactions, and keeps SMS the single source of truth | Claude — **Ayan to confirm** |
| 2026-11-18 | 020 | Gmail connection state lives in `mail_connection` (one row; token AES-GCM-encrypted with `HISHAAB_TOKEN_ENC_KEY`), not in `settings`; there is no `mail.enabled` flag. Disconnect = stop + revoke at provider + delete token; stored emails and their evidence stay. Backfill = `backfill.days` on every (re)connect | "On" must mean "has a valid token", and disconnecting must leave nothing usable behind | Claude |
| 2026-11-18 | 035 | Balance check is mandatory on every scan in both modes; savings accounts only; new `BALANCE_STALE` review after `balance.staleDays` (14) without a balance-bearing SMS | No second source in SMS-only mode; card "available limit" is not a balance | Claude |
| 2026-11-18 | 036 | SPEC-036 no longer depends on SPEC-020; `scan_run` records `source_mode`, `mail_status` and per-step results; report and push name the mode; `/summary` returns `sourceMode` + `mail` | Mode must be visible on the dashboard and in every report | Claude |
| 2026-11-18 | 050 | Login never requests Gmail scopes; Gmail is a separate optional consent | Keeps email truly opt-in | Claude |
| 2026-11-18 | 020 | SPEC-020 (optional Gmail) **deferred**; work continues with SPEC-030 | Ayan's call; nothing depends on it | Ayan |
| 2026-11-18 | 030 | Accounts and merchants live in the `match` module (`AccountService`, `MerchantService`); `categorise` will use them | No accounts module in §2.3; resolution happens at match time | Claude |
| 2026-11-18 | 030 | "A last 4 belongs to one account" (own number **or** one alias, never both) enforced by triggers on `account` and `account_alias`, plus a service pre-check that returns 400 `ALIAS_CONFLICT` | Cross-table uniqueness can't be a plain constraint | Claude |
| 2026-11-18 | 030 | Merges publish `AccountMerged` / `MerchantMerged` before deleting the old row; later specs repoint in the same transaction. No `user_override` needed: accounts, aliases and merchants are not derived data | Transactions don't exist yet; keeps modules decoupled | Claude |
| 2026-11-18 | 030 | Merchant normalisation: uppercase; non-alphanumerics (except `&`) → space; digit runs ≥ 4 dropped; leading titles (MR, MRS, DR, SHRI…) and trailing company/city suffixes (LTD, PVT, INDIA, BLR, MUMBAI…) dropped while > 1 word remains. UPI IDs keep their lowercased address as key. PERSON guess = 2–3 plain words with no business word, or a phone/name-like UPI ID; anything else MERCHANT. Merchant API added so a wrong guess can be fixed | R-030-2/3 made concrete; heuristics are only first guesses | Claude |
| 2026-11-18 | 030 | Auto-created nicknames: `Axis ••1234` / `SBI ••1234`, cards `Axis card ••1234`; merging requires same bank and type | Readable defaults; merging a card into savings would corrupt balance checks | Claude |
| | | | | |

---

## 7. Open questions (must be answered before the blocked specs)

| # | Question | Blocks | Owner |
|---|---|---|---|
| Q-1 | ~~Provide sample SMS + emails~~ — received 2026-11-18 (shape reference only; no SBI Card) | SPEC-013 | Ayan |
| Q-2 | Gmail API or IMAP (HLD O-02) — **only needed for the optional Gmail feature; blocks nothing else** | SPEC-020 real client | Ayan |
| Q-3 | Bank email sender addresses for `mail.fromAllowlist` — optional; default `["*"]` | SPEC-020 (optional) | Ayan |
| Q-4 | Sender ID middle parts for `senders.allowlist` (from your inbox) | SPEC-011 go-live | Ayan |
| Q-5 | super.money credit card in scope? | SPEC-030 | Ayan |
| Q-6 | Subdomain | SPEC-090 | Ayan |
| Q-7 | ~~Bank-side SIP debit sample~~ — **answered 2026-11-18:** not needed to proceed. Schedules hold regardless of messages and instalments can always be marked paid by hand (R-045-10). A bank-debit template is added when a sample appears | SPEC-046 | Ayan |
| Q-8 | ~~Fund-house sender IDs / email domains~~ — **answered 2026-11-18:** keep allowlists open (`["*"]`); the sync job filters on its side | SPEC-011, SPEC-020 | Ayan |
| Q-9 | ~~Card last-4 vs account last-4~~ — **answered 2026-11-18: yes**, card aliases + account merge in SPEC-030 (R-030-5..7) | SPEC-030 | Ayan |
