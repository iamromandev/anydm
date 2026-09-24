import { expect, it } from "bun:test";

// Throwaway, for #64: proves the ui job now fails on a failing test. Reverted
// in the next commit.
it("fails on purpose", () => {
    expect(1).toBe(2);
});
