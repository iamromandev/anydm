// HMAC-SHA-256 and SHA-256 over Web Crypto (`crypto.subtle`), which every extension page and worker has, and bun too.
// Text is UTF-8; hex is lower-case, two digits per byte. Nothing here logs or keeps anything.

const encoder = new TextEncoder();

/** The bytes of a text, as `fetch` sends a string body. */
export const utf8 = (text: string): Uint8Array<ArrayBuffer> =>
    encoder.encode(text);

export function hexOf(bytes: Uint8Array): string {
    let out = "";
    for (const byte of bytes) out += byte.toString(16).padStart(2, "0");
    return out;
}

/** The bytes of a lower-case hex text with an even number of digits; null for anything else. */
export function bytesOfHex(hex: string): Uint8Array<ArrayBuffer> | null {
    if (!/^(?:[0-9a-f]{2})*$/.test(hex)) return null;
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < bytes.length; i += 1) {
        bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    }
    return bytes;
}

/** [length] random bytes from the browser's random source, as hex. Never derived, never reused. */
export function randomHex(length: number): string {
    return hexOf(crypto.getRandomValues(new Uint8Array(length)));
}

export async function sha256Hex(data: Uint8Array): Promise<string> {
    return hexOf(
        new Uint8Array(
            await crypto.subtle.digest(
                "SHA-256",
                data as Uint8Array<ArrayBuffer>,
            ),
        ),
    );
}

const hmacKey = (key: Uint8Array, usage: "sign" | "verify") =>
    crypto.subtle.importKey(
        "raw",
        key as Uint8Array<ArrayBuffer>,
        { name: "HMAC", hash: "SHA-256" },
        false,
        [
            usage,
        ],
    );

/** HMAC-SHA-256 of a text under [key], as hex. */
export async function hmacHex(
    key: Uint8Array,
    message: string,
): Promise<string> {
    return hexOf(
        new Uint8Array(
            await crypto.subtle.sign(
                "HMAC",
                await hmacKey(key, "sign"),
                utf8(message),
            ),
        ),
    );
}

/** Whether [proof] (hex) is the HMAC of [message] under [key]. The comparison is `subtle.verify`'s, so it is constant time. */
export async function hmacMatches(
    key: Uint8Array,
    proof: string,
    message: string,
): Promise<boolean> {
    const given = bytesOfHex(proof);
    if (given === null || given.length !== 32) return false;
    return crypto.subtle.verify(
        "HMAC",
        await hmacKey(key, "verify"),
        given,
        utf8(message),
    );
}
