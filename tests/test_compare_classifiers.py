"""Synthetic contract fixtures only; these are not recognition-accuracy data."""
import copy
import unittest
from scripts.compare_classifiers import compare

class ComparisonContractTest(unittest.TestCase):
    def fixtures(self):
        labels = [{"sha256": format(i, "064x"), "category": "cat_food"} for i in range(36)]
        def report(engine):
            return {"mediaItems": [{"sha256Checksum": r["sha256"], "detectedCategoryId": "cat_food",
                                   "inferenceEngine": engine, "needsManualReview": True,
                                   "primarySubjectNote": "Gemini: A meal. No calibrated Gemini probability is available.",
                                   "isManuallyCorrected": False, "isVideo": False} for r in labels]}
        return labels, report("ML_KIT_IMAGE_LABELING"), report("GEMINI")

    def test_counts_known_synthetic_contract_values(self):
        labels, offline, cloud = self.fixtures()
        cloud["mediaItems"][0]["detectedCategoryId"] = "cat_people"
        result = compare(labels, offline, cloud)
        self.assertEqual(36, result["offline_mlkit"]["correct"])
        self.assertEqual(35, result["gemini"]["correct"])
        self.assertEqual(1, result["disagreements"])
        self.assertEqual(36, result["gemini"]["requires_review"])

    def test_refuses_missing_or_duplicate_media(self):
        for duplicate in (False, True):
            labels, offline, cloud = self.fixtures()
            if duplicate:
                cloud["mediaItems"][-1] = copy.deepcopy(cloud["mediaItems"][0])
            else:
                cloud["mediaItems"].pop()
            with self.assertRaises(ValueError):
                compare(labels, offline, cloud)

    def test_refuses_different_originals(self):
        labels, offline, cloud = self.fixtures()
        cloud["mediaItems"][0]["sha256Checksum"] = "f" * 64
        with self.assertRaises(ValueError):
            compare(labels, offline, cloud)

    def test_refuses_fake_or_manually_corrected_predictions(self):
        labels, offline, cloud = self.fixtures()
        offline["mediaItems"][0]["inferenceEngine"] = "ONNX_DIAGNOSTIC"
        with self.assertRaises(ValueError):
            compare(labels, offline, cloud)
        labels, offline, cloud = self.fixtures()
        cloud["mediaItems"][0]["isManuallyCorrected"] = True
        with self.assertRaises(ValueError):
            compare(labels, offline, cloud)

    def test_api_failure_never_counts_as_a_correct_other_prediction(self):
        labels, offline, cloud = self.fixtures()
        labels[0]["category"] = "cat_other"
        cloud["mediaItems"][0].update(detectedCategoryId="cat_other",
                                      primarySubjectNote="Gemini: Rate or quota limit reached.")
        with self.assertRaises(ValueError):
            compare(labels, offline, cloud)

    def test_abstention_is_reported_and_not_counted_as_correct_other(self):
        labels, offline, cloud = self.fixtures()
        labels[0]["category"] = "cat_other"
        cloud["mediaItems"][0].update(detectedCategoryId="cat_other",
                                      primarySubjectNote="Gemini selected unknown/ambiguous content.")
        result = compare(labels, offline, cloud)
        self.assertEqual(35, result["gemini"]["correct"])
        self.assertEqual(1, result["gemini"]["abstentions"])
        self.assertEqual(35, result["compared_predictions"])
