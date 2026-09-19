/**
 * Two services, two envelopes.
 *
 * FastAPI answers `{ status, code, data, message }`; the Bun API answers
 * `{ success, data, error }`. Both shapes are unwrapped here so no component
 * has to know which service it is talking to — and so deleting the Bun half
 * later means deleting one branch.
 */
/**
 * A request the API answered, and refused.
 *
 * Carries the service's own code so a caller can tell a rejected action from
 * an unreachable service without matching on message text.
 */
export class ApiError extends Error {
    readonly code?: number | string;

    constructor(message: string, code?: number | string) {
        super(message);
        this.name = "ApiError";
        this.code = code;
    }
}

export function unwrap<T>(payload: unknown): T {
    if (payload === null || typeof payload !== "object") {
        throw new ApiError("Request failed");
    }

    const body = payload as Record<string, unknown>;

    if (typeof body.status === "string") {
        if (body.status === "success") {
            return body.data as T;
        }
        throw new ApiError(
            (body.message as string) || "Request failed",
            body.code as number | string | undefined,
        );
    }

    if (typeof body.success === "boolean") {
        if (body.success) {
            return body.data as T;
        }
        throw new ApiError((body.error as string) || "Request failed");
    }

    throw new ApiError("Request failed");
}
