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
    const module: Record<string, unknown> = new Proxy(
      {},
      {
        get(_, key) {
          accessed += 1;
          const name = String(key);
          return function (this: unknown, ...args: unknown[]) {
            calls.push({ name, self: this, args });
            return `${name}-result`;
          };
        },
      },
    );
    jest.doMock("../ExpoScreenTimeModule", () => ({ __esModule: true, default: module }));
  });

  it("does not touch the native module at import time", () => {
    jest.isolateModules(() => {
      require("../functions");
    });
    expect(accessed).toBe(0);
  });

  const names = [...read("functions.ts").matchAll(/export const (\w+)/g)].map((m) => m[1]);

  it("finds every export", () => {
    expect(names.length).toBeGreaterThan(30);
  });

  it.each(names)("%s calls the same-named method on the module and returns its result", (name) => {
    jest.isolateModules(() => {
      const functions = require("../functions");
      const module = require("../ExpoScreenTimeModule").default;
      const args = ["a", { b: 1 }];
      const result = functions[name](...args);
      const recorded = calls.splice(0);
      expect(result).toBe(`${name}-result`);
      expect(recorded.map((c) => [c.name, c.args])).toEqual([[name, args]]);
      expect(recorded[0].self === module).toBe(true);
    });
  });
});
