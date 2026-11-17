# hishaab-backend — Spec-Driven Build Plan

| | |
|---|---|
| **Component** | `hishaab-backend` (Spring Boot service) |
| **Repo path** | `hishaab/backend/` |
| **Design reference** | `docs/Hishaab-ai-HLD.md` (the "HLD") — the *why*. This file is the *what to build and how to check it*. |
| **Version** | Spec v1.0 — 17 Nov 2026 |
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
| H-10 | Totals exclude `PENDING` and `HIDDEN` transactions and the kinds `TRANSFER`, `CC_PAYMENT`, `REVERSAL` from spend/income (see SPEC-041). |

### 0.3 Spec format

Each spec has: **Goal · Depends on · Scope · Data · API · Rules (R-) · Acceptance criteria (AC-) · Tests · Out of scope · Tasks**. Rules say what must always be true. Acceptance criteria are concrete Given/When/Then checks; every AC needs at least one test.

---

## 1. Context in one paragraph

Hishaab.ai turns bank SMS (uploaded raw by an Android worker) and bank emails (pulled by the server from Gmail) into one clean list of transactions. Each real transaction is stored once (dedup), linked to every message that proves it (evidence), categorised (your rules first, AI only for unknown merchants), and anything unclear goes to a review queue. A daily job pulls email, re-runs matching, checks balances and pushes a report. A chat agent answers questions using read-only tools. Banks in v1: **Axis, SBI (savings), SBI Card (credit card)**. One user. See HLD §1–§4.

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
| Google API client for Gmail **or** Jakarta Mail (IMAP) — decided in SPEC-020 | SPEC-020 |
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
| Migrations | `V{n}__{snake_case}.sql`, one concern per file |

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
| | SPEC-001 | Settings | 000 | TODO |
| 1 Ingest & parse | SPEC-010 | Devices & device tokens | 000 | TODO |
| | SPEC-011 | SMS ingest API | 001, 010 | TODO |
| | SPEC-012 | Classifier | 011 | TODO |
| | SPEC-013 | Parse templates + golden set | 012 | **BLOCKED (needs masked sample SMS/emails from Ayan)** |
| | SPEC-014 | Normalisation | 013 | TODO |
| | SPEC-015 | Processing orchestrator | 012–014 | TODO |
| 2 Email | SPEC-020 | Mail fetcher | 015 | **BLOCKED (HLD O-02: Gmail API vs IMAP)** |
| 3 Match & review | SPEC-030 | Accounts & merchants | 015 | TODO |
| | SPEC-031 | Matcher & transactions | 030 | TODO |
| | SPEC-032 | Special kinds | 031 | TODO |
| | SPEC-033 | Review queue | 031 | TODO |
| | SPEC-034 | Overrides & rebuild | 031, 033 | TODO |
| | SPEC-035 | Balance check | 031, 033 | TODO |
| | SPEC-036 | Scheduler, scan runs, report, push | 020, 033 | TODO |
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

Recommended order: 000 → 001 → 010 → 011 → 012 → (013 when unblocked) → 014 → 015 → 030 → 031 → 033 → 032 → 034 → 035 → 040 → 043 → 041 → 042 → 044 → 050 → 020 → 036 → 090 → 060 → 061 → 062 → 070.

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
| `MessageClass` | `TRANSACTION`, `OTP`, `PROMO`, `MANDATE_REMINDER`, `FAILED_TXN`, `BALANCE_ONLY`, `STATEMENT`, `UNKNOWN` |
| `Direction` | `DR`, `CR` |
| `Channel` | `UPI`, `CARD`, `NEFT`, `IMPS`, `RTGS`, `ATM`, `AUTOPAY`, `OTHER` |
| `TxnKind` | `SPEND`, `INCOME`, `TRANSFER`, `REFUND`, `CC_PAYMENT`, `REVERSAL` |
| `TxnStatus` | `CONFIRMED`, `PENDING`, `HIDDEN` |
| `CategorySource` | `USER`, `RULE`, `AI`, `NONE` |
| `MatchMethod` | `FIRST`, `REF`, `WINDOW`, `MANUAL` |
| `ReviewType` | `AMBIGUOUS_MATCH`, `UNPARSED`, `LOW_CONFIDENCE`, `UNKNOWN_CATEGORY`, `UNKNOWN_KIND`, `BALANCE_GAP`, `ORPHAN_OVERRIDE` |
| `ReviewStatus` | `OPEN`, `RESOLVED`, `DISMISSED`, `AUTO_CLOSED` |

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
| `senders.allowlist` | `[]` (filled during setup) | SPEC-011, worker |
| `match.smsEmailWindowMinutes` | `180` | SPEC-031 |
| `ai.categoryConfidenceMin` | `0.80` | SPEC-061 |
| `backfill.days` | `31` | worker, SPEC-020 |

