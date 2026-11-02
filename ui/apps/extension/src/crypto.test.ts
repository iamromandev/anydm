import { describe, expect, test } from "bun:test";
import vectors from "../contract/pairing-vectors.json";
import {
    bytesOfHex,
    hexOf,
    hmacHex,
    hmacMatches,
    randomHex,
    sha256Hex,
    utf8,
} from "./crypto";

describe("hex", () => {
    test("writes two lower-case digits a byte and reads them back", () => {
        const bytes = new Uint8Array([
            0,
            15,
            16,
            255,
        ]);

        expect(hexOf(bytes)).toBe("000f10ff");
        expect([
            ...bytesOfHex("000f10ff")!,
        ]).toEqual([
            ...bytes,
        ]);
    });

    test("reads only exact lower-case hex of whole bytes", () => {
        for (const bad of [
            "0F",
            "abc",
            "zz",
            " 00",
            "0x00",
        ]) {
            expect(bytesOfHex(bad)).toBeNull();
        }
        expect(bytesOfHex("")).toEqual(new Uint8Array());
    });
});

describe("random", () => {
    test("is the requested number of bytes as hex, and not the same twice", () => {
        const a = randomHex(16);
        const b = randomHex(16);

        expect(a).toMatch(/^[0-9a-f]{32}$/);
        expect(a).not.toBe(b);
    });
});

describe("SHA-256 and HMAC-SHA-256", () => {
    test("SHA-256 of the empty text is the well-known value", async () => {
        expect(await sha256Hex(utf8(""))).toBe(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        );
        expect(await sha256Hex(utf8("abc"))).toBe(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        );
    });

    test("HMAC-SHA-256 matches RFC 4231 test case 2", async () => {
        expect(
            await hmacHex(utf8("Jefe"), "what do ya want for nothing?"),
        ).toBe(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
        );
    });

    test("UTF-8, not UTF-16: the shared vector with non-ASCII text", async () => {
        const request = vectors.requests[2]!;

        expect(await sha256Hex(utf8(request.body))).toBe(request.bodySha256);
    });

    test("a proof is checked against the message, and only the right one matches", async () => {
        const key = utf8("k");
        const proof = await hmacHex(key, "message");

        expect(await hmacMatches(key, proof, "message")).toBe(true);
        expect(await hmacMatches(key, proof, "messagf")).toBe(false);
        expect(await hmacMatches(utf8("j"), proof, "message")).toBe(false);
    });

    test("a proof that is not 64 lower-case hex digits never matches", async () => {
        const key = utf8("k");
        const proof = await hmacHex(key, "message");

        for (const bad of [
            "",
            proof.slice(1),
            proof + "00",
            proof.toUpperCase(),
            "zz".repeat(32),
        ]) {
            expect(await hmacMatches(key, bad, "message")).toBe(false);
        }
    });
});
