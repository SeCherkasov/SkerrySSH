#!/usr/bin/env python3
"""Run retained rule regressions and v2 contracts in disposable repositories. No Gradle."""
import argparse
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-k", default="")
    args = parser.parse_args()
    loader = unittest.TestLoader()
    if args.k:
        loader.testNamePatterns = ["*" + args.k + "*"]
    suite = loader.discover(str(Path(__file__).parent / "tests"))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    sys.exit(0 if result.wasSuccessful() and result.testsRun else 1)
