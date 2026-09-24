import { describe, expect, it } from "bun:test";

import { ApiError, unwrap } from "./envelope";

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

describe("the error unwrap throws", () => {
    it("is an ApiError carrying the service's own code", () => {
        try {
            unwrap({ status: "error", code: 404, message: "Task not found" });
            throw new Error("unwrap should have thrown");
        } catch (error) {
            expect(error).toBeInstanceOf(ApiError);
            expect((error as ApiError).code).toBe(404);
            expect((error as ApiError).message).toBe("Task not found");
        }
    });

    it("carries the error's type, so a caller can tell one 400 from another", () => {
        try {
            unwrap({
                status: "error",
                code: 400,
                message: "No site supports this link",
                type: "unsupported_url",
            });
            throw new Error("unwrap should have thrown");
        } catch (error) {
            expect((error as ApiError).type).toBe("unsupported_url");
        }
    });

    it("is still an ApiError when the envelope carries no code", () => {
        try {
            unwrap({ success: false, error: "boom" });
            throw new Error("unwrap should have thrown");
        } catch (error) {
            expect(error).toBeInstanceOf(ApiError);
            expect((error as ApiError).code).toBeUndefined();
        }
    });
});
