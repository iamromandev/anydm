"""Piece selection: random for the first few pieces so a new download has something to share quickly,
then rarest-first, and endgame duplication once every missing piece already has a worker.

Missing pieces nobody is fetching sit in buckets keyed by how many peers have them, so a pick looks at the
rarest bucket first instead of scanning every piece."""

import itertools
import random
from collections.abc import Collection

RANDOM_SAMPLE = 256  # candidates looked at for a random pick in a huge torrent


class Picker:
    def __init__(self, count: int, have: set[int], first_random: int = 4) -> None:
        self.count = count
        self._have = set(have)
        self._claims: dict[int, int] = {}
        self.availability = [0] * count
        self._first_random = first_random
        self._buckets: dict[int, set[int]] = {0: {i for i in range(count) if i not in self._have}}

    def has(self, index: int) -> bool:
        return index in self._have

    @property
    def have(self) -> set[int]:
        return set(self._have)

    @property
    def finished(self) -> bool:
        return len(self._have) == self.count

    @property
    def endgame(self) -> bool:
        """Every missing piece is already being fetched by someone."""
        missing = self.count - len(self._have)
        return missing > 0 and len(self._claims) >= missing

    # --- availability ---

    def _bump(self, index: int, delta: int) -> None:
        old = self.availability[index]
        new = max(0, old + delta)
        self.availability[index] = new
        bucket = self._buckets.get(old)
        if bucket is not None and index in bucket:
            self._remove(index, old)
            self._buckets.setdefault(new, set()).add(index)

    def _remove(self, index: int, level: int) -> None:
        bucket = self._buckets.get(level)
        if bucket is not None:
            bucket.discard(index)
            if not bucket:
                del self._buckets[level]

    def peer_joined(self, pieces: set[int]) -> None:
        for i in pieces:
            self._bump(i, 1)

    def peer_got(self, index: int) -> None:
        self._bump(index, 1)

    def peer_left(self, pieces: set[int]) -> None:
        for i in pieces:
            self._bump(i, -1)

    # --- picking ---

    def _random_fresh(self, peer_has: set[int]) -> int | None:
        sample = [i for i in itertools.islice(peer_has, RANDOM_SAMPLE * 4) if i not in self._have and i not in self._claims]
        return random.choice(sample[:RANDOM_SAMPLE]) if sample else None

    def _rarest_fresh(self, peer_has: set[int]) -> int | None:
        for level in sorted(self._buckets):
            bucket = self._buckets[level]
            smaller, other = (bucket, peer_has) if len(bucket) <= len(peer_has) else (peer_has, bucket)
            for i in smaller:
                if i in other:
                    return i
        return None

    def pick(self, peer_has: set[int], exclude: Collection[int] = ()) -> int | None:
        """A piece for a peer that has `peer_has`, or None. `exclude` (the pieces that peer is already fetching)
        only matters in endgame, when a piece someone else is fetching may be duplicated."""
        early = len(self._have) < self._first_random
        choice = self._random_fresh(peer_has) if early else self._rarest_fresh(peer_has)
        if choice is None:
            # nothing unclaimed on this peer: help with the least-claimed piece it has
            wanted = [i for i in self._claims if i in peer_has and i not in self._have and i not in exclude]
            if not wanted:
                return None
            fewest = min(self._claims[i] for i in wanted)
            choice = random.choice([i for i in wanted if self._claims[i] == fewest])
        else:
            self._remove(choice, self.availability[choice])
        self._claims[choice] = self._claims.get(choice, 0) + 1
        return choice

    def release(self, index: int) -> None:
        n = self._claims.get(index, 0) - 1
        if n > 0:
            self._claims[index] = n
        else:
            self._claims.pop(index, None)
            if index not in self._have:
                self._buckets.setdefault(self.availability[index], set()).add(index)

    def complete(self, index: int) -> None:
        self._have.add(index)
        self._claims.pop(index, None)
        self._remove(index, self.availability[index])
