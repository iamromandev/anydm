from src.engine.torrent.picker import Picker


def test_rarest_piece_is_picked_after_the_random_start() -> None:
    p = Picker(6, have={0, 1, 2, 3}, first_random=4)  # 4 pieces already done: past the random phase
    p.peer_joined({4, 5})
    p.peer_joined({4})
    p.peer_joined({4})
    assert p.pick({4, 5}) == 5  # 5 is held by one peer, 4 by three


def test_random_first_pieces_vary() -> None:
    seen = set()
    for _ in range(60):
        p = Picker(20, have=set(), first_random=4)
        p.peer_joined(set(range(20)))
        seen.add(p.pick(set(range(20))))
    assert len(seen) > 5


def test_pick_skips_owned_and_claimed_pieces() -> None:
    p = Picker(3, have={0}, first_random=0)
    p.peer_joined({0, 1, 2})
    a = p.pick({0, 1, 2})
    b = p.pick({0, 1, 2})
    assert {a, b} == {1, 2}
    assert p.pick({0}) is None  # peer only has what we have


def test_endgame_reuses_claimed_pieces_when_nothing_else_is_left() -> None:
    p = Picker(2, have={0}, first_random=0)
    p.peer_joined({1})
    assert p.pick({1}) == 1
    assert p.pick({1}) == 1  # endgame: second worker duplicates it
    assert p.endgame


def test_release_returns_the_piece_to_the_pool_and_complete_finishes_it() -> None:
    p = Picker(2, have=set(), first_random=0)
    p.peer_joined({0, 1})
    i = p.pick({0, 1})
    assert i is not None
    p.release(i)
    assert not p.endgame
    j = p.pick({i})
    assert j == i
    p.complete(i)
    assert p.has(i)
    assert p.pick({i}) is None
    assert not p.finished
    k = p.pick({0, 1})
    assert k is not None and k != i
    p.complete(k)
    assert p.finished


def test_peer_left_and_have_update_availability() -> None:
    p = Picker(2, have=set(), first_random=0)
    p.peer_joined({0})
    p.peer_got(1)
    assert p.availability == [1, 1]
    p.peer_left({0, 1})
    assert p.availability == [0, 0]


def test_picking_does_not_scan_every_piece_each_time() -> None:
    import time

    n = 100_000
    p = Picker(n, have=set(), first_random=0)
    everything = set(range(n))
    p.peer_joined(everything)
    start = time.perf_counter()
    picked = {p.pick(everything) for _ in range(300)}
    assert len(picked) == 300  # each claim is a different piece
    assert time.perf_counter() - start < 0.5


def test_rarest_order_follows_availability_changes_after_picks_and_releases() -> None:
    p = Picker(4, have=set(), first_random=0)
    p.peer_joined({0, 1, 2, 3})
    p.peer_joined({0, 1})
    p.peer_got(2)  # 0 and 1 are held by two peers, 2 by two, 3 by one
    assert p.pick({0, 1, 2, 3}) == 3
    p.release(3)
    p.peer_got(3)
    p.peer_got(3)  # now 3 is the commonest
    assert p.pick({0, 1, 2, 3}) in {0, 1, 2}
    p.peer_left({0, 1})
    assert p.availability == [1, 1, 2, 3]


def test_a_peer_with_few_pieces_is_served_quickly_in_a_huge_torrent() -> None:
    import time

    p = Picker(500_000, have=set(), first_random=0)
    p.peer_joined(set(range(500_000)))
    start = time.perf_counter()
    for _ in range(200):
        p.pick({7, 8, 9})
        p.pick({400_000})
    assert time.perf_counter() - start < 0.5
