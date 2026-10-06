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
export type ApiErrorDetail = {
    subject?: string;
    description?: string;
    fields?: string[];
};

export class ApiError extends Error {
    readonly code?: number | string;
    /** The service's error type, such as `unsupported_url`, when it sent one. */
    readonly type?: string;
    /** Each cause the service listed, such as every indexer a search failed on. */
    readonly details?: ApiErrorDetail[];

    constructor(
        message: string,
        code?: number | string,
        type?: string,
        details?: ApiErrorDetail[],
    ) {
        super(message);
        this.name = "ApiError";
        this.code = code;
        this.type = type;
        this.details = details;
    }
}

function readDetails(raw: unknown): ApiErrorDetail[] | undefined {
    if (!Array.isArray(raw)) return undefined;
    return raw
        .filter(
            (d): d is Record<string, unknown> =>
                d !== null && typeof d === "object",
        )
        .map((d) => ({
            subject: typeof d.subject === "string" ? d.subject : undefined,
            description:
                typeof d.description === "string" ? d.description : undefined,
            fields: Array.isArray(d.fields)
                ? d.fields.filter((f): f is string => typeof f === "string")
                : undefined,
        }));
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
            typeof body.type === "string" ? body.type : undefined,
            readDetails(body.details),
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
