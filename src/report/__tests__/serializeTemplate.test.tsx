import * as React from "react";

import { Report } from "../ReportComponents";
import { serializeTemplate } from "../serializeTemplate";

describe("serializeTemplate", () => {
  it("serializes a representative report template tree", () => {
    expect(
      serializeTemplate(
        <Report.VStack spacing={12} padding={16}>
          <Report.Text
            bind="totalDuration"
            durationStyle="abbreviated"
            size={34}
          />
          <Report.Chart type="bar" bind="hourly" accentColor="#FF6B00" />
          <Report.TopApps limit={5}>
            <Report.HStack>
              <Report.AppIcon size={28} />
              <Report.AppTitle color="#ccc" />
              <Report.Spacer />
              <Report.Text bind="appDuration" durationStyle="short" />
            </Report.HStack>
          </Report.TopApps>
        </Report.VStack>,
      ),
    ).toEqual({
      version: 1,
      root: {
        type: "VStack",
        props: { spacing: 12, padding: 16 },
        children: [
          {
            type: "Text",
            props: {
              bind: "totalDuration",
              durationStyle: "abbreviated",
              size: 34,
            },
          },
          {
            type: "Chart",
            props: { type: "bar", bind: "hourly", accentColor: "#FF6B00" },
          },
          {
            type: "TopApps",
            props: { limit: 5 },
            children: [
              {
                type: "HStack",
                props: {},
                children: [
                  { type: "AppIcon", props: { size: 28 } },
                  { type: "AppTitle", props: { color: "#ccc" } },
                  { type: "Spacer", props: {} },
                  {
                    type: "Text",
                    props: { bind: "appDuration", durationStyle: "short" },
                  },
                ],
              },
            ],
          },
        ],
      },
    });
  });

  it("turns string and number children of Report.Text into the text prop", () => {
    expect(
      serializeTemplate(
        React.createElement(Report.Text as any, null, "Hello", 1, 2),
      ),
    ).toEqual({
      version: 1,
      root: { type: "Text", props: { text: "Hello12" } },
    });
  });

  it("flattens fragments and skips null false and undefined conditional children inside a VStack", () => {
    expect(
      serializeTemplate(
        <Report.VStack>
          <>
            <Report.Text text="First" />
            {null}
            {false}
            {undefined}
            <Report.Text text="Second" />
          </>
        </Report.VStack>,
      ),
    ).toEqual({
      version: 1,
      root: {
        type: "VStack",
        props: {},
        children: [
          { type: "Text", props: { text: "First" } },
          { type: "Text", props: { text: "Second" } },
        ],
      },
    });
  });

  it("flattens arrays of children from map in order", () => {
    expect(
      serializeTemplate(
        <Report.VStack>
          {["One", "Two", "Three"].map((text) => (
            <Report.Text key={text} text={text} />
          ))}
        </Report.VStack>,
      ),
    ).toEqual({
      version: 1,
      root: {
        type: "VStack",
        props: {},
        children: [
          { type: "Text", props: { text: "One" } },
          { type: "Text", props: { text: "Two" } },
          { type: "Text", props: { text: "Three" } },
        ],
      },
    });
  });

  it("invokes a hook-free user function component and serializes its output", () => {
    function Summary({ label }: { label: string }) {
      return (
        <Report.HStack spacing={8}>
          <Report.Text text={label} />
          <Report.Text bind="totalDuration" durationStyle="short" />
        </Report.HStack>
      );
    }

    expect(serializeTemplate(<Summary label="Today" />)).toEqual({
      version: 1,
      root: {
        type: "HStack",
        props: { spacing: 8 },
        children: [
          { type: "Text", props: { text: "Today" } },
          {
            type: "Text",
            props: { bind: "totalDuration", durationStyle: "short" },
          },
        ],
      },
    });
  });

  it("throws a hook-free hint when a user component calls React.useState", () => {
    function Stateful() {
      React.useState(0);
      return <Report.Text text="Never reached" />;
    }

    const consoleError = jest
      .spyOn(console, "error")
      .mockImplementation(() => undefined);

    try {
      expect(() => serializeTemplate(<Stateful />)).toThrow(/hook-free/);
    } finally {
      consoleError.mockRestore();
    }
  });

  it("throws an error listing the supported vocabulary for an unknown component", () => {
    expect(() => serializeTemplate(React.createElement("div", null))).toThrow(
      /Only Report\.\* components \(VStack, HStack, ZStack, Grid, Spacer, Divider, Text, Symbol, Chart, ProgressBar, Gauge, TopApps, Categories, AppIcon, AppTitle\)/,
    );
  });

  it("rejects Report.AppIcon at root scope but allows it inside Report.Categories", () => {
    expect(() => serializeTemplate(<Report.AppIcon size={24} />)).toThrow(
      /only valid inside <Report\.TopApps> or <Report\.Categories>/,
    );

    expect(
      serializeTemplate(
        <Report.Categories>
          <Report.AppIcon size={24} />
        </Report.Categories>,
      ),
    ).toEqual({
      version: 1,
      root: {
        type: "Categories",
        props: {},
        children: [{ type: "AppIcon", props: { size: 24 } }],
      },
    });
  });

  it("throws exactly one root for a fragment with two root children", () => {
    expect(() =>
      serializeTemplate(
        <>
          <Report.Text text="One" />
          <Report.Text text="Two" />
        </>,
      ),
    ).toThrow(/exactly one root/);
  });

  it("throws the non-serializable prop error for a function prop on a marker", () => {
    expect(() =>
      serializeTemplate(
        React.createElement(Report.Text as any, {
          text: "Tap",
          onPress: () => undefined,
        }),
      ),
    ).toThrow(/prop "onPress" on <Report\.Text> is not serializable/);
  });

  it("throws the descriptor error when a Report marker is called directly", () => {
    expect(() => (Report.VStack as any)({})).toThrow(/descriptor/);
  });

  it("throws when Report.Text has a nested element child", () => {
    expect(() =>
      serializeTemplate(
        React.createElement(
          Report.Text as any,
          null,
          React.createElement(Report.Symbol, { name: "clock" }),
        ),
      ),
    ).toThrow(/<Report\.Text> children must be plain text/);
  });

  it("throws when nesting exceeds 32 levels", () => {
    let element: React.ReactElement = <Report.Text text="Deep" />;

    for (let i = 0; i < 34; i += 1) {
      element = <Report.VStack>{element}</Report.VStack>;
    }

    expect(() => serializeTemplate(element)).toThrow(
      /template nesting exceeds 32 levels/,
    );
  });
});