**API**
- `GET /api/v1/settings` → all settings as an object.
- `PATCH /api/v1/settings` → partial update; validated per key.

**Rules**
- R-001-1 Unknown keys are rejected (400 `UNKNOWN_SETTING`).
- R-001-2 `scan.cron` must be a valid Spring 6-field cron; invalid → 400 `INVALID_CRON`.
- R-001-3 Each allowlist entry is the stable middle part of the sender ID (e.g. `AXISBK`), uppercase, 3–8 letters.
- R-001-4 Changing a setting publishes `SettingsChanged(key)`.

**Acceptance criteria**
- AC-001-1 Fresh DB → `GET /settings` returns all defaults above.
- AC-001-2 `PATCH {"scan.cron":"bad"}` → 400 `INVALID_CRON`, value unchanged.
- AC-001-3 `PATCH {"senders.allowlist":["AXISBK","sbiinb"]}` stores `["AXISBK","SBIINB"]`.
- AC-001-4 A successful patch publishes `SettingsChanged`.

**Tasks**
- [ ] Migration + seed
- [ ] `SettingsService` with typed getters
- [ ] Controller + validation
- [ ] Event publishing

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
- [ ] Migration
- [ ] Token generation + hashing
- [ ] Security filter for device tokens
- [ ] Controllers

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
- R-011-3 Sender's middle part not in `senders.allowlist` → rejected `SENDER_NOT_ALLOWED` (defence in depth; the worker should already filter).
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

**Tests:** `IngestSmsIT` (Testcontainers), `SmsIdTest` (shared vectors also used by the worker).

**Tasks**
- [ ] Request/response records + validation
- [ ] Hash check + allowlist check
- [ ] Insert with `ON CONFLICT DO NOTHING`
- [ ] Cursor update
- [ ] After-commit event
- [ ] Publish 3 fixed test vectors (input → hash) in `src/test/resources/contract/sms-id-vectors.json` for the worker to reuse

---

### SPEC-012 — Classifier

**Goal:** Decide what a raw message is before any parsing. HLD §4.3 "Classify first".

**Depends on:** 011

**Scope:** rule-based classifier per bank. Keyword and pattern sets live in `src/main/resources/classify/*.yml`.

**Rules**
- R-012-1 Output one `MessageClass` (§4.3) plus a short `reason` string.
- R-012-2 Check order: `OTP` → `PROMO` → `FAILED_TXN` → `MANDATE_REMINDER` → `BALANCE_ONLY` → `STATEMENT` → `TRANSACTION` → `UNKNOWN`. First match wins.
- R-012-3 `TRANSACTION` needs both an amount pattern and a completed-action word (e.g. *debited*, *credited*, *spent*, *withdrawn*, *sent*, *received*). Future tense (*will be debited*, *due*, *scheduled*) is `MANDATE_REMINDER`.
- R-012-4 Result: `TRANSACTION` → status `CLASSIFIED`, publish `MessageClassified`; `FAILED_TXN` → `FAILED`; `UNKNOWN` → status `CLASSIFIED` with class `UNKNOWN` (handled by SPEC-015); all others → `IGNORED` with `ignore_reason` = class.
- R-012-5 The classifier is a pure function `classify(sender, body) → Classification` with no DB access, so it is easy to test.

