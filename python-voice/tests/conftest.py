"""Make `from agent import ...` work for bare `pytest -q`.

pytest inserts the tests/ directory into sys.path, not its parent, so
without this file the tests cannot import agent.py, which lives in
python-voice/ (the parent of tests/). Inserting the parent directory
here fixes every test module, for every invocation style (bare pytest,
python -m pytest, pytest from the repo root), with no environment
variable and no change to any test file.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
