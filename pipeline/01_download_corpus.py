#!/usr/bin/env python3
"""Download CMU Movie Summary Corpus to data/raw/."""

import pathlib
import sys
import tarfile
import urllib.request

CORPUS_URL = "https://www.cs.cmu.edu/~ark/personas/data/MovieSummaries.tar.gz"
RAW_DIR = pathlib.Path("data/raw")
METADATA_PATH = RAW_DIR / "movie.metadata.tsv"
SUMMARIES_PATH = RAW_DIR / "plot_summaries.txt"

_EXTRACT_FILES = {"movie.metadata.tsv", "plot_summaries.txt"}


def is_already_downloaded() -> bool:
    return METADATA_PATH.exists() and SUMMARIES_PATH.exists()


def download_and_extract(url: str, dest_dir: pathlib.Path) -> None:
    from tqdm import tqdm

    dest_dir.mkdir(parents=True, exist_ok=True)
    archive = dest_dir / "MovieSummaries.tar.gz"

    with urllib.request.urlopen(url) as response:
        total = int(response.headers.get("Content-Length", 0)) or None
        with tqdm(total=total, unit="B", unit_scale=True, desc="Downloading") as bar:
            with open(archive, "wb") as f:
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
                    bar.update(len(chunk))

    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            fname = pathlib.Path(member.name).name
            if fname in _EXTRACT_FILES:
                member.name = fname
                tar.extract(member, path=dest_dir)

    archive.unlink()


def main():
    if is_already_downloaded():
        print("Corpus already downloaded, skipping.", flush=True)
        sys.exit(0)

    print(f"Downloading corpus from {CORPUS_URL} ...", flush=True)
    download_and_extract(CORPUS_URL, RAW_DIR)
    print(f"Done. Files written to {RAW_DIR}.", flush=True)


if __name__ == "__main__":
    main()