**Acceptance criteria** (bodies below are illustrative; real ones come from the golden set)
- AC-012-1 "123456 is your OTP for txn of Rs 500..." → `OTP` (even though it contains an amount).
- AC-012-2 "Rs 499 will be debited on 20-11 for your mandate..." → `MANDATE_REMINDER`.
- AC-012-3 "Txn of Rs 250 failed/declined..." → `FAILED_TXN`.
- AC-012-4 "Avl Bal in A/c XX1234 is Rs 10,000" (no debit/credit) → `BALANCE_ONLY`.
- AC-012-5 "Rs 450.00 debited from A/c XX1234 ... UPI Ref 4321..." → `TRANSACTION`.
- AC-012-6 Every message in the golden set (SPEC-013) is classified as its fixture says.

**Tasks**
- [ ] Classification record + rules loader
- [ ] Ordered rule engine
- [ ] Unit tests for AC-012-1..5
- [ ] Hook into golden runner when SPEC-013 lands

---

### SPEC-013 — Parse templates + golden set

**Goal:** Pull fields out of `TRANSACTION` messages using versioned, bank-specific regex templates, proven by a golden test set.

**Depends on:** 012 · **BLOCKED** until Ayan provides 5–10 masked samples per source: Axis SMS, Axis email, SBI SMS, SBI email, SBI Card SMS, SBI Card email.

**Golden set layout**
```
src/test/resources/golden/
  axis/sms/001.json
  axis/email/001.json
  sbi/sms/...
  sbicard/sms/...
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

**Masking rule for fixtures:** replace real names with fake ones of similar shape, change every digit of ref nos and balances, keep amounts realistic, keep **all punctuation, spacing and line breaks exactly**.

**Templates** — `src/main/resources/parse-templates/<bank>.yml`
```yaml
- id: axis.sms.upi_debit
  version: 1
  bank: AXIS
  source: SMS
  senderPattern: "AXISBK"
  regex: "(?s)INR (?<amount>[\\d,]+\\.\\d{2}) debited.*?A/c no\\. XX(?<last4>\\d{4}).*?UPI/P2[AM]/(?<ref>\\d{12})/(?<cp>[^\\n]+)"
  fixed: { direction: DR, channel: UPI }
