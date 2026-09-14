/**
 * Two services, two envelopes.
 *
 * FastAPI answers `{ status, code, data, message }`; the Bun API answers
 * `{ success, data, error }`. Both shapes are unwrapped here so no component
 * has to know which service it is talking to — and so deleting the Bun half
 * later means deleting one branch.
 */
export function unwrap<T>(payload: unknown): T {
    if (payload === null || typeof payload !== "object") {
        throw new Error("Request failed");
    }

    const body = payload as Record<string, unknown>;

    if (typeof body.status === "string") {
        if (body.status === "success") {
            return body.data as T;
        }
        throw new Error((body.message as string) || "Request failed");
    }

    if (typeof body.success === "boolean") {
        if (body.success) {
            return body.data as T;
        }
        throw new Error((body.error as string) || "Request failed");
    }

    throw new Error("Request failed");
}
