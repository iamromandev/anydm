import { expect, test } from "bun:test";
import { cx } from "./cx";

test("cx joins the parts that are on and drops the rest", () => {
    expect(cx("a", false, "b", null, undefined, "")).toBe("a b");
    expect(cx(false)).toBe("");
});
