# The Reply Brain (Professional Mode)

The Reply Brain is a **structured, user-editable memory** that gets injected into the prompt at
reply time. It is what makes a professional reply *right for that person, in that context, in the
user's voice* — instead of a generic AI message.

Built with **retrieval (RAG)**, not fine-tuning, so it is cheap to update and **instantly deletable**.

## Data model

All fields are user-editable and deletable.

### 1. Identity & business
- Who the user is (name, role)
- Company / business
- What they sell (products / services)

### 2. Hard rules (enforced, not suggested)
- Price floor — e.g. "never discount below 15%"
- Payment terms — e.g. "50% advance"
- Words / phrases to avoid
- Non-negotiables

> Hard rules are passed as constraints the model must not violate. The backend also does a
> post-generation check (e.g. reject a draft that offers a discount below the floor).

### 3. Voice
- Tone settings (e.g. polite but confident, concise, warm)
- A handful of **approved past replies** used as style samples

### 4. Contact memory (per contact)
- Notes about the person
- Relationship stage (new lead / active client / long-term / cold)
- Past commitments made to them
- Preferred tone for this contact

### 5. Situation type (detected per thread)
`sales · support · negotiation · hiring · follow-up · complaint · scheduling`

Detection shapes the reply strategy (e.g. a *complaint* leads with acknowledgement; a *negotiation*
holds the price floor).

## Prompt assembly

```
System:
  You are the user's private reply assistant. Write ONE best reply in the user's voice.
  Respect all HARD RULES. Do not send — only draft.

Context blocks (retrieved, only what's relevant):
  [IDENTITY]        who / company / what they sell
  [HARD RULES]      price floor, terms, words to avoid   ← must not be violated
  [VOICE]           tone + 2–4 approved style samples
  [CONTACT MEMORY]  notes, stage, past commitments (this contact only)
  [SITUATION]       detected type + strategy hint

Current message (from screen):
  "<the incoming message>"

→ One reply, in the box.
```

## Example

**Contact:** active client. **Rules:** no discount below 15%, 50% advance, polite but confident.

> Client: *Can you reduce the price?*

Retrieved: hard rules (15% floor, 50% advance) + voice (polite, confident) + contact stage (active).

> ReplyMint: *"I understand where you're coming from. I can offer a 10% adjustment, but I wouldn't
> want to reduce the scope or quality beyond that. If that works for you, we can confirm with 50%
> advance and get started."*

## Storage (Phase 2)

- **Postgres** for structured rows (identity, rules, contacts, situations).
- **pgvector** for semantic retrieval of voice samples and contact notes.
- Retrieval returns only the slices relevant to the current contact + situation, keeping prompts
  small (→ faster, cheaper generation).

## Learning loop

When the user **approves** (sends) a professional draft, it can be added as a voice/style sample or
a contact note — **only with consent**, and always removable. This is how the Brain gets sharper
without ever storing raw chat history wholesale.

## Why RAG, not fine-tuning

| | RAG | Fine-tuning |
|---|---|---|
| Update speed | Instant | Slow retrain |
| Delete a memory | Immediate & real | Cannot truly un-learn |
| Cost | Low | High |
| Privacy promise ("editable & deletable") | ✅ Satisfied | ❌ Violated |
