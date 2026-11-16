"""E4 dataset validator: the checks the broken 57-question file failed.

Validates a RAG question dataset against what the harness actually needs:
  * Valid JSON (no comments — the harness uses a plain ObjectMapper).
  * Unique ids; type in {direct, paraphrase, exception, unsupported}.
  * language in {EN, UR, RU}.
  * category is a real policy-document stem (or null for unsupported).
  * Unsupported questions have no category and no expected answer;
    every other question has both.
Warnings (do not fail the run): Urdu/Roman Urdu questions still marked
needs_review, i.e. not yet reviewed for language correctness by the user.

Usage:
  python scripts/e4_validate_dataset.py --dataset datasets/rag_questions_v2.json
      [--out results/e4_dataset_check.json]
Exit code 0 = valid (warnings allowed), 1 = errors found.
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

DOC_STEMS = {
    "returns-policy", "cancellation-policy", "refund-timing",
    "claims-damaged-wrong-missing", "payment-issues",
    "shipping-and-delivery", "human-escalation",
}
TYPES = {"direct", "paraphrase", "exception", "unsupported"}
LANGUAGES = {"EN", "UR", "RU"}


def main():
    parser = argparse.ArgumentParser(description="Validate a RAG question dataset")
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    errors, warnings = [], []
    try:
        data = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"E4 dataset: INVALID JSON: {exc}")
        print("Hint: JSON has no comments. Remove '#' comment lines first.")
        return 1
    questions = data.get("questions") if isinstance(data, dict) else None
    if not isinstance(questions, list) or not questions:
        print("E4 dataset: no 'questions' list found.")
        return 1

    seen_ids = set()
    for question in questions:
        qid = question.get("id", "?")
        if qid in seen_ids:
            errors.append(f"{qid}: duplicate id")
        seen_ids.add(qid)
        if question.get("type") not in TYPES:
            errors.append(f"{qid}: type must be one of {sorted(TYPES)}, "
                          f"got {question.get('type')!r} (the harness reads 'type')")
        if question.get("language") not in LANGUAGES:
            errors.append(f"{qid}: language must be one of {sorted(LANGUAGES)}")
        if not (question.get("question") or "").strip():
            errors.append(f"{qid}: blank question")
        category = question.get("category")
        if question.get("type") == "unsupported":
            if category is not None:
                errors.append(f"{qid}: unsupported question must have category null")
            if (question.get("expected_answer") or "").strip():
                errors.append(f"{qid}: unsupported question must have a blank expected_answer")
        else:
            if category not in DOC_STEMS:
                errors.append(f"{qid}: category must be a policy document stem "
                              f"{sorted(DOC_STEMS)}, got {category!r}")
            if not (question.get("expected_answer") or "").strip():
                errors.append(f"{qid}: non-unsupported question needs a one-sentence expected_answer")
        if question.get("needs_review"):
            warnings.append(f"{qid}: needs_review=true (Urdu/Roman Urdu draft not yet reviewed)")

    counts = {
        "questions": len(questions),
        "by_type": dict(Counter(q.get("type") for q in questions)),
        "by_language": dict(Counter(q.get("language") for q in questions)),
        "by_category": dict(Counter(q.get("category") for q in questions)),
        "needs_review": sum(1 for q in questions if q.get("needs_review")),
    }
    result = {"dataset": args.dataset, "valid": not errors, "errors": errors,
              "warnings": warnings, "counts": counts}
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"E4 dataset: {counts['questions']} questions, valid={not errors}, "
          f"errors={len(errors)}, warnings={len(warnings)}")
    for error in errors:
        print(f"  ERROR: {error}")
    for warning in warnings[:10]:
        print(f"  WARNING: {warning}")
    if len(warnings) > 10:
        print(f"  ... and {len(warnings) - 10} more warnings")
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
