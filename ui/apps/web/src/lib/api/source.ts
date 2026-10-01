/**
 * The API's source list: built-ins and Torznab indexers as one list.
 *
 * The API leaves null fields out of its answers, so every optional field is
 * read as `null` here: the view never has to tell "missing" from "none".
 */

import { deleteApi, getApi, patchApi, postApi } from "./client";

export type SourceItem = {
    id: string;
    name: string;
    kind: string;
    enabled: boolean;
    baseUrl: string;
    /** Null when no key is stored: masked, or missing for the same reason. */
    apiKeyMasked: string | null;
    /** False for registry rows, which switch and re-address but never delete. */
    deletable: boolean;
    /** Null for Torznab rows, which have no default to reset to. */
    defaultUrl: string | null;
};

export type SourceTest = {
    ok: boolean;
    count: number | null;
    tookMs: number;
    message: string;
};

const textOrNull = (value: unknown): string | null =>
    typeof value === "string" && value !== "" ? value : null;

const numberOrNull = (value: unknown): number | null =>
    typeof value === "number" ? value : null;

export function normalizeSource(raw: any): SourceItem {
    return {
        id: raw?.id ?? "",
        name: raw?.name ?? "",
        kind: raw?.kind ?? "",
        enabled: raw?.enabled === true,
        baseUrl: raw?.base_url ?? "",
        apiKeyMasked: textOrNull(raw?.api_key_masked),
        deletable: raw?.deletable === true,
        defaultUrl: textOrNull(raw?.default_url),
    };
}

export function normalizeTest(raw: any): SourceTest {
    return {
        ok: raw?.ok === true,
        count: numberOrNull(raw?.count),
        tookMs: numberOrNull(raw?.took_ms) ?? 0,
        message: raw?.message ?? "",
    };
}

/** Every source: registry rows first, then created ones oldest-first. */
export async function listSources(): Promise<SourceItem[]> {
    const raw = await getApi<any>("/source");
    return Array.isArray(raw?.sources) ? raw.sources.map(normalizeSource) : [];
}

export type SourceCreateInput = {
    name: string;
    kind: string;
    baseUrl: string;
    apiKey?: string;
    enabled?: boolean;
};

/** Add a source; answers 201's row. */
export async function createSource(
    input: SourceCreateInput,
): Promise<SourceItem> {
    return normalizeSource(
        await postApi<any>("/source", {
            name: input.name,
            kind: input.kind,
            base_url: input.baseUrl,
            ...(input.apiKey !== undefined ? { api_key: input.apiKey } : {}),
            ...(input.enabled !== undefined ? { enabled: input.enabled } : {}),
        }),
    );
}

export type SourcePatchInput = {
    enabled?: boolean;
    baseUrl?: string;
    /** Omitted keeps the stored key, `""` clears it, anything else sets it. */
    apiKey?: string;
};

/** Change a source by id. */
export async function updateSource(
    id: string,
    patch: SourcePatchInput,
): Promise<SourceItem> {
    return normalizeSource(
        await patchApi<any>(`/source/${id}`, {
            ...(patch.enabled !== undefined ? { enabled: patch.enabled } : {}),
            ...(patch.baseUrl !== undefined ? { base_url: patch.baseUrl } : {}),
            ...(patch.apiKey !== undefined ? { api_key: patch.apiKey } : {}),
        }),
    );
}

/** Delete a created source; registry rows are refused. */
export async function deleteSource(id: string): Promise<void> {
    await deleteApi(`/source/${id}`);
}

/** Put a built-in-kind source back to its registry address and state. */
export async function resetSource(id: string): Promise<SourceItem> {
    return normalizeSource(await postApi<any>(`/source/${id}/reset`, {}));
}

export type SourceTestOverrides = {
    baseUrl?: string;
    apiKey?: string;
};

/** Ask a stored source whether it answers, optionally at unsaved values. */
export async function testSource(
    id: string,
    overrides: SourceTestOverrides = {},
): Promise<SourceTest> {
    return normalizeTest(
        await postApi<any>(`/source/${id}/test`, {
            ...(overrides.baseUrl !== undefined
                ? { base_url: overrides.baseUrl }
                : {}),
            ...(overrides.apiKey !== undefined
                ? { api_key: overrides.apiKey }
                : {}),
        }),
    );
}

/** Ask an unsaved source whether it answers, before adding it. */
export async function probeSource(input: {
    kind: string;
    baseUrl: string;
    apiKey?: string;
}): Promise<SourceTest> {
    return normalizeTest(
        await postApi<any>("/source/test", {
            kind: input.kind,
            base_url: input.baseUrl,
            ...(input.apiKey !== undefined ? { api_key: input.apiKey } : {}),
        }),
    );
}
