import pytest
from src.lib.pattern import MAX_ITEMS, PatternError, expand


def test_a_pattern_with_no_range_is_itself() -> None:
    assert expand("https://x.test/a.png") == ["https://x.test/a.png"]


def test_a_leading_zero_pads_to_the_width_of_the_left_number() -> None:
    links = expand("img[001-120].png")
    assert len(links) == 120
    assert links[0] == "img001.png" and links[8] == "img009.png" and links[9] == "img010.png" and links[-1] == "img120.png"


def test_two_digits_of_padding_follow_the_left_number() -> None:
    assert expand("p[08-11]") == ["p08", "p09", "p10", "p11"]
    assert expand("p[00-02]") == ["p00", "p01", "p02"]


def test_no_leading_zero_means_no_padding_and_a_width_that_grows() -> None:
    assert expand("p[1-3]") == ["p1", "p2", "p3"]
    assert expand("p[8-12]") == ["p8", "p9", "p10", "p11", "p12"]


def test_a_single_zero_is_not_padding() -> None:
    assert expand("p[0-2]") == ["p0", "p1", "p2"]


def test_letters_expand_in_either_case() -> None:
    assert expand("[a-d].txt") == ["a.txt", "b.txt", "c.txt", "d.txt"]
    assert expand("[X-Z]") == ["X", "Y", "Z"]
    assert expand("[c-c]") == ["c"]


def test_several_ranges_multiply_with_the_last_varying_fastest() -> None:
    assert expand("[a-b]/[1-2]") == ["a/1", "a/2", "b/1", "b/2"]


def test_a_range_may_open_close_or_fill_the_pattern() -> None:
    assert expand("[1-2]") == ["1", "2"]
    assert expand("a[1-2]b[x-y]c") == ["a1bxc", "a1byc", "a2bxc", "a2byc"]


def test_brackets_that_are_not_a_range_stay_as_written() -> None:
    assert expand("https://x.test/[abc]/f[1-2].bin") == ["https://x.test/[abc]/f1.bin", "https://x.test/[abc]/f2.bin"]
    assert expand("http://[::1]/f") == ["http://[::1]/f"]
    # Mixed case, or a letter to a number, is not a range either.
    assert expand("[a-Z]") == ["[a-Z]"]
    assert expand("[a-9]") == ["[a-9]"]
    assert expand("[1-]") == ["[1-]"]


def test_a_range_that_runs_backwards_is_refused() -> None:
    with pytest.raises(PatternError, match="backwards"):
        expand("p[10-01]")
    with pytest.raises(PatternError, match="backwards"):
        expand("p[z-a]")


def test_the_cap_allows_exactly_the_limit_and_refuses_one_more() -> None:
    assert len(expand("f[0001-1000]")) == MAX_ITEMS
    with pytest.raises(PatternError, match="1,001"):
        expand("f[0001-1001]")


def test_the_cap_counts_the_product_of_ranges() -> None:
    assert len(expand("[a-z]/[1-38]")) == 26 * 38
    with pytest.raises(PatternError, match="the most is 1,000"):
        expand("[a-z]/[1-39]/[1-2]")


def test_a_huge_range_is_refused_before_anything_is_built() -> None:
    with pytest.raises(PatternError, match="the most is 1,000"):
        expand("f[1-99999999999999999999]")


def test_an_empty_pattern_is_refused() -> None:
    with pytest.raises(PatternError):
        expand("   ")
