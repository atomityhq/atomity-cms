import { describe, expect, it, afterEach ,vi} from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { Modal } from "./Modal";
import { useState } from "react";

afterEach(() => {
  cleanup();
});

describe("Modal", () => {
  it("renders the title and description", () => {
    render(
      <Modal
        title="New content"
        description="What are you writing?"
        onClose={() => {}}
      >
        <button type="button">Article</button>
      </Modal>,
    );

    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(screen.getByText("New content")).toBeTruthy();
    expect(screen.getByText("What are you writing?")).toBeTruthy();
  });

  it("keeps keyboard focus inside the modal", () => {
    render(
      <Modal
        title="New content"
        onClose={() => {}}
        footer={<button type="button">Cancel</button>}
      >
        <button type="button">Article</button>
        <button type="button">Page</button>
      </Modal>,
    );

    const closeButton = screen.getByRole("button", { name: "Close" });
    const cancelButton = screen.getByRole("button", { name: "Cancel" });

    cancelButton.focus();

    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(closeButton);

    closeButton.focus();

    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(cancelButton);
  });

  it("returns focus to the opener when closed", () => {
    function TestComponent() {
      const [isOpen, setIsOpen] = useState(false);

      return (
        <>
          <button type="button" onClick={() => setIsOpen(true)}>
            Open modal
          </button>

          {isOpen && (
            <Modal title="New content" onClose={() => setIsOpen(false)}>
              <button type="button">Article</button>
            </Modal>
          )}
        </>
      );
    }

    render(<TestComponent />);

    const opener = screen.getByRole("button", { name: "Open modal" });
    opener.focus();
    fireEvent.click(opener);

    fireEvent.click(opener);

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(document.activeElement).toBe(opener);
  });
  it("closes when Escape is pressed", () => {
  const onClose = vi.fn();

  render(
    <Modal title="New content" onClose={onClose}>
      <button type="button">Article</button>
    </Modal>,
  );

  fireEvent.keyDown(document, { key: "Escape" });

  expect(onClose).toHaveBeenCalledOnce();
});
});
