# Monnie assistant API (`/ai/sdk`)

> **Building a web or mobile client? Read
> [ai-assistant-frontend-guide.md](ai-assistant-frontend-guide.md) instead.** It covers
> the same API from the client side, with worked code, the frame reducer, card
> rendering, recovery handling and a pre-ship checklist.
>
> This document is the API and operations reference: endpoint contracts, configuration,
> and how the SDK is built and published.

The in-process agent, exposed entirely over HTTP and STOMP. Nothing in a client, and
nothing outside `com.moniewise.moniewise_backend.ai.sdk`, constructs or calls the SDK
directly.

Older Gemini routes under `/ai/*` are a separate, unrelated feature and are unaffected.

---

## 1. The shape of it

```
GET  /ai/sdk/status                      is the assistant available, and what are the limits
GET  /ai/sdk/capabilities                what it can read, what it can propose

POST /ai/sdk/threads                     open a conversation (optional)
GET  /ai/sdk/threads                     inbox listing
GET  /ai/sdk/threads/{id}
PATCH  /ai/sdk/threads/{id}              rename; set guidance while it has none
DELETE /ai/sdk/threads/{id}              soft delete
GET  /ai/sdk/threads/{id}/messages       history, and the way to recover a dropped turn
GET  /ai/sdk/threads/{id}/actions        pending proposals in this thread

POST /ai/sdk/turn                        send a message, wait for the whole answer
POST /ai/sdk/turn/async                  send a message, 202, frames over the socket

GET  /ai/sdk/actions                     my proposals across every thread
GET  /ai/sdk/actions/{id}
POST /ai/sdk/actions/{id}/confirm        authorise (PIN when required)
POST /ai/sdk/actions/{id}/cancel
```

Every route needs the session JWT: `Authorization: Bearer <accessToken>`. Identity is
taken from that token and never from a request body, so there is no user id to pass
anywhere. Another user's thread or action returns **404**, not 403 — a 403 would confirm
the id exists.

Start with `GET /ai/sdk/status`. It answers even when the agent is switched off, which is
how a client decides whether to show the assistant at all rather than discovering a 503
at the moment a user taps it.

---

## 2. Surfaces and instructions

This is the part most likely to be misunderstood, so it is worth being precise.

### Surfaces

A **surface** says where the conversation is happening. One of:

| Surface | Server guidance it always applies |
|---|---|
| `CHAT` | none — open conversation |
| `ONBOARDING` | new user; help create a first budget and envelopes, one question at a time, do not push withdrawals or transfers |
| `DASHBOARD` | on the home screen; be brief, prefer a single next action |
| `BUDGET_ASSISTANT` | refine this budget, one question at a time, do not invent envelope amounts |

Case-insensitive on the way in and stored upper-case. An unrecognised surface is a **400**
rather than a silent default — otherwise `"onboarding-v2"` would get no guidance, would
not match your own `?surface=` filter, and would look like the feature simply did not
work.

### Instructions

`instructions` is free text you attach to a thread to specialise the agent for a screen —
an onboarding navigator, a budget coach, a particular tone.

**What it is:** a *specification chosen from what the agent already does*. It narrows and
directs behaviour.

**What it is not:** a replacement personality, and not a way to add a capability. Three
separate mechanisms make that true, and only the last is a matter of prompt text:

1. Your text is appended **after** the agent's own system prompt, which always stays in
   force and always comes first. It cannot replace it.
2. It is wrapped in a preamble that states it cannot waive PIN, skip tools, invent
   balances or execute money.
3. The real guarantees are in code the prompt cannot reach: the tool catalog is fixed at
   startup, the set of possible mutations is a closed enum, every mutation needs a
   separate PIN-authenticated request, and a guard fails closed if the model states a
   figure the server did not compute.

So guidance asking for something outside `GET /ai/sdk/capabilities` does not get it,
however it is phrased. Write guidance about *tone, pacing, and which of the available
things to focus on* — that is what it is for and what it does well.

**Limits and lifecycle**

- Maximum 2000 characters, measured after normalisation (CRLF collapsed, control
  characters stripped, runs of blank lines squeezed). Over that is a 400.
