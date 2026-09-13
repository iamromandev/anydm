import { describe, expect, it } from "bun:test";

import { unwrap } from "./envelope";

describe("unwrap", () => {
    it("returns data from a FastAPI success envelope", () => {
        expect(
            unwrap<{ id: string }>({
                status: "success",
                code: 200,
                data: { id: "a" },
            }),
        ).toEqual({
            id: "a",
        });
    });

    it("returns data from a Bun success envelope", () => {
        expect(
            unwrap<{ id: string }>({ success: true, data: { id: "a" } }),
        ).toEqual({ id: "a" });
    });

    it("throws the message from a FastAPI error envelope", () => {
        expect(() =>
            unwrap({ status: "error", code: 404, message: "Task not found" }),
        ).toThrow("Task not found");
    });

    it("throws the error from a Bun error envelope", () => {
        expect(() => unwrap({ success: false, error: "boom" })).toThrow("boom");
    });

    it("throws a generic message when neither field is present", () => {
        expect(() => unwrap({ status: "error", code: 500 })).toThrow(
            "Request failed",
        );
    });

    it("throws on a shape it does not recognise", () => {
        expect(() => unwrap(null)).toThrow();
    });
});
