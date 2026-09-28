# VoxTicket Phase 3 — Routing Evaluation Corpus Rubric

Research labeling rubric for the held-out routing evaluation corpus. These labels are
**deterministic research labels** based on conversational/reasoning complexity — NOT on
business risk, security sensitivity, or which backend *should* handle the request.

## Label definitions

### TIER_1 — simple
One clear intent, directly answerable or directly executable:
- greeting
- direct order status ("where is my order")
- straightforward FAQ
- direct policy question (return window, delivery fee, …)
- simple reference follow-up (one order reference, one question about it)
- simple cancellation request (ONE order, unconditional)
- simple return request (ONE order/item, unconditional)
- simple claim request (ONE item, direct)
- direct single-order lookup

**A protected mutation (cancel/return/claim) must NOT automatically be TIER_2.**
"Cancel ORD-48210" is TIER_1: one intent, one order, no conditions.

### TIER_2 — complex
Needs multi-step reasoning, disambiguation, or synthesis:
- multiple simultaneous intents
- multiple order references (2+ distinct ORD-*)
- multi-item comparison ("which of these two is cheaper / better")
- conditional requests ("if X then Y else Z")
- corrections / revisions of a previous request
- conflicting constraints
- ambiguous cross-turn references ("that one", "the other order")
- policy + account synthesis (combine a policy rule with account/order facts)
- compound follow-up (follow-up that adds a new constraint)
- complex reference resolution
- comparison across multiple entities
- nested conditionals

## Hard constraints (mechanical — violations fail automated checks)

1. **240 examples total.** Split BEFORE any evaluation: `VALIDATION` 120, `FINAL_TEST` 120.
2. Each split: exactly **30 ENGLISH, 30 URDU, 30 ROMAN_URDU, 30 CODE_SWITCH**.
3. Within each (split, language): exactly **15 expected TIER_1, 15 expected TIER_2**.
4. Stable IDs: `{SPLIT}-{LANG}-{TIER}-{SEQ}` e.g. `VAL-EN-T1-001`, `TST-CS-T2-015`.
   - SPLIT ∈ {VAL, TST}; LANG ∈ {EN, UR, RU, CS}; TIER ∈ {T1, T2}; SEQ zero-padded 001–015.
5. Every example: `id, split, language, expectedTier, text, complexityTags, rationale`.
   - `split` ∈ {VALIDATION, FINAL_TEST}; `language` ∈ {ENGLISH, URDU, ROMAN_URDU, CODE_SWITCH};
     `expectedTier` ∈ {TIER_1, TIER_2}.
6. **No duplicate IDs. No duplicate normalized text** (normalize = trim, lowercase,
   collapse whitespace) anywhere in the 240.
7. **No overlap with the 96 routing prototypes** (`src/main/resources/routing/examples-v1.json`)
   — not exact text, not obvious paraphrase copies.
8. **Text length < 260 characters** for every example (the structural long-message rule
   fires at 300; keep the eval about semantics, not length).
9. **TIER_1 examples: at most ONE `ORD-` reference** and no conditionals/corrections/
   comparisons — the label must be achievable by a perfect router (the structural
   multi-order rule forces TIER_2 on 2+ refs).
10. **TIER_2 multi-order examples: at most 5 per (split, language)** — the majority of
    TIER_2 must exercise the *semantic* path, not the structural short-circuit.
11. Blank text or blank rationale is a failure.
12. `complexityTags`: 1–3 tags from the controlled vocabulary below. `rationale`: 1–2
    sentences citing the rubric (why this tier).

## Controlled tag vocabulary

TIER_1-leaning: greeting, order-status, faq, policy-question, simple-reference,
cancellation, return-request, claim-request, single-lookup, delivery-question,
payment-question

TIER_2-leaning: multi-intent, multi-order, comparison, conditional, correction,
conflicting-constraints, ambiguous-reference, synthesis, compound-followup,
reference-resolution, nested-conditional

## Language guidance

- **ENGLISH**: natural e-commerce support English. Pakistani marketplace context
  (Daraz-style) is fine: COD, vouchers, JazzCash/Easypaisa refunds.
- **URDU**: actual Urdu script. Natural phrasing, e.g. "میرا آرڈر کہاں ہے؟",
  "رقم کی واپسی", "پتہ تبدیل کریں".
- **ROMAN_URDU**: realistic Pakistani Roman Urdu, NOT word-for-word translated English:
  "mera order kahan hai", "paisa wapas kab aayega", "address change kar dein".
