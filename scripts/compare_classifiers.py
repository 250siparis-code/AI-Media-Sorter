#!/usr/bin/env python3
"""Compare app exports on exactly 36 human-labeled original hashes. No API calls."""
import argparse
import collections
import json
import pathlib
import re

def compare(labels, offline, gemini):
    """labels: [{"sha256": 64 hex chars, "category": configured ID}], 36 unique files."""
    def index(rows, field):
        result = {}
        for row in rows:
            key = row.get(field)
            if not isinstance(key, str) or not re.fullmatch(r"[0-9a-f]{64}", key):
                raise ValueError("Missing or invalid original SHA-256")
            if key in result:
                raise ValueError("Duplicate original media hash")
            result[key] = row
        if len(result) != 36:
            raise ValueError("Exactly 36 distinct labeled images are required")
        return result
    truth = index(labels, "sha256")
    local = index(offline["mediaItems"], "sha256Checksum")
    cloud = index(gemini["mediaItems"], "sha256Checksum")
    if truth.keys() != local.keys() or truth.keys() != cloud.keys():
        raise ValueError("Exports must contain the same 36 original media hashes")
    abstained = set()
    for key in truth:
        if not isinstance(truth[key].get("category"), str) or not truth[key]["category"]:
            raise ValueError("A human category ID is required for every image")
        for row in (local[key], cloud[key]):
            if row.get("isVideo") or row.get("isManuallyCorrected"):
                raise ValueError("Use uncorrected photo predictions for this evaluation")
        if local[key].get("inferenceEngine") != "ML_KIT_IMAGE_LABELING":
            raise ValueError("Offline export must contain real ML Kit predictions")
        if cloud[key].get("inferenceEngine") != "GEMINI":
            raise ValueError("Cloud export must contain Gemini-only predictions")
        note = cloud[key].get("primarySubjectNote", "")
        if note == "Gemini selected unknown/ambiguous content.":
            abstained.add(key)
        elif "No calibrated Gemini probability is available." not in note:
            raise ValueError("Cloud report contains an API/input failure rather than a validated prediction")
    results = {}
    for name, predictions in (("offline_mlkit", local), ("gemini", cloud)):
        def matched(k):
            return not (name == "gemini" and k in abstained) and predictions[k]["detectedCategoryId"] == truth[k]["category"]
        correct = sum(matched(k) for k in truth)
        per_category = collections.defaultdict(lambda: {"total": 0, "correct": 0})
        for k in truth:
            record = per_category[truth[k]["category"]]
            record["total"] += 1
            record["correct"] += matched(k)
        results[name] = {
            "correct": correct, "total": 36, "observed_accuracy": correct / 36,
            "requires_review": sum(bool(predictions[k].get("needsManualReview")) for k in truth),
            "abstentions": len(abstained) if name == "gemini" else 0,
            "per_category": dict(per_category)
        }
    results["disagreements"] = sum(local[k]["detectedCategoryId"] != cloud[k]["detectedCategoryId"] for k in truth if k not in abstained)
    results["compared_predictions"] = 36 - len(abstained)
    results["limitations"] = ("Accuracy is the measured exact category match on this labeled collection only, "
                             "not model confidence, calibrated probability or a population estimate. "
                             "Review flags include policy requirements and must not be treated as errors.")
    return results

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("labels", type=pathlib.Path)
    parser.add_argument("offline_export", type=pathlib.Path)
    parser.add_argument("gemini_export", type=pathlib.Path)
    args = parser.parse_args()
    result = compare(*(json.loads(p.read_text(encoding="utf-8")) for p in
                       (args.labels, args.offline_export, args.gemini_export)))
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
