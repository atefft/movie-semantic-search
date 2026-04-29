#!/usr/bin/env python3
import argparse
import os
import time
import requests
from collections import deque


def search_tmdb(session, title: str, year: int | None, api_key: str) -> str | None:
    """
    Search TMDB for a movie and return its poster_path or None.

    Args:
        session: requests.Session for making HTTP requests
        title: Movie title to search for
        year: Optional release year
        api_key: TMDB API key

    Returns:
        The poster_path of the first result, or None if no results or no poster_path
    """
    url = "https://api.themoviedb.org/3/search/movie"
    params = {"query": title, "api_key": api_key}
    if year is not None:
        params["year"] = year

    response = session.get(url, params=params)
    results = response.json().get("results", [])

    if not results:
        return None

    poster_path = results[0].get("poster_path")
    return poster_path if poster_path is not None else None


class RateLimiter:
    """Rate limiter to enforce max requests per second."""

    def __init__(self, max_per_second: int):
        self.max_per_second = max_per_second
        self._timestamps = deque()

    def wait(self):
        """Wait if necessary to enforce the rate limit."""
        now = time.monotonic()
        # Drop timestamps older than 1 second
        while self._timestamps and self._timestamps[0] < now - 1.0:
            self._timestamps.popleft()
        if len(self._timestamps) >= self.max_per_second:
            sleep_time = 1.0 - (now - self._timestamps[0])
            if sleep_time > 0:
                time.sleep(sleep_time)
        self._timestamps.append(time.monotonic())


def main():
    parser = argparse.ArgumentParser(description="Enrich Qdrant points with TMDB poster URLs.")
    parser.add_argument("--qdrant-url", default="http://localhost:6333")
    args = parser.parse_args()

    api_key = os.environ.get("TMDB_API_KEY")
    if not api_key:
        raise ValueError("TMDB_API_KEY environment variable is required")

    from qdrant_client import QdrantClient

    client = QdrantClient(url=args.qdrant_url)
    session = requests.Session()
    limiter = RateLimiter(max_per_second=40)

    # Scroll all points where thumbnail_url is null
    from qdrant_client.models import Filter, FieldCondition, MatchValue

    filter_condition = Filter(
        must=[
            FieldCondition(
                key="thumbnail_url",
                match=MatchValue(value=None)
            )
        ]
    )

    points, _ = client.scroll(
        collection_name="movies",
        limit=100,
        scroll_filter=filter_condition,
    )

    while points:
        for point in points:
            limiter.wait()
            title = point.payload.get("title")
            year = point.payload.get("release_year")

            poster_path = search_tmdb(session, title, year, api_key)
            if poster_path:
                client.set_payload(
                    collection_name="movies",
                    payload={"thumbnail_url": poster_path},
                    points=[point.id]
                )

        # Fetch next batch
        points, _ = client.scroll(
            collection_name="movies",
            limit=100,
            scroll_filter=filter_condition,
        )


if __name__ == "__main__":
    main()