- **Write-once per thread.** The first non-blank value wins. A later `/turn` body cannot
  swap it, and `PATCH` on a thread that already has guidance is a 400. Guidance is
  configuration set when a conversation opens; if any message could rewrite it, then any
  message could re-specify the assistant mid-conversation — which is exactly the
  capability an injected instruction would want. **To use different guidance, create a new
  thread.**
- The owner can read back the active text: thread responses include `instructions`.

```jsonc
// POST /ai/sdk/threads
{
  "surface": "ONBOARDING",
  "title": "First budget",
  "instructions": "You are the guide on the onboarding screen. Ask one question at a time. Use plain language, no jargon. Focus on getting a first budget created; do not bring up transfers."
}
```

The server starter for the surface is prepended automatically, so the agent receives the
onboarding guidance *and* yours, in that order.

---

## 3. Sending a message

### Buffered — `POST /ai/sdk/turn`

Simplest, and right for a screen with no socket. Returns when the agent has finished.

```jsonc
{
  "threadId": "8f0e…",            // omit to start a new conversation
  "text": "Help me create my first budget.",
  "surface": "ONBOARDING",        // used only when creating the thread
  "instructions": "…",            // applied only if the thread has none
  "clientMessageId": "…",         // optional; echoed on every frame
  "clientRequestId": "…"          // optional; echoed on every frame
}
```

```jsonc
{
  "threadId": "8f0e…",
  "turnId": "01J…",
  "text": "Let's start with what comes in each month…",
  "finishReason": "OK",
  "actionsProposed": 0,
  "frames": [ /* every frame the turn produced, in order */ ]
}
```

The cost is latency: nothing reaches the user until the agent has finished, which for a
turn that calls several tools is a few seconds of silence.

### Live — `POST /ai/sdk/turn/async` + STOMP

Same body. Returns **202** at once:

```jsonc
{
  "threadId": "8f0e…",
  "clientMessageId": "…",
  "clientRequestId": "…",
  "destination": "/user/queue/ai"
}
```

Frames then arrive on that destination as they happen.

```js
const client = new Client({
  brokerURL: "wss://<host>/ws",
  connectHeaders: { Authorization: `Bearer ${accessToken}` },
});

client.onConnect = () => {
  // Subscribe BEFORE posting. Frames are pushed live, not queued for replay:
  // subscribe after and the opening frames of the turn are simply missed.
  client.subscribe("/user/queue/ai", (msg) => handleFrame(JSON.parse(msg.body)));

  fetch("/ai/sdk/turn/async", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ threadId, text, surface: "ONBOARDING" }),
  });
};

client.activate();
```

Everything that can fail — rate limit, unknown surface, oversize guidance, agent
disabled — is checked before the turn starts, so those stay ordinary HTTP errors on the
202 call instead of becoming an error frame you have to special-case.

If the socket drops mid-turn, **do not resend**. The answer is generated and persisted
regardless; refetch with `GET /ai/sdk/threads/{id}/messages?after=<lastId>`.

---

## 4. The frame protocol

Every frame is one complete JSON document:

```jsonc
{
  "v": 1,                     // protocol version; also in GET /ai/sdk/status
  "type": "TEXT_DELTA",
  "turnId": "01J…",
  "threadId": "8f0e…",
  "clientMessageId": "…",     // echoed from your request
  "clientRequestId": "…",
  "seq": 7,                   // monotonic per turn, from 0
  "ts": 1757068530123,
  "payload": { "text": "…" }
}
```

| Type | Payload | Notes |
|---|---|---|
| `THREAD_CREATED` | `threadId`, `title` | only when the turn opened a new thread |
| `USER_MESSAGE` | `messageId`, `text`, `hidden` | echo, sent once the row is committed |
| `TURN_START` | `turnId`, `acceptedAt` | |
| `ROUTE` | `agents[]` | which agent handled it |
| `THINKING` | `text`, `phase` | ephemeral narration; never persisted, discard on first `TEXT_DELTA` |
| `TOOL_START` | `callId`, `tool`, `displayLabel` | `displayLabel` is user-facing prose; raw arguments are deliberately absent |
| `TOOL_END` | `callId`, `tool`, `shape`, `durationMs` | counts and flags, never values |
| `TEXT_DELTA` | `text` | a chunk of the real answer. **Prose only** — no structured data ever travels here |
| `ACTION_PROPOSED` | `actionId`, `kind`, `render`, `requiresPin`, `expiresAt` | draw the confirmation card |
| `ACTION_RESULT` | `actionId`, `status`, `message`, `receipt` | pushed by confirm/cancel, not by the turn |
| `TURN_END` | `turnId`, `finishReason`, `totalMs`, `usage` | **exactly one per turn, always, on every exit path** |
| `ERROR` | `code`, `message`, `retryable` | |
| `RATE_LIMITED` | `retryAfterSeconds` | |
| `AUTH_REVOKED` | `reason` | session expired mid-turn; send the user to sign in |

