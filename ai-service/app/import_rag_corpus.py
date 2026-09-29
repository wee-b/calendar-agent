"""Run `python -m app.import_rag_corpus [--dry-run] [--source-dir PATH]`."""

import argparse
import asyncio
from pathlib import Path

from app.service.rag_corpus import DEFAULT_CORPUS_DIR, import_corpus


def main() -> None:
    parser = argparse.ArgumentParser(description="Import planning Markdown corpus into Qdrant")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--source-dir", type=Path, default=DEFAULT_CORPUS_DIR)
    args = parser.parse_args()
    count = asyncio.run(import_corpus(args.source_dir, dry_run=args.dry_run))
    print(f"语料处理完成: {count} chunks" + (" (dry-run)" if args.dry_run else ""))


if __name__ == "__main__":
    main()
