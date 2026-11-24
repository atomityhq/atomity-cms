// @vitest-environment jsdom
import { useState } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  GuardedLink,
  NavigationGuardProvider,
  useBlockNavigation,
  useGuardedAction,
  useGuardedNavigate,
} from "./NavigationGuard";

const pushMock = vi.fn();
const replaceMock = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: pushMock,
    replace: replaceMock,
  }),
}));

vi.mock("@/components/ui/ConfirmDialog", () => ({
  ConfirmDialog: ({
    title,
    confirmLabel,
    onClose,
    onConfirm,
  }: {
    title: string;
    confirmLabel: string;
    onClose: () => void;
    onConfirm: () => void;
  }) => (
    <div role="dialog" aria-label={title}>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button type="button" onClick={onConfirm}>
        {confirmLabel}
      </button>
    </div>
  ),
}));

function EditorHarness({
  initialDirty = false,
  onSaveComplete,
}: {
  initialDirty?: boolean;
  onSaveComplete?: () => void;
}) {
  const [dirty, setDirty] = useState(initialDirty);
  const [title, setTitle] = useState(initialDirty ? "Draft title" : "");
  const guardedNavigate = useGuardedNavigate();
  const guardedAction = useGuardedAction();

  useBlockNavigation(dirty);

  const handleSave = () => {
    setDirty(false);
    onSaveComplete?.();
  };

  return (
    <div>
      <input
        aria-label="Title"
        value={title}
        onChange={(e) => {
          setTitle(e.target.value);
          setDirty(true);
        }}
      />
      <button type="button" onClick={handleSave}>
        Save
      </button>
      <GuardedLink href="/content">Back to content</GuardedLink>
      <button type="button" onClick={() => guardedNavigate("/content/list")}>
        Go to list
      </button>
      <button type="button" onClick={() => guardedAction(() => replaceMock("/content/saved-id"))}>
        Save and replace
      </button>
    </div>
  );
}

describe("NavigationGuard", () => {
  beforeEach(() => {
    pushMock.mockReset();
    replaceMock.mockReset();
    window.history.replaceState(null, "", "/content/new");
  });

  afterEach(() => {
    cleanup();
  });

  it("prompts on browser Back (popstate) when dirty and keeps unsaved changes on cancel", () => {
    const { unmount } = render(
      <NavigationGuardProvider>
        <EditorHarness initialDirty />
      </NavigationGuardProvider>,
    );

    const input = screen.getByLabelText("Title") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "Edited unsaved title" } });

    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    expect(screen.getByRole("dialog")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(input.value).toBe("Edited unsaved title");

    unmount();
  });

  it("handles repeated popstate events without losing unsaved edits on cancel", () => {
    const { unmount } = render(
      <NavigationGuardProvider>
        <EditorHarness initialDirty />
      </NavigationGuardProvider>,
    );

    const input = screen.getByLabelText("Title") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "Multi-step draft" } });

    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    expect(screen.getAllByRole("dialog")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(input.value).toBe("Multi-step draft");

    unmount();
  });

  it("navigates backward cleanly when user confirms discarding unsaved changes", async () => {
    const goSpy = vi.spyOn(window.history, "go").mockImplementation(() => {});

    const { unmount } = render(
      <NavigationGuardProvider>
        <EditorHarness initialDirty />
      </NavigationGuardProvider>,
    );

    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));

    await waitFor(() => {
      expect(goSpy).toHaveBeenCalled();
    });

    goSpy.mockRestore();
    unmount();
  });

  it("supports edit -> save -> edit cycles and cleans up guard history on save", async () => {
    const { unmount } = render(
      <NavigationGuardProvider>
        <EditorHarness />
      </NavigationGuardProvider>,
    );

    const input = screen.getByLabelText("Title");
    fireEvent.change(input, { target: { value: "First edit" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    // Allow any async popstate from history.back() cleanup to settle before the next edit.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    fireEvent.change(input, { target: { value: "Second edit" } });

    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });

    expect(screen.getByRole("dialog")).toBeTruthy();
    unmount();
  });

  it("does not block navigation after saving a new item", () => {
    const { unmount } = render(
      <NavigationGuardProvider>
        <EditorHarness initialDirty />
      </NavigationGuardProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    fireEvent.click(screen.getByRole("link", { name: "Back to content" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    unmount();
  });
});