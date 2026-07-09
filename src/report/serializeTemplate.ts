import {
  Fragment,
  isValidElement,
  type ReactElement,
  type ReactNode,
} from "react";

import {
  ITEM_ONLY_BINDINGS,
  ITEM_ONLY_TYPES,
  ITEM_SCOPE_TYPES,
} from "./ReportComponents";

// ============================================================================
// TEMPLATE SERIALIZER
//
// Walks a Report.* element tree and produces the JSON shipped to the report
// extension via the App Group. Elements are treated as data ({type, props});
// nothing is ever mounted. User function components are invoked manually
// (hook-free rule — same mechanism as expo-widgets' jsx-runtime stub).
// ============================================================================

export type ReportTemplateNode = {
  type: string;
  props: Record<string, unknown>;
  children?: ReportTemplateNode[];
};

export type ReportTemplate = {
  version: 1;
  root: ReportTemplateNode;
};

const MAX_DEPTH = 32;

const SUPPORTED = [
  "VStack",
  "HStack",
  "ZStack",
  "Grid",
  "Spacer",
  "Divider",
  "Text",
  "Symbol",
  "Chart",
  "ProgressBar",
  "Gauge",
  "TopApps",
  "Categories",
  "AppIcon",
  "AppTitle",
];

class TemplateError extends Error {
  constructor(message: string) {
    super(`[expo-screen-time] Invalid report template: ${message}`);
    this.name = "ReportTemplateError";
  }
}

/**
 * Serialize a Report.* element tree into the JSON template consumed by the
 * DeviceActivityReport extension. Throws a descriptive error on anything that
 * can't be represented (unknown components, hooks in user components, raw text
 * outside `Report.Text`, item-scope components at root scope…).
 * @platform ios
 */
export function serializeTemplate(element: ReactElement): ReportTemplate {
  const roots = serializeNode(element, { depth: 0, inItemScope: false });
  const root = roots[0];
  if (roots.length !== 1 || root === undefined) {
    throw new TemplateError(
      `the template must have exactly one root element (got ${roots.length}). ` +
        `Wrap siblings in a <Report.VStack> or <Report.HStack>.`,
    );
  }
  return { version: 1, root };
}

type Scope = { depth: number; inItemScope: boolean };

/** A node can serialize to zero (skipped), one, or several (fragment) nodes. */
function serializeNode(node: ReactNode, scope: Scope): ReportTemplateNode[] {
  if (scope.depth > MAX_DEPTH) {
    throw new TemplateError(`template nesting exceeds ${MAX_DEPTH} levels.`);
  }

  // Conditional-rendering artifacts — skipped, like React does.
  if (node === null || node === undefined || typeof node === "boolean") {
    return [];
  }

  if (typeof node === "string" || typeof node === "number") {
    throw new TemplateError(
      `raw text ${JSON.stringify(String(node))} is only allowed as the child of ` +
        `<Report.Text>. Wrap it: <Report.Text>${String(node)}</Report.Text>.`,
    );
  }

  if (Array.isArray(node)) {
    return node.flatMap((child) => serializeNode(child, scope));
  }

  if (!isValidElement(node)) {
    throw new TemplateError(
      `unsupported value of type "${typeof node}" in the template tree.`,
    );
  }

  const { type, props } = node as ReactElement & {
    props: Record<string, unknown>;
  };

  // <>…</> — flatten into the parent.
  if (type === Fragment) {
    return serializeNode(props.children as ReactNode, scope);
  }

  // Report.* marker.
  if (typeof type === "function" && "$$reportType" in type) {
    return [
      serializeMarker((type as any).$$reportType as string, props, scope),
    ];
  }

  // Unwrap React.memo / React.forwardRef so wrapped hook-free compositions
  // still serialize (memo(fn) → fn, forwardRef(fn) → render fn).
  let callable: unknown = type;
  for (let i = 0; i < 4; i++) {
    const t = callable as any;
    if (t?.$$typeof === Symbol.for("react.memo")) {
      callable = t.type;
    } else if (t?.$$typeof === Symbol.for("react.forward_ref")) {
      callable = t.render;
    } else {
      break;
    }
  }

  // User composition: a plain function component — invoke it manually and
  // serialize what it returns. Hooks are unavailable outside React's renderer;
  // we surface that as a template rule rather than a cryptic dispatcher error.
  if (typeof callable === "function") {
    if ("$$reportType" in (callable as any)) {
      // memo(Report.X) — treat as the marker itself.
      return [
        serializeMarker((callable as any).$$reportType as string, props, scope),
      ];
    }
    const name =
      (callable as any).displayName || (callable as any).name || "anonymous";
    let result: ReactNode;
    try {
      result = (callable as (p: unknown, ref?: unknown) => ReactNode)(
        props,
        null,
      );
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      // Hook calls outside React surface differently per engine: "Invalid hook
      // call…" (React dev), "null is not an object (evaluating
      // 'resolveDispatcher().useState')" (JSC), "Cannot read properties of null
      // (reading 'useState')" (V8) — match the hook name too, not just "hook".
      const hint = /hook|dispatch|use[A-Z]/.test(reason)
        ? " Report-template components are serialized outside React and must be " +
          "hook-free (pure props → elements)."
        : "";
      throw new TemplateError(
        `component <${name}> threw during serialization: ${reason}.${hint}`,
      );
    }
    return serializeNode(result, { ...scope, depth: scope.depth + 1 });
  }

  // Anything else: host components ('div'), RN components (forwardRef/memo
  // objects), context providers… — not part of the vocabulary.
  const label =
    typeof type === "string"
      ? `<${type}>`
      : `<${(type as any)?.displayName ?? (type as any)?.name ?? "unknown"}>`;
  throw new TemplateError(
    `${label} is not a report-template component. Only Report.* components ` +
      `(${SUPPORTED.join(", ")}) and hook-free compositions of them are allowed — ` +
      `React Native components cannot render inside the report extension.`,
  );
}