```
Named groups recognised: `amount`, `dir`, `last4`, `channel`, `ref`, `cp`, `date`, `time`, `datetime`, `bal`. `fixed:` sets fields the text doesn't spell out.

**Data** — `V4__parse.sql`: `parse_template` (id, version, bank, source, sender_pattern, regex, fixed JSONB, active; PK `(id, version)`) and `parsed_record` (HLD §4.7 columns, plus `template_id`, `template_version`, `parser` `TEMPLATE|LLM`, `confidence`).

**Rules**
- R-013-1 Templates are loaded from YAML at startup and upserted into `parse_template` by `(id, version)`. Changing a regex means a **new version**; old versions stay for audit.
- R-013-2 Candidate templates = matching `bank`, `source` and `senderPattern`. Try in YAML order; first full match wins.
- R-013-3 A template match must yield at least `amount` and `direction`; otherwise it doesn't count as a match.
- R-013-4 No match → no `parsed_record`; message goes to the unparsed path (SPEC-015).
- R-013-5 Template parse confidence = `1.0`.

**Acceptance criteria**
- AC-013-1 A parameterised golden test runs every fixture; each produces exactly the expected class, template ID and fields.
- AC-013-2 Every active template is used by at least one fixture (no untested template).
- AC-013-3 Changing a template's regex without bumping `version` fails a startup check (hash of regex stored per version).

**Tasks**
- [ ] Receive and mask samples (Ayan)
- [ ] Fixture loader + parameterised golden test
- [ ] Template YAML loader + DB upsert
- [ ] Template engine
- [ ] Templates for Axis, SBI, SBI Card (SMS + email)

---

### SPEC-014 — Normalisation

**Goal:** Turn template captures into clean typed fields.

**Depends on:** 013

**Rules**
- R-014-1 `amount`/`bal` → paise via `Money.parseInr`.
- R-014-2 `dir` words → `DR` (*debited, spent, withdrawn, sent, paid, purchase*) or `CR` (*credited, received, deposited, refund*). Unknown word → parse fails.
- R-014-3 Dates: accept at least `dd-MM-yy`, `dd-MM-yyyy`, `dd/MM/yy`, `dd/MM/yyyy`, `ddMMMyy`, `dd-MMM-yy`, `dd MMM yyyy`, with optional `HH:mm[:ss]`. Interpreted in `Asia/Kolkata`.
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
- [ ] `Normaliser` + unit tests
- [ ] Date parser with format list

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
- R-015-6 `TRANSACTION` + no template, or class `UNKNOWN` with an amount pattern → status `PARSED` is **not** set; a review item `UNPARSED` is created (until SPEC-062 adds LLM fallback).
- R-015-7 `reprocess(rawMessageId)` and `reprocessAll()` exist for rebuilds (SPEC-034) and clear derived rows for those messages first.

**Acceptance criteria**
- AC-015-1 Two triggers at once → only one processes; no message processed twice.
- AC-015-2 A message that throws during parsing → `ERROR`; the next message in the batch is still processed.
- AC-015-3 An OTP message ends `IGNORED` with reason `OTP` and no parsed record.
- AC-015-4 A transaction-like message with no template → one `UNPARSED` review item, no transaction.

**Tasks**
- [ ] Advisory lock helper in `shared`
- [ ] Orchestrator + listeners
- [ ] Reprocess API (internal service; no HTTP yet)

---

### SPEC-020 — Mail fetcher

**Goal:** Pull bank emails into `raw_message` daily and on backfill.

**Depends on:** 015 · **BLOCKED** on HLD O-02.

**Decision needed first (record in §6):**
- Option A — **Gmail API** (`gmail.readonly`): needs an OAuth client. Risk: if the OAuth app stays in Google's "Testing" status, refresh tokens may expire after about a week. Must verify current Google policy and either publish the app or accept re-consent.
- Option B — **IMAP** with a Google app password (requires 2-Step Verification). Simpler token handling.

**Scope (either option)** — interface in `mail`:
```java
public interface MailSource {
  List<RawEmail> fetchSince(Instant since, Set<String> fromAddresses);
}
record RawEmail(String id, String from, String subject, String textBody, Instant receivedAt) {}
```

**Rules**
- R-020-1 Only emails from configured bank sender addresses (`settings: mail.fromAllowlist`, new key) are fetched.
- R-020-2 HTML bodies are converted to plain text before storage (keep line breaks; drop scripts/styles/images).
- R-020-3 Stored as `raw_message` with `source=EMAIL`, `id` per §4.2, `sender` = from address, `body` = `subject + "\n\n" + textBody`.
- R-020-4 `fetchSince` start = last successful fetch time minus 1 day overlap (duplicates are harmless due to IDs). First run = now − `backfill.days`.
- R-020-5 Credentials come from env vars / encrypted storage, never from code.

**Acceptance criteria**
- AC-020-1 With a fake `MailSource` returning 3 emails (1 from a non-allowlisted sender) → 2 stored.
- AC-020-2 Running the fetch twice over the same window → no extra rows.
- AC-020-3 HTML fixture → expected plain text.

**Tasks**
- [ ] Decide A/B (Ayan) and record
- [ ] `MailSource` interface + fake
- [ ] Real implementation
- [ ] HTML → text
- [ ] Wire into SPEC-036

---

### SPEC-030 — Accounts & merchants

**Goal:** Resolve `accountLast4` + bank to an account, and raw counterparty strings to merchants.

**Depends on:** 015

**Data** — `V5__accounts_merchants.sql`: `account`, `merchant`, `merchant_alias` (HLD §4.7). Unique `(bank, last4)` on account; unique `raw_name_normalised` on alias.

**Rules**
- R-030-1 Unknown `(bank, last4)` → account auto-created with `type` guessed from template bank (`SBI_CARD` → `CREDIT_CARD`, else `SAVINGS`) and `nickname = bank + " ••" + last4`.
- R-030-2 Alias normalisation: uppercase, remove `*`, digits runs ≥ 4, and common suffixes (`PVT LTD`, `LIMITED`, `INDIA`, `BLR`, city codes list), collapse spaces.
- R-030-3 Unknown normalised alias → new merchant with `type`: `PERSON` if the raw string looks like a personal UPI ID or a name of 2–3 words with no business keywords; else `MERCHANT`.
- R-030-4 Merchants can be merged (`mergeMerchants(from, into)`): aliases move, transactions repoint.

**Acceptance criteria**
- AC-030-1 `SWIGGY*BLR` and `Swiggy Ltd` resolve to the same merchant.
- AC-030-2 `rahul.k@okaxis` → `PERSON`.
- AC-030-3 Merge moves aliases and transactions; the old merchant is deleted.

**Tasks**
- [ ] Migration
- [ ] Resolver services + tests

---

### SPEC-031 — Matcher & transactions

**Goal:** Turn parsed records into transactions with evidence, without duplicates. HLD §4.4.

**Depends on:** 030

**Data** — `V6__transactions.sql`: `transaction`, `transaction_evidence` (HLD §4.7) plus:
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
   - 0 candidates → new transaction, `match_method=FIRST`, `status=CONFIRMED`.
   - 1 candidate and channels equal (or one unknown) → attach, `match_method=WINDOW`.
   - otherwise → new transaction `status=PENDING` + review `AMBIGUOUS_MATCH` listing candidates.
- R-031-4 A new transaction copies fields from its first record; later evidence fills **only null** fields (e.g. an email bringing the ref no.), never overwrites.
- R-031-5 When later evidence adds a `refNo` that makes another `PENDING` transaction's open `AMBIGUOUS_MATCH` resolvable, that review item is `AUTO_CLOSED` and the pair is merged.
- R-031-6 New transactions start `kind=SPEND` for `DR`, `INCOME` for `CR`; SPEC-032 may change it. Publish `TransactionCreated` / `TransactionUpdated`.
- R-031-7 Whole match step for one record runs in one DB transaction; unique-index violation → retry once as a ref match.

**Acceptance criteria**
- AC-031-1 SMS with ref R, then email with ref R → 1 transaction, 2 evidence rows.
- AC-031-2 SMS without ref, email 40 min later same amount/account/direction → 1 transaction (`WINDOW`).
- AC-031-3 Email arrives 5 h later (window 180) → 2 transactions.
- AC-031-4 Two SMS, same amount/account, 2 min apart, no refs → **2 transactions**.
- AC-031-5 Two SMS candidates exist for one email → new `PENDING` transaction + `AMBIGUOUS_MATCH` review.
- AC-031-6 Email adds ref to an SMS-only transaction → `ref_no` filled, nothing else overwritten.
- AC-031-7 The same parsed record processed twice → still one evidence row.

**Tasks**
- [ ] Migration
- [ ] Matcher service (pure decision function + persistence wrapper)
- [ ] Scenario tests AC-031-1..7

---

### SPEC-032 — Special kinds

**Goal:** Stop transfers, card bill payments and reversals from inflating totals. HLD §4.4 table.

**Depends on:** 031

**Rules**
- R-032-1 **CC payment:** a `DR` on a savings account and a `CR` on a credit-card account, same amount, within 72 h, where either text mentions the card/biller (`card`, `SBI Card`, `CC payment`, `BillDesk`, card last4) → both `kind=CC_PAYMENT`, linked via `linked_txn_id`.
- R-032-2 **Own transfer:** `DR` on one own savings account + `CR` on another own savings account, same amount, within 24 h → both `TRANSFER`.
- R-032-3 **Reversal:** a `CR` with the same `refNo` as an earlier `DR` (or text says *reversed/reversal/refund of failed*) → both `REVERSAL`, `status=HIDDEN`.
- R-032-4 **Refund:** `CR` from a merchant that has an earlier `DR` (any amount ≤ original) within 60 days, text contains *refund* → `REFUND`, linked to the most recent such `DR`.
- R-032-5 Any rule that matches more than one candidate → review `UNKNOWN_KIND`; no automatic change.
- R-032-6 User-set kind (override) is never changed by these rules.

**Data** — `V7__txn_links.sql`: `ALTER TABLE transaction ADD COLUMN linked_txn_id UUID REFERENCES transaction(id);`

**Acceptance criteria**
- AC-032-1 SBI debit ₹12,000 "SBI Card payment" + SBI Card credit ₹12,000 next day → both `CC_PAYMENT`, excluded from spend.
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

**Data** — `V8__review.sql`: `review_item` (HLD §4.7) with `transaction_id` nullable, `raw_message_id` nullable, `dedup_key TEXT UNIQUE` (so the same question is never opened twice).

**Payload shapes**

| Type | `payload` | Allowed `resolution` |
|---|---|---|
| `AMBIGUOUS_MATCH` | `{transactionId, candidateIds[]}` | `{action:"MERGE", into:<id>}` or `{action:"KEEP_SEPARATE"}` |
| `UNPARSED` | `{rawMessageId}` | `{action:"MANUAL", fields:{...}}` or `{action:"IGNORE"}` |
| `LOW_CONFIDENCE` | `{parsedRecordId, fields}` | `{action:"CONFIRM"}` / `{action:"CORRECT", fields}` / `{action:"IGNORE"}` |
| `UNKNOWN_CATEGORY` | `{transactionId, suggestion?}` | `{categoryId, scope:"THIS"\|"ALWAYS", applyPast?:bool}` |
| `UNKNOWN_KIND` | `{transactionId, candidates[]}` | `{kind, linkTo?}` |
| `BALANCE_GAP` | `{accountId, from, to, expectedPaise, actualPaise}` | `{action:"ACK"}` or `{action:"ADD_MANUAL", fields}` |
| `ORPHAN_OVERRIDE` | `{overrideId}` | `{action:"DROP"}` or `{action:"REAPPLY", transactionId}` |

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

**Data** — `V9__overrides_audit.sql`: `user_override` (HLD §4.7) and `audit_log(id, at, actor, entity, entity_id, action, before JSONB, after JSONB)`.

**Rules**
- R-034-1 An override is anchored to the **earliest raw message** among the transaction's evidence (`anchor_raw_message_id`), plus `field`, `value`, `created_at`.
- R-034-2 Override fields: `CATEGORY`, `KIND`, `NOTE`, `HIDE`, `MERGE_WITH` (other anchor), `SPLIT` (parts), `MANUAL_FIELDS`.
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

**Goal:** Detect missing or misread messages using the balance printed in bank SMS. HLD §4.9.

**Depends on:** 031, 033

**Rules**
- R-035-1 Per account, take confirmed+pending transactions ordered by `txn_time` that carry `balance_after_paise`.
- R-035-2 For each consecutive pair (A, B) with balances: `expected = A.balance + Σ CR − Σ DR` over transactions strictly after A up to and including B. If `expected ≠ B.balance` → review `BALANCE_GAP` with both values and the period.
- R-035-3 Hidden reversals are **included** in this sum (money did move).
- R-035-4 Runs at the end of each scan (SPEC-036) for transactions touched since the last check.
- R-035-5 A gap that becomes consistent after new data arrives → its review item `AUTO_CLOSED`.

**Acceptance criteria**
- AC-035-1 Balances 10,000 → (−450) → 9,550 → no gap.
- AC-035-2 Balances 10,000 → (−450) → 9,000 → `BALANCE_GAP` expected 9,550 actual 9,000.
- AC-035-3 A late SMS for the missing −550 arrives → gap auto-closes.

**Tasks**
- [ ] Checker + tests

---

### SPEC-036 — Scheduler, scan runs, report, push

**Goal:** The daily job: pull email, process, check, report, notify. HLD §4.9.

**Depends on:** 020, 033 (and 035 when done)

**Data** — `V10__scan_run.sql`: `scan_run` (HLD §4.7) + `trigger TEXT` (`CRON|MANUAL|STARTUP`).

**API**
- `GET /api/v1/scan-runs/latest`, `GET /api/v1/scan-runs?cursor=`
- `POST /api/v1/scan-runs` → starts a manual run, returns its id (202).

**Rules**
- R-036-1 Server cron = `scan.cron` shifted by `scan.serverDelayMinutes`, in `scan.zone`. Re-registered on `SettingsChanged(scan.*)` without restart.
- R-036-2 Steps: mail fetch → `processPending()` → balance check → build report → push. Each step's failure is recorded; later steps still run where possible.
- R-036-3 Only one scan at a time (same advisory lock family).
- R-036-4 Report = counts (new SMS, new emails, transactions created/merged, review opened, errors), spend for the day, and open review count.
- R-036-5 Push via FCM to every non-revoked device with an `fcm_token`: title "Hishaab daily scan", body e.g. "12 new · ₹3,420 spent · 2 need review". No merchant names in the push text.
- R-036-6 A scan with `errors > 0` or a failed step also sends a push "Scan had problems — open Sync status".

**Acceptance criteria**
- AC-036-1 Changing `scan.cron` reschedules (verify via scheduler registry in test).
- AC-036-2 A run with a mail failure still processes SMS and records `status=PARTIAL`.
- AC-036-3 Report numbers match DB counts for the run window.
- AC-036-4 Push sender is called once per device with the expected text (FCM mocked).

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
{ "spentPaise": 342000, "receivedPaise": 5000000, "refundsPaise": 12000,
  "pendingPaise": 124000, "pendingCount": 2,
  "groups": [ { "key": "...", "label": "Food", "spentPaise": 81200, "count": 23 } ],
  "lastSyncAt": "...", "lastScanAt": "...", "openReviewCount": 3 }
```