Three client rules:

- **Ignore unknown `type` values** rather than treating them as errors. New frame types
  ship without a client release; `v` only changes on a breaking change.
- **A gap in `seq` means you missed something.** Refetch messages rather than rendering a
  hole.
- **`TURN_END` always arrives**, including on error, timeout, and when the agent is at
  capacity. Drive your spinner off it and it cannot hang.

`finishReason`: `OK`, `ABORTED`, `ERROR`, `RATE_LIMITED`, `BUDGET_EXCEEDED`, or `BUSY`.
`BUSY` means the service is saturated (try again shortly); `RATE_LIMITED` means this user
is asking too often. A turn that failed still carries `text` with something to show.

---

## 5. Actions: proposing, confirming, cancelling

**The agent never moves money.** The most a turn can do is leave a *prepared action* — a
row and a card. Execution takes a separate, PIN-authenticated request from the user.

An `ACTION_PROPOSED` frame (or `GET /ai/sdk/actions`) gives you:

```jsonc
{
  "id": "act_…",
  "kind": "ENVELOPE_TRANSFER_EXTERNAL",
  "label": "Send to bank account",
  "riskTier": "MONEY_OUT",
  "status": "PENDING",
  "requiresPin": true,
  "movesMoney": true,
  "paramsHash": "9c1f…",
  "editableFields": ["amount"],
  "render": {
    "title": "Send ₦25,000 to GTBank",
    "rows": [
      { "key": "amount", "label": "Amount",
        "after": { "type": "MONEY", "raw": "25000.00", "display": "₦25,000.00" },
        "editable": true },
      { "key": "fee", "label": "Fee",
        "after": { "type": "MONEY", "raw": "50.00", "display": "₦50.00" } }
    ],
    "emphasisRowKey": "amount",
    "notes": [{ "level": "CRITICAL", "text": "This cannot be reversed." }],
    "confirmLabel": "Send", "cancelLabel": "Cancel"
  },
  "expiresAt": "2026-09-04T10:20:30Z"
}
```

Render the card from `render.rows`. Do **not** map `kind` to a hand-written screen: rows
are declarative precisely so a new action kind needs no client release, and every
`display` string was rendered server-side. Show `notes` — `CRITICAL` ones cover
irreversibility and forfeited interest. Respect `expiresAt`.

### Confirm

```jsonc
// POST /ai/sdk/actions/{id}/confirm
{
  "pin": "1234",              // required when requiresPin
  "paramsHash": "9c1f…",      // the hash from the card that was shown
  "edits": { "amount": "20000.00" }   // only keys in editableFields
}
```

`paramsHash` binds the confirmation to the figures the user actually saw, so an amount
cannot change without the hash changing. An edit to a field not in `editableFields` is
rejected.

The response carries the resolved action and the same `ACTION_RESULT` frame that was
pushed to the socket, so an HTTP-only client gets the receipt without a socket:

```jsonc
{
  "action": { "id": "act_…", "status": "EXECUTED", "executionRef": "AI-…", … },
  "resultFrame": { "type": "ACTION_RESULT", "payload": { "status": "EXECUTED", "message": "…", "receipt": { … } } }
}
```

`POST /ai/sdk/actions/{id}/cancel` resolves it as `REJECTED` and emits the same frame.

Statuses you will see: `PENDING`, `EXECUTED`, `PENDING_PROVIDER` (accepted by a provider,
settlement pending — **never present this as done**), `FAILED`, `REJECTED`, `EXPIRED`,
`SUPERSEDED` (a newer proposal of the same kind replaced it).

---

## 6. Errors and limits

All errors share one shape:

```jsonc
{ "status": 409, "error": "Conflict", "message": "That is already in flight.", "timestamp": "…" }
```