describe("serializeTemplate — review regressions", () => {
  it("rejects Report.Text combining bind with static text or children", () => {
    expect(() =>
      serializeTemplate(
        React.createElement(Report.Text as any, {
          text: "Static",
          bind: "totalDuration",
        }),
      ),
    ).toThrow(/can't combine `bind` with static text/);

    expect(() =>
      serializeTemplate(
        React.createElement(
          Report.Text as any,
          { bind: "totalDuration" },
          "child text",
        ),
      ),
    ).toThrow(/can't combine `bind` with static text/);
  });

  it("rejects bind=appDuration at root scope but allows root bindings in item scope", () => {
    expect(() => serializeTemplate(<Report.Text bind="appDuration" />)).toThrow(
      /only valid inside <Report\.TopApps>/,
    );

    expect(
      serializeTemplate(
        <Report.TopApps>
          <Report.Text bind="totalDuration" />
        </Report.TopApps>,
      ).root.children?.[0],
    ).toEqual({ type: "Text", props: { bind: "totalDuration" } });
  });

  it("serializes memo- and forwardRef-wrapped user components", () => {
    const Row = ({ label }: { label: string }) => (
      <Report.HStack>
        <Report.Text text={label} />
      </Report.HStack>
    );
    const MemoRow = React.memo(Row);
    const ForwardedRow = React.forwardRef<unknown, { label: string }>(
      (props, _ref) => <Row {...props} />,
    );

    const expected = {
      type: "HStack",
      props: {},
      children: [{ type: "Text", props: { text: "Hi" } }],
    };
    expect(serializeTemplate(<MemoRow label="Hi" />).root).toEqual(expected);
    expect(serializeTemplate(<ForwardedRow label="Hi" />).root).toEqual(
      expected,
    );
  });

  it("treats memo-wrapped Report markers as the marker itself", () => {
    const MemoText = React.memo(Report.Text);
    expect(serializeTemplate(<MemoText text="Hi" />).root).toEqual({
      type: "Text",
      props: { text: "Hi" },
    });
  });

  it("serializes the v1.1 components (ProgressBar, Gauge) with their bindings", () => {
    expect(
      serializeTemplate(
        <Report.VStack>
          <Report.Gauge
            bind="totalDuration"
            goal={14400}
            size={64}
            color="#AF52DE"
          />
          <Report.ProgressBar
            goal={14400}
            height={10}
            color="#AF52DE"
            trackColor="#E5E5EA"
          />
        </Report.VStack>,
      ).root.children,
    ).toEqual([
      {
        type: "Gauge",
        props: {
          bind: "totalDuration",
          goal: 14400,
          size: 64,
          color: "#AF52DE",
        },
      },
      {
        type: "ProgressBar",
        props: {
          goal: 14400,
          height: 10,
          color: "#AF52DE",
          trackColor: "#E5E5EA",
        },
      },
    ]);
  });

  it("rejects item-only bindings at root scope on any bindable component", () => {
    expect(() =>
      serializeTemplate(<Report.Gauge bind="appDuration" goal={3600} />),
    ).toThrow(/only valid inside <Report\.TopApps>/);
    expect(() => serializeTemplate(<Report.Text bind="appName" />)).toThrow(
      /only valid inside <Report\.TopApps>/,
    );
    // …but they serialize fine inside an item scope.
    expect(
      serializeTemplate(
        <Report.TopApps>
          <Report.Text bind="appName" />
        </Report.TopApps>,
      ).root.children?.[0],
    ).toEqual({ type: "Text", props: { bind: "appName" } });
  });

  it("rejects array props containing non-primitives but accepts primitive arrays", () => {
    expect(() =>
      serializeTemplate(
        React.createElement(Report.Text as any, {
          text: "Hi",
          weights: [{ nested: true }],
        }),
      ),
    ).toThrow(/not serializable/);

    expect(
      serializeTemplate(
        React.createElement(Report.Text as any, {
          text: "Hi",
          steps: [1, 2, 3],
        }),
      ).root.props,
    ).toEqual({ text: "Hi", steps: [1, 2, 3] });
  });
});
