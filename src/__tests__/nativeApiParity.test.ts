import * as fs from "fs";
import * as path from "path";

// The three declarations of the module's surface must agree:
//   - src/ExpoScreenTimeModule.ts   (what JS can call)
//   - ios/ExpoScreenTimeModule.swift (the iOS implementation)
//   - android/.../NativeFunctions.kt  (the Android registry; every entry fails
//                                      with UNSUPPORTED until implemented)
// Names, arity and sync/async must match so a shared screen behaves the same
// on both platforms. The check is a cheap regex over the sources: no Xcode or
// Gradle needed, and it runs in `bun run test` on every PR.

const ROOT = path.resolve(__dirname, "..", "..");
const read = (rel: string) => fs.readFileSync(path.join(ROOT, rel), "utf8");

type Fn = { name: string; arity: number; kind: "sync" | "async" };

function parseSwift(): { functions: Fn[]; views: string[]; events: string[] } {
  const swift = read("ios/ExpoScreenTimeModule.swift");
  const functions: Fn[] = [];
  // `Function("name") { (a: T, promise: Promise) in` — the parameter list is
  // optional (`{` alone or `{ () -> R in` both mean zero arguments).
  const fnRe = /^\s*(AsyncFunction|Function)\("(\w+)"\)\s*\{\s*(?:\(([^)]*)\))?/gm;
  for (const m of swift.matchAll(fnRe)) {
    const params = (m[3] ?? "")
      .split(",")
      .map((p) => p.trim())
      .filter((p) => p.length > 0 && !/^promise\s*:/.test(p));
    functions.push({
      name: m[2],
      arity: params.length,
      kind: m[1] === "AsyncFunction" ? "async" : "sync",
    });
  }
  const views = [...swift.matchAll(/^\s*View\((\w+)\.self\)/gm)].map((m) => m[1]);
  const events = [...swift.matchAll(/^\s*Events\(([^)]*)\)/gm)].flatMap((m) =>
    [...m[1].matchAll(/"(\w+)"/g)].map((e) => e[1]),
  );
  return { functions, views, events };
}

function parseKotlin(): { functions: Fn[]; views: string[]; events: string[] } {
  const registry = read(
    "android/src/main/java/expo/modules/screentime/NativeFunctions.kt",
  );
  const module = read(
    "android/src/main/java/expo/modules/screentime/ExpoScreenTimeModule.kt",
  );
  const functions = [
    ...registry.matchAll(/^\s*(sync|async)\("(\w+)",\s*(\d+)\)/gm),
  ].map((m) => ({
    name: m[2],
    arity: Number(m[3]),
    kind: m[1] as Fn["kind"],
  }));
  const views = [...module.matchAll(/^\s*View\((\w+)::class\)/gm)].map(
    (m) => m[1],
  );
  const events = [...module.matchAll(/^\s*Events\(([^)]*)\)/gm)].flatMap((m) =>
    [...m[1].matchAll(/"(\w+)"/g)].map((e) => e[1]),
  );
  return { functions, views, events };
}

function parseTypeScript(): string[] {
  const ts = read("src/ExpoScreenTimeModule.ts");
  const body = ts.slice(ts.indexOf("declare class ExpoScreenTimeModule"));
  // Method declarations start the line with `name(`; doc comments start with `*`.
  return [...body.matchAll(/^\s+(\w+)\(/gm)].map((m) => m[1]);
}

const byName = (fns: Fn[]) =>
  Object.fromEntries(fns.map((f) => [f.name, f])) as Record<string, Fn>;

describe("native API parity (TS ↔ Swift ↔ Kotlin)", () => {
  const swift = parseSwift();
  const kotlin = parseKotlin();
  const tsNames = parseTypeScript();

  it("parses a plausible surface", () => {
    expect(swift.functions.length).toBeGreaterThan(40);
    expect(swift.views).toHaveLength(3);
    expect(tsNames.length).toBe(swift.functions.length);
  });

  it("Swift declares exactly the functions the TS class declares", () => {
    expect(swift.functions.map((f) => f.name).sort()).toEqual(
      [...tsNames].sort(),
    );
  });

  it("Kotlin registers every Swift function with the same arity and kind", () => {
    const k = byName(kotlin.functions);
    const s = byName(swift.functions);
    expect(Object.keys(k).sort()).toEqual(Object.keys(s).sort());
    for (const name of Object.keys(s)) {
      expect({ name, ...k[name] }).toEqual({ name, ...s[name] });
    }
  });

  it("Kotlin registers the same view names and events as Swift", () => {
    expect(kotlin.views.sort()).toEqual(swift.views.sort());
    expect(kotlin.events.sort()).toEqual(swift.events.sort());
  });

  it("the Kotlin registry has no duplicate names", () => {
    const names = kotlin.functions.map((f) => f.name);
    expect(new Set(names).size).toBe(names.length);
  });
});
