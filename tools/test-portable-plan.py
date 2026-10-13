"""Check the shared portable policy and actual generated Java source without writing either."""
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,'-B',str(ROOT/'tools/pc-migration/generate-plan.py'),'--check'],cwd=ROOT,check=True)