function serializeMarker(
  type: string,
  props: Record<string, unknown>,
  scope: Scope,
): ReportTemplateNode {
  if (ITEM_ONLY_TYPES.has(type) && !scope.inItemScope) {
    throw new TemplateError(
      `<Report.${type}> is only valid inside <Report.TopApps> or ` +
        `<Report.Categories> (it binds to the current item).`,
    );
  }

  const { children, ...rest } = props;
  const serializedProps: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(rest)) {
    if (value === undefined) continue;
    const isPrimitive = (v: unknown) =>
      typeof v === "string" || typeof v === "number" || typeof v === "boolean";
    if (
      !isPrimitive(value) &&
      !(Array.isArray(value) && value.every(isPrimitive))
    ) {
      throw new TemplateError(
        `prop "${key}" on <Report.${type}> is not serializable (got ${typeof value}). ` +
          `Template props must be strings, numbers, booleans, or arrays of those.`,
      );
    }
    serializedProps[key] = value;
  }

  // Text-specific validation: bindings and static text are mutually exclusive.
  if (type === "Text") {
    const hasBind = serializedProps.bind !== undefined;
    const hasStatic = serializedProps.text !== undefined || children != null;
    if (hasBind && hasStatic) {
      throw new TemplateError(
        `<Report.Text> can't combine \`bind\` with static text — use one or the other.`,
      );
    }
  }

  // Item-scope bindings (appDuration, appName…) can't be used at root scope —
  // applies to every bindable component (Text, ProgressBar, Gauge).
  if (
    typeof serializedProps.bind === "string" &&
    ITEM_ONLY_BINDINGS.has(serializedProps.bind) &&
    !scope.inItemScope
  ) {
    throw new TemplateError(
      `bind="${serializedProps.bind}" is only valid inside <Report.TopApps> or ` +
        `<Report.Categories> (it binds to the current item).`,
    );
  }

  const nodeChildren: ReportTemplateNode[] = [];

  if (children !== undefined && children !== null) {
    // Sugar: string children of <Report.Text> become the `text` prop.
    if (type === "Text") {
      const parts = (Array.isArray(children) ? children : [children]).filter(
        (c) => c !== null && c !== undefined && typeof c !== "boolean",
      );
      if (!parts.every((c) => typeof c === "string" || typeof c === "number")) {
        throw new TemplateError(
          `<Report.Text> children must be plain text (no nested elements).`,
        );
      }
      if (parts.length > 0) {
        serializedProps.text = parts.join("");
      }
    } else {
      const childScope: Scope = {
        depth: scope.depth + 1,
        inItemScope: scope.inItemScope || ITEM_SCOPE_TYPES.has(type),
      };
      nodeChildren.push(...serializeNode(children as ReactNode, childScope));
    }
  }

  const node: ReportTemplateNode = { type, props: serializedProps };
  if (nodeChildren.length > 0) {
    node.children = nodeChildren;
  }
  return node;
}
