import { readdirSync, readFileSync } from "fs";
import { join } from "path";

import { isScreenTimeError, SCREEN_TIME_ERROR_CODES } from "../errors";

describe("isScreenTimeError", () => {
  const withCode = (code: unknown) => Object.assign(new Error("message"), { code });

  it("accepts an Error carrying one of the module's codes", () => {
    for (const code of SCREEN_TIME_ERROR_CODES) {
      expect(isScreenTimeError(withCode(code))).toBe(true);
    }
  });

  it("rejects other errors and non-errors", () => {
    expect(isScreenTimeError(new Error("no code"))).toBe(false);
    expect(isScreenTimeError(withCode("ERR_ARGUMENT_CAST"))).toBe(false);
    expect(isScreenTimeError(withCode(42))).toBe(false);
    expect(isScreenTimeError({ code: "NO_ACTIVE_BLOCK", message: "plain object" })).toBe(false);
    expect(isScreenTimeError("NO_ACTIVE_BLOCK")).toBe(false);
    expect(isScreenTimeError(null)).toBe(false);
    expect(isScreenTimeError(undefined)).toBe(false);
  });
});

describe("ScreenTimeErrorCode", () => {
  it("lists exactly the codes the native module emits", () => {
    const swift = readFileSync(join(__dirname, "../../ios/ScreenTimeError.swift"), "utf8");
    const native = [...swift.matchAll(/case \w+ = "([A-Z_]+)"/g)].map((m) => m[1]);
    expect(native.length).toBeGreaterThan(0);
    expect([...native].sort()).toEqual([...SCREEN_TIME_ERROR_CODES].sort());
  });

  it("is the only vocabulary of the native rejections", () => {
    // Every literal code passed to a rejection or exception in ios/ must be a
    // ScreenTimeErrorCode (the typed helpers take the enum, so this catches
    // raw strings slipping back in).
    const dir = join(__dirname, "../../ios");
    const literals = readdirSync(dir)
      .filter((file) => file.endsWith(".swift") && file !== "ScreenTimeError.swift")
      .flatMap((file) => {
        const source = readFileSync(join(dir, file), "utf8");
        return [...source.matchAll(/reject\(\s*"([^"]+)"/g)].map((m) => `${file}: ${m[1]}`);
      });
    expect(literals).toEqual([]);
  });
});