- **CODE_SWITCH**: genuinely mixed, as Pakistanis actually type:
  "Mera ORD-48210 return karna hai, what's the process?",
  "Blue shirt order ki thi, red deliver hui hai".

**Language itself must never be a complexity signal.** Each language has the same
15/15 tier balance; difficulty comes from the request structure, not the language.

## Content guidance

- Realistic support situations: order tracking, delivery slots/address changes, COD,
  refunds (to bank / JazzCash / Easypaisa), returns, exchanges, damaged items,
  wrong items, vouchers, order cancellation, claim for missing items.
- Order refs look like `ORD-48210` (digits only after the dash). Use varied numbers.
- Vary the scenarios — no template repetition, no near-paraphrase pairs.
- No PII, no real names, no phone numbers, no secrets.

## Exemplars (follow these exactly in shape)

### ENGLISH / TIER_1
1. text: "Hi, where is my order ORD-48210?"
   tags: [greeting, order-status]
   rationale: "Single direct order-status lookup; one intent, no condition or comparison."
2. text: "What is the return window for shoes?"
   tags: [faq, policy-question]
   rationale: "Straightforward policy FAQ with a single intent."

### ENGLISH / TIER_2
1. text: "Cancel ORD-48210 and change the delivery address for ORD-48211 to my office."
   tags: [multi-intent, multi-order, cancellation]
   rationale: "Two distinct mutations on two different orders in one turn."
2. text: "I ordered the blue shirt but received the red one. If blue is in stock send a replacement, otherwise refund me."
   tags: [correction, conditional]
   rationale: "Correction of a wrong item plus a conditional branch on stock availability."

### URDU / TIER_1
1. text: "میرا آرڈر ORD-48210 کہاں ہے؟"
   tags: [order-status, single-lookup]
   rationale: "ایک ہی آرڈر کے بارے میں براہِ راست استفسار؛ کوئی شرط یا موازنہ نہیں۔"
2. text: "جوتوں کی واپسی کی مدت کتنی ہے؟"
   tags: [faq, policy-question]
   rationale: "ایک سادہ پالیسی سوال؛ واحد ارادہ۔"

### URDU / TIER_2
1. text: "ORD-48210 منسوخ کر دیں اور ORD-48211 کا پتہ میرے دفتر کا کر دیں۔"
   tags: [multi-intent, multi-order]
   rationale: "ایک ہی پیغام میں دو مختلف آرڈرز پر دو الگ درخواستیں۔"
2. text: "میں نے نیلی قمیض منگوائی تھی مگر سرخ آئی ہے؛ اگر نیلی دستیاب ہو تو تبدیل کر دیں ورنہ رقم واپس کر دیں۔"
   tags: [correction, conditional]
   rationale: "غلط آئٹم کی تصحیح اور اسٹاک پر منحصر مشروط ہدایت۔"

### ROMAN_URDU / TIER_1
1. text: "mera order ORD-48210 kahan hai?"
   tags: [order-status, single-lookup]
   rationale: "Seedha order status ka sawal; ek intent, koi condition nahin."
2. text: "shoes wapas karne ki muddat kitni hai?"
   tags: [faq, policy-question]
   rationale: "Seedhi policy FAQ; wahid maqsad."

### ROMAN_URDU / TIER_2
1. text: "ORD-48210 cancel kar dein aur ORD-48211 ka address mere office ka kar dein."
   tags: [multi-intent, multi-order, cancellation]
   rationale: "Ek turn mein do mukhtalif orders par do alag requests."
2. text: "maine neeli shirt mangwai thi lekin surkh aa gayi hai; agar neeli available ho to replace kar dein warna refund kar dein."
   tags: [correction, conditional]
   rationale: "Ghalat item ki correction aur stock par conditional hidayat."

### CODE_SWITCH / TIER_1
1. text: "Hi, mera order ORD-48210 kab deliver hoga?"
   tags: [greeting, order-status]
   rationale: "Single delivery-status intent; language mixing does not add complexity."
2. text: "Mera ORD-48210 kahan hai, can you track it?"
   tags: [order-status, single-lookup]
   rationale: "Direct single-order lookup phrased with code-switching."

### CODE_SWITCH / TIER_2
1. text: "ORD-48210 cancel kar dein, aur ORD-48211 office address par bhej dein."
   tags: [multi-intent, multi-order]
   rationale: "Do orders, do alag intents — ek hi turn mein."
2. text: "Blue shirt order ki thi, red deliver hui hai — agar blue stock mein hai to replacement bhej dein warna refund kar dein."
   tags: [correction, conditional]
   rationale: "Wrong-item correction with a stock-conditional branch."
