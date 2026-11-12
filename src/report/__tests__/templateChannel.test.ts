import {
  acquireTemplate,
  clearTemplateChannel,
  hashTemplate,
  releaseTemplate,
} from "../templateChannel";

const mockSetReportTemplate = jest.fn();
const mockClearReportTemplate = jest.fn();

jest.mock("../../ExpoScreenTimeModule", () => ({
  __esModule: true,
  default: {
    setReportTemplate: (json: string) => mockSetReportTemplate(json),
    clearReportTemplate: () => mockClearReportTemplate(),
  },
}));

describe("templateChannel ownership", () => {
  beforeEach(() => {
    clearTemplateChannel();
    mockSetReportTemplate.mockClear();
    mockClearReportTemplate.mockClear();
  });

  it("acquire is idempotent per owner+json (safe to call during render)", () => {
    const owner = {};
    acquireTemplate(owner, "A");
    acquireTemplate(owner, "A");
    acquireTemplate(owner, "A");
    expect(mockSetReportTemplate).toHaveBeenCalledTimes(1);
  });

  it("releasing a non-active owner does not clear the channel", () => {
    const first = {};
    const second = {};
    acquireTemplate(first, "A");
    acquireTemplate(second, "B");
    releaseTemplate(first);
    expect(mockClearReportTemplate).not.toHaveBeenCalled();
    expect(mockSetReportTemplate).toHaveBeenLastCalledWith("B");
  });

  it("two owners with IDENTICAL templates don't clear each other", () => {
    const first = {};
    const second = {};
    acquireTemplate(first, "SAME");
    acquireTemplate(second, "SAME");
    releaseTemplate(first);
    // second still owns the channel — no clear, no rewrite needed.
    expect(mockClearReportTemplate).not.toHaveBeenCalled();
  });

  it("releasing the active owner restores the most recent survivor", () => {
    const first = {};
    const second = {};
    acquireTemplate(first, "A");
    acquireTemplate(second, "B");
    releaseTemplate(second);
    expect(mockSetReportTemplate).toHaveBeenLastCalledWith("A");
    expect(mockClearReportTemplate).not.toHaveBeenCalled();
  });

  it("releasing the last owner clears the channel", () => {
    const owner = {};
    acquireTemplate(owner, "A");
    releaseTemplate(owner);
    expect(mockClearReportTemplate).toHaveBeenCalledTimes(1);
  });

  it("hashTemplate is stable and length-suffixed", () => {
    expect(hashTemplate("abc")).toBe(hashTemplate("abc"));
    expect(hashTemplate("abc")).not.toBe(hashTemplate("abd"));
    expect(hashTemplate("abc")).toMatch(/-3$/);
  });
});
