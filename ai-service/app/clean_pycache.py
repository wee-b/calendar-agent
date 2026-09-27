"""Remove Python bytecode caches from project source and tests.

Run explicitly with: python -m app.clean_pycache
"""

from pathlib import Path
from shutil import rmtree


def clean_pycache() -> int:
    project_dir = Path(__file__).resolve().parents[1]
    cache_dirs = [path for source in (project_dir / "app", project_dir / "tests")
                  if source.is_dir() for path in source.rglob("__pycache__") if path.is_dir()]
    for path in cache_dirs:
        rmtree(path)
    return len(cache_dirs)


if __name__ == "__main__":
    print(f"Removed {clean_pycache()} cache directories")
