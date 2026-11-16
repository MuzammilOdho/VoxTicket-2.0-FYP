"""E4 metrics: Recall@1/@3, MRR, nDCG@3 from harness retrieval output.

Reads retrieval-raw.json (written by RagEvaluationHarnessManualTest) plus
the question dataset, and scores every question against its expected
document category (the policy filename stem). Binary relevance, one
relevant document per question:
  * Recall@1 = 1 if the expected category ranks first, else 0.
  * Recall@3 = 1 if it ranks in the top 3, else 0.
  * Reciprocal rank = 1/rank (0 when absent); MRR is its mean.
  * nDCG@3 = 1/log2(rank+1) when rank <= 3, else 0.
Unsupported questions are scored separately: correct only when retrieval
returns no hits (the system must not answer from a policy that does not
cover the question). Answer quality is NEVER scored here — it is human
scored, never an LLM judge.

If the retrieval file is missing or empty, the output says so plainly
instead of reporting zeros as results: a zero-hit run usually means the
vector store was never populated.

Usage:
  python scripts/e4_metrics.py --retrieval results/retrieval-raw.json \
      --dataset datasets/rag_questions_v2.json --out-json results/e4.json \
      [--out-md results/e4_table.md]
"""

import argparse
import json
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def score_question(expected, hits):
    """Return (rank or None, recall1, recall3, rr, ndcg3)."""
    for index, hit in enumerate(hits, start=1):
        if hit.get("category") == expected:
            rank = index
            return (rank, 1.0 if rank == 1 else 0.0, 1.0 if rank <= 3 else 0.0,
                    1.0 / rank, 1.0 / math.log2(rank + 1) if rank <= 3 else 0.0)
    return (None, 0.0, 0.0, 0.0, 0.0)


def aggregate(rows):
    if not rows:
        return None
    return {
        "n": len(rows),
        "recall_at_1": round(statistics.mean(r["recall_at_1"] for r in rows), 4),
        "recall_at_3": round(statistics.mean(r["recall_at_3"] for r in rows), 4),
        "mrr": round(statistics.mean(r["reciprocal_rank"] for r in rows), 4),
        "ndcg_at_3": round(statistics.mean(r["ndcg_at_3"] for r in rows), 4),
    }


def main():
    parser = argparse.ArgumentParser(description="Compute E4 retrieval metrics")
    parser.add_argument("--retrieval", required=True)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--out-json", required=True)
    parser.add_argument("--out-md", default="")
    args = parser.parse_args()

    retrieval_path = Path(args.retrieval)
    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    expected = {q["id"]: q for q in dataset["questions"]}

    if not retrieval_path.is_file():
        result = {"area": "E4", "status": "not_run",
                  "reason": f"retrieval file not found: {retrieval_path}. Run the "
                  "RagEvaluationHarnessManualTest first (see README)."}
    else:
        raw = json.loads(retrieval_path.read_text(encoding="utf-8"))
        rows_in = raw.get("rows", [])
        if not rows_in:
            result = {"area": "E4", "status": "not_run",
                      "reason": "Retrieval file contains no rows. The vector store "
                      "may be empty — populate it with policy embeddings first."}
        else:
            rows, unsupported_correct, unsupported_total = [], 0, 0
            for row in rows_in:
                question = expected.get(row["id"])
                if question is None:
                    continue
                hits = row.get("hits", [])
                if question["type"] == "unsupported":
                    unsupported_total += 1
                    correct = 1 if not hits else 0
                    unsupported_correct += correct
                    rows.append({"id": row["id"], "type": "unsupported",
                                 "language": question["language"],
                                 "expected_category": None, "hits": len(hits),
                                 "unsupported_handled_correctly": correct})
                    continue
                rank, r1, r3, rr, ndcg = score_question(question["category"], hits)
                rows.append({"id": row["id"], "type": question["type"],
                             "language": question["language"],
                             "expected_category": question["category"],
                             "rank": rank, "hits": len(hits),
                             "recall_at_1": r1, "recall_at_3": r3,
                             "reciprocal_rank": rr, "ndcg_at_3": ndcg})
            scored = [r for r in rows if r["type"] != "unsupported"]
            by_type, by_language = {}, {}
            for key, group in (("type", defaultdict(list)), ("language", defaultdict(list))):
                buckets = defaultdict(list)
                for row in scored:
                    buckets[row[key]].append(row)
                target = by_type if key == "type" else by_language
                for name, bucket in sorted(buckets.items()):
                    target[name] = aggregate(bucket)
            zero_hit = sum(1 for r in scored if r["hits"] == 0)
            result = {
                "area": "E4",
                "status": "measured",
                "method": "Binary relevance against the expected policy document "
                "category. Answer quality is human scored and is NOT in this "
                "file. Unsupported questions are scored separately (correct "
                "only when retrieval returns no hits).",
                "dataset_sha256": raw.get("datasetSha256"),
                "dataset_path": raw.get("datasetPath"),
                "rag_top_k": raw.get("ragTopK"),
                "rag_similarity_threshold": raw.get("ragSimilarityThreshold"),
                "embedding_provider_note": "NOT recorded by the harness output. Record the "
                "actual provider/model/dimensions in the run header before "
                "quoting these numbers (yaml default is Ollama bge-m3; "
                "Voyage only when VOXTICKET_EMBEDDING_PROVIDER=voyage).",
                "overall": aggregate(scored),
                "by_type": by_type,
                "by_language": by_language,
                "unsupported": {
                    "n": unsupported_total,
                    "handled_correctly": unsupported_correct,
                    "rate": round(unsupported_correct / unsupported_total, 4)
                    if unsupported_total else None,
                },
                "zero_hit_questions": zero_hit,
                "zero_hit_warning": ("Most questions returned no hits. The vector "
                "store is probably unpopulated; treat these numbers as an "
                "infrastructure result, not a retrieval result.")
                if scored and zero_hit > len(scored) / 2 else None,
                "rows": rows,
            }
    out_json = Path(args.out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    if args.out_md and result["status"] == "measured":
        overall = result["overall"] or {}
        lines = ["# E4 — RAG retrieval metrics (computed, not typed)", "",
                 f"Dataset: {result['dataset_sha256']} (top-k {result['rag_top_k']}, "
                 f"threshold {result['rag_similarity_threshold']})", "",
                 "| Metric | Value |", "|---|---|"]
        for key in ("recall_at_1", "recall_at_3", "mrr", "ndcg_at_3"):
            lines.append(f"| {key} | {overall.get(key)} |")
        lines += ["", f"Unsupported handled correctly: {result['unsupported']['handled_correctly']}"
                       f"/{result['unsupported']['n']}"]
        Path(args.out_md).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"E4: status={result['status']} -> {out_json}")
    if result["status"] == "measured":
        print(f"  overall: {result['overall']} unsupported: {result['unsupported']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