| Status | Means |
|---|---|
| 400 | validation: blank text, unknown surface or status, oversize guidance, wrong PIN, guidance on a thread that has some |
| 404 | not yours, or does not exist |
| 409 | the action was already claimed, or is still running — **do not retry the identical request** |
| 429 | rate limited; honour `Retry-After` |
| 503 | the assistant is not enabled or has no model key — check `GET /ai/sdk/status` |

Rate limits, per user and IP per hour: turns 40, confirms 20, chat reads 240, prepares 30.

Page sizes are clamped rather than rejected: threads 50, messages 100, actions 50.

---

## 7. Building against the SDK

The agent lives in the `com.moniewise:monnie-sdk-*` artifacts, published to **GitHub
Packages** from [`WISEMONIE/monnieSDK`](https://github.com/WISEMONIE/monnieSDK). Every
push to that repo's `main` publishes `0.1.0-SNAPSHOT` automatically via its
`publish` workflow — no one runs a deploy by hand.

Reading those artifacts needs credentials. `.mvn/settings.xml` is committed and contains
no secret, only two placeholders:

```bash
export GH_PACKAGES_USER=<github-username>
export GH_PACKAGES_TOKEN=<classic PAT with read:packages>
mvn -s .mvn/settings.xml -U clean package
```

The token must be a **classic** PAT — fine-grained tokens do not cover Packages. Use a
machine account so a departing developer does not break deploys.

`-U` matters: the snapshot repository is configured `updatePolicy=never` so a developer
without a token is never blocked by a 401, and `-U` is what forces the newest SDK build.

**Docker / Render.** The build stage takes the token either as a BuildKit secret
(preferred — it never reaches image metadata) or as a build arg:

```bash
docker build --secret id=GH_PACKAGES_TOKEN,src=./gh_token .
docker build --build-arg GH_PACKAGES_TOKEN=ghp_xxx .
```

On Render, add a **secret file** named `GH_PACKAGES_TOKEN`; Render exposes secret files
to `docker build` as BuildKit secrets under the same id. Setting it as a build-time
environment variable works too, via the `ARG` fallback. Either way the token stays in the
build stage — the runtime image copies only the jar.

**Locally without a token**: `mvn install` the sibling `monnieSDK` checkout and the
snapshot resolves from `~/.m2`.

---

## 8. Configuration

All under `ai.sdk.*`, supplied by environment (`application.properties` is gitignored):

| Property | Default | Notes |
|---|---|---|
| `ai.sdk.enabled` | `true` | false leaves `/ai/sdk/status` answering `enabled:false` and everything else 503 |
| `ai.sdk.provider` | inferred | `gemini` \| `groq` \| `openai`; inferred from whichever key is present |
| `ai.sdk.model` | provider default | |
| `ai.sdk.groq-api-key` / `ai.sdk.openai-api-key` | — | also read from `GROQ_API_KEY` / `OPENAI_API_KEY`; Gemini uses `gemini.api-key` / `GEMINI_API_KEY` |
| `ai.sdk.history-limit` | `24` | prior messages replayed into each turn |
| `ai.sdk.turn-budget-seconds` | `90` | wall clock before a turn is stopped |
| `ai.sdk.enabled-kinds` | all | allowlist of action kinds; a kind left out is unreachable |
| `ai.sdk.push.destination` | `/queue/ai` | clients subscribe to `/user` + this |

The agent bean only starts when `ai.sdk.enabled` is true **and** a model key is present,
so a local run without credentials still boots.

---

## 9. Known follow-ups

- `MonnieActionExecutor` hard-codes some business defaults: `CREATE_BUDGET` always builds
  three envelopes (Needs/Wants/Savings) at a third each, `CREATE_SAVINGS_GOAL` assumes a
  0.12 rate and a six-month maturity, `LOCK_ENVELOPE` assumes 30 days, and transfer notes
  are literals like "AI move". These should come from `SystemConfigService` or the request.
- `VasCatalogPort.quoteAirtime` returns the amount unchanged instead of applying markup
  via `MarkupCalculatorService`, and `airtimeNetworks()` is a hard-coded list.
- `MoniewiseBackendApplicationTests.contextLoads` needs a Postgres URL that CI has no
  source for, so CI excludes it. Give it a service container and test datasource
  properties to bring it back.
- The STOMP simple broker is in-process: with more than one instance, user-queue delivery
  needs a relay or sticky sessions. The savings-projection push has the same limitation.
