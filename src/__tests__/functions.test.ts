import { readFileSync } from "fs";
import { join } from "path";

const read = (path: string) => readFileSync(join(__dirname, "..", path), "utf8");

describe("named exports", () => {
  it("cover every function of the native module", () => {
    const swift = readFileSync(join(__dirname, "../../ios/ExpoScreenTimeModule.swift"), "utf8");
    const native = [...swift.matchAll(/\b(?:Async)?Function\("(\w+)"\)/g)].map((m) => m[1]);
    expect(native.length).toBeGreaterThan(30);

    const exported = new Set(
      ["functions.ts", "utils.ts", "index.ts"].flatMap((file) =>
        [...read(file).matchAll(/export (?:const|function) (\w+)/g)].map((m) => m[1]),
      ),
    );
    expect(native.filter((name) => !exported.has(name))).toEqual([]);
  });
});

describe("forward", () => {
  const calls: { name: string; self: unknown; args: unknown[] }[] = [];
  let accessed = 0;

  beforeAll(() => {
    const target: Record<string, unknown> = {};
    for (const name of ["blockSelection", "temporaryUnlock", "isBlocked"]) {
      target[name] = function (this: unknown, ...args: unknown[]) {
        calls.push({ name, self: this, args });
        return `${name}-result`;
      };
    }
    const module = new Proxy(target, {
      get(object, key) {
        accessed += 1;
        return object[key as string];
      },
    });
    jest.doMock("../ExpoScreenTimeModule", () => ({ __esModule: true, default: module }));
  });

  it("does not touch the native module at import time", () => {
    jest.isolateModules(() => {
      require("../functions");
    });
    expect(accessed).toBe(0);
  });

  it("calls the method on the module with the arguments, and returns its result", () => {
    jest.isolateModules(() => {
      const functions = require("../functions");
      const module = require("../ExpoScreenTimeModule").default;
      expect(functions.blockSelection("work")).toBe("blockSelection-result");
      expect(functions.temporaryUnlock(60)).toBe("temporaryUnlock-result");
      const target = { handle: "QUJD", type: "application" as const };
      expect(functions.temporaryUnlock(60, { target })).toBe("temporaryUnlock-result");
      expect(functions.isBlocked()).toBe("isBlocked-result");
      expect(calls).toEqual([
        { name: "blockSelection", self: module, args: ["work"] },
        { name: "temporaryUnlock", self: module, args: [60] },
        { name: "temporaryUnlock", self: module, args: [60, { target }] },
        { name: "isBlocked", self: module, args: [] },
      ]);
    });
  });
});