**Rules (the definition of "spend")**
- R-041-1 `spentPaise` = Σ `DR` where `kind=SPEND` and `status=CONFIRMED`, **minus** Σ `REFUND` (`CR`) linked to those.
- R-041-2 `receivedPaise` = Σ `CR` where `kind=INCOME` and `status=CONFIRMED`.
- R-041-3 `TRANSFER`, `CC_PAYMENT`, `REVERSAL`, `HIDDEN` never count.
- R-041-4 `pendingPaise` = Σ amount of `PENDING` (shown separately, never in totals).
- R-041-5 Chat (SPEC-070) must call this service, not recompute.

**Acceptance criteria**
- AC-041-1 Fixture month with spends, a refund, a CC payment, a transfer, a reversal and a pending → totals equal hand-computed values in the test.
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
- R-090-1 All secrets via env file on the VM, never in the repo.
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
| | | | | |

---

## 7. Open questions (must be answered before the blocked specs)

| # | Question | Blocks | Owner |
|---|---|---|---|
| Q-1 | Provide masked sample SMS + emails for Axis, SBI, SBI Card (5–10 each) | SPEC-013 | Ayan |
| Q-2 | Gmail API or IMAP (HLD O-02) | SPEC-020 | Ayan |
| Q-3 | Bank email sender addresses for `mail.fromAllowlist` | SPEC-020 | Ayan |
| Q-4 | Sender ID middle parts for `senders.allowlist` (from your inbox) | SPEC-011 go-live | Ayan |
| Q-5 | super.money credit card in scope? | SPEC-030 | Ayan |
| Q-6 | Subdomain | SPEC-090 | Ayan |
