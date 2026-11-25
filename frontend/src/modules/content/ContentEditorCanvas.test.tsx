// @vitest-environment jsdom
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/types/api";
import type { Post } from "@/types/post";
import { ContentEditorCanvas } from "./ContentEditorCanvas";
import { post, TYPES } from "./form.fixtures";

// Mock only the boundaries: network, router, and UI components unrelated to saving.
// The real editor state, keyboard listener, save function, and form helpers run.
const api = vi.hoisted(() => ({
  getContentTypes: vi.fn(),
  getPost: vi.fn(),
  updatePost: vi.fn(),
  createPost: vi.fn(),
  publishPost: vi.fn(),
  unpublishPost: vi.fn(),
  archivePost: vi.fn(),
  deletePost: vi.fn(),
}));
vi.mock("./api", () => api);
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));
vi.mock("@/components/shell/NavigationGuard", () => ({
  useBlockNavigation: () => {},
  GuardedLink: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock("@/hooks/useUnsavedChangesWarning", () => ({
  useUnsavedChangesWarning: () => {},
}));
vi.mock("@/components/ui/Toast", () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}));
vi.mock("./EditorSidebar", () => ({ EditorSidebar: () => null }));
vi.mock("./RichTextEditor", () => ({ RichTextEditor: () => null }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function loadEditor() {
  render(<ContentEditorCanvas contentId="p1" />);
  return screen.findByRole("textbox", { name: "Title" });
}

function editTitle(title: string) {
  fireEvent.change(screen.getByRole("textbox", { name: "Title" }), {
    target: { value: title },
  });
}

function pressSaveShortcut() {
  fireEvent.keyDown(window, { key: "s", ctrlKey: true });
}

function savedPost(title: string, version = 4): Post {
  return post({
    title,
    slug: title.toLowerCase().replaceAll(" ", "-"),
    version,
  });
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  api.getContentTypes.mockResolvedValue(TYPES);
  api.getPost.mockResolvedValue(post());
});
afterEach(() => cleanup());

describe("ContentEdiorCanvas : Issue #4", () => {
  it("saves normally when Ctrl+S is pressed once", async () => {
    // step 1: Mock a successfull backend response;
    api.updatePost.mockResolvedValue(savedPost("First"));

    // Step 2 : Rneder the eidtor with an exisiting artical.
    await loadEditor();

    // Step 3 :  Chnage the article title.
    editTitle("First");

    //Step 4 :  simulate Ctrl + S.
    pressSaveShortcut();

    // Step 5: Verify that only one request was sent.
    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(1);
    });
    // Step 6: Verify that the updated title was submitted.
    expect(api.updatePost).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        title: "First",
      }),
    );
    // Step 7: Verify that no unsaved changes remain.
    await waitFor(() => {
      expect(screen.queryByText("Unsaved changes")).toBeNull();
    });
  });
  //
  it("ignores repeated Ctrl+s pressess while a save is pending ", async () => {
    // Step 1 :  Create Promise that we controle manually
    const request = deferred<Post>();

    // Step 2 : Make updatePOst return this pending Promise.
    api.updatePost.mockReturnValue(request.promise);

    // Step 3 : Load an exisiting article.
    await loadEditor();

    // Step 4 :  LOad an exisiting artical
    editTitle("First");
    // Step 5: Press Ctrl+S six times before the request finishes.
    for (let index = 0; index < 6; index++) {
      pressSaveShortcut();
    }
    // Step 6: Verify that only one request was sent.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 7: Simulate the server successfully completing the save.
    await act(async () => {
      request.resolve(savedPost("First"));
      await request.promise;
    });

    // Step 8: Confirm no extra requests were started.
    expect(api.updatePost).toHaveBeenCalledTimes(1);
  });

  it("disables the Save button while a request is pending", async () => {
    // Step 1: Create a controllable pending request.
    const request = deferred<Post>();

    // Step 2: Mock updatePost to return the pending request.
    api.updatePost.mockReturnValue(request.promise);

    // Step 3: Render an existing article.
    await loadEditor();

    // Step 4: Change the title to create unsaved changes.
    editTitle("First");

    // Step 5: Find the Save draft button.
    const saveButton = screen.getByRole("button", {
      name: "Save draft",
    }) as HTMLButtonElement;

    // The button should be enabled before saving.
    expect(saveButton.disabled).toBe(false);

    // Step 6: Click Save draft.
    fireEvent.click(saveButton);

    // Verify the first request started.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 7: The button must be disabled during saving.
    expect(saveButton.disabled).toBe(true);

    // Step 8: Try Ctrl+S while the first request is pending.
    pressSaveShortcut();

    // The shortcut must not start another request.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 9: Simulate a successful backend response.
    await act(async () => {
      request.resolve(savedPost("First"));
      await request.promise;
    });

    // Step 10: Confirm there were no duplicate requests.
    expect(api.updatePost).toHaveBeenCalledTimes(1);
  });

  it("preserves edits made while a save request is pending", async () => {
    // Step 1: Create a Promise we can control.
    const request = deferred<Post>();

    // Step 2: Keep the API request pending.
    api.updatePost.mockReturnValue(request.promise);

    // Step 3: Load an existing article.
    await loadEditor();

    // Step 4: Change the title to "First".
    editTitle("First");

    // Step 5: Start saving.
    pressSaveShortcut();

    // Verify the first request contains "First".
    expect(api.updatePost).toHaveBeenCalledWith(
      "p1",
      expect.objectContaining({
        title: "First",
      }),
    );

    // Step 6: While saving, change the title again.
    editTitle("Second");

    // Step 7: Complete the first save request.
    await act(async () => {
      request.resolve(savedPost("First"));
      await request.promise;
    });

    // Step 8: Verify the newer title was preserved.
    const titleInput = screen.getByRole("textbox", {
      name: "Title",
    }) as HTMLInputElement;

    expect(titleInput.value).toBe("Second");

    // Step 9: The newer title must still be unsaved.
    await waitFor(() => {
      expect(screen.getByText("Unsaved changes")).toBeTruthy();
    });

    // Step 10: Only the original request was sent.
    expect(api.updatePost).toHaveBeenCalledTimes(1);
  });

  it("saves subsequent edits using the latest post version", async () => {
    // Step 1: Create a pending request that we control.
    const firstRequest = deferred<Post>();

    // Step 2: Configure two API responses.
    api.updatePost
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValueOnce(savedPost("Second", 5));

    // Step 3: Render the editor.
    await loadEditor();

    // Step 4: Change the title and start the first save.
    editTitle("First");
    pressSaveShortcut();

    // Step 5: Confirm the first request started.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 6: Make new edits while the first save is pending.
    editTitle("Second");

    // Step 7: Complete the first request with version 4.
    await act(async () => {
      firstRequest.resolve(savedPost("First", 4));
      await firstRequest.promise;
    });

    // Step 8: The newer changes must still be unsaved.
    expect(
      (
        screen.getByRole("textbox", {
          name: "Title",
        }) as HTMLInputElement
      ).value,
    ).toBe("Second");

    expect(screen.getByText("Unsaved changes")).toBeTruthy();

    // Step 9: Start a second save.
    pressSaveShortcut();

    // Step 10: Verify the second API request.
    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(2);
    });

    // Step 11: Verify the latest title and server version.
    expect(api.updatePost).toHaveBeenNthCalledWith(
      2,
      "p1",
      expect.objectContaining({
        title: "Second",
        version: 4,
      }),
    );

    // Step 12: Verify the newer changes were saved.
    await waitFor(() => {
      expect(screen.queryByText("Unsaved changes")).toBeNull();
    });
  });

  it("allows retrying after a failed save request", async () => {
    // Step 1: Create a request we can control.
    const firstRequest = deferred<Post>();

    // Step 2: The first request stays pending,
    // while the second request will succeed.
    api.updatePost
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValueOnce(savedPost("First", 4));

    // Step 3: Load an existing article.
    await loadEditor();

    // Step 4: Edit the title.
    editTitle("First");

    // Step 5: Start the first save request.
    pressSaveShortcut();

    // Step 6: Confirm the first request started.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 7: Simulate a network failure.
    await act(async () => {
      firstRequest.reject(new Error("Network unavailable"));
    });

    // Step 8: The changes must still be unsaved.
    expect(screen.getByText("Unsaved changes")).toBeTruthy();

    // Step 9: Retry saving by pressing Ctrl+S.
    pressSaveShortcut();

    // Step 10: Verify that a second request started.
    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(2);
    });

    // Step 11: Verify that the retry contains the correct data.
    expect(api.updatePost).toHaveBeenNthCalledWith(
      2,
      "p1",
      expect.objectContaining({
        title: "First",
        version: 3,
      }),
    );

    // Step 12: Confirm the retry completed successfully.
    await waitFor(() => {
      expect(screen.queryByText("Unsaved changes")).toBeNull();
    });
  });

  it("allows saving after correcting a validation error", async () => {
    // Step 1: Mock a successful backend response.
    api.updatePost.mockResolvedValue(savedPost("First", 4));

    // Step 2: Load an existing article.
    await loadEditor();

    // Step 3: Enter an invalid title (201 characters).
    editTitle("x".repeat(201));

    // Step 4: Attempt to save the invalid form.
    pressSaveShortcut();

    // Step 5: Verify no API request was sent.
    expect(api.updatePost).not.toHaveBeenCalled();

    // Step 6: Correct the invalid title.
    editTitle("First");

    // Step 7: Try saving again.
    pressSaveShortcut();

    // Step 8: Verify saving works after validation.
    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(1);
    });

    // Step 9: Verify the correct title was submitted.
    expect(api.updatePost).toHaveBeenCalledWith(
      "p1",
      expect.objectContaining({
        title: "First",
        version: 3,
      }),
    );

    // Step 10: Verify that the changes were saved.
    await waitFor(() => {
      expect(screen.queryByText("Unsaved changes")).toBeNull();
    });
  });

  it("does not save again after a successful save without new edits", async () => {
    // Step 1: Mock a successful backend response.
    api.updatePost.mockResolvedValue(savedPost("First", 4));

    // Step 2: Load an existing article.
    await loadEditor();

    // Step 3: Change the article title.
    editTitle("First");

    // Step 4: Save the change.
    pressSaveShortcut();

    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(1);
    });

    // Step 5: Wait until the save finishes.
    await waitFor(() => {
      expect(screen.queryByText("Unsaved changes")).toBeNull();
    });

    // Step 6: Verify the Save button is disabled.
    const saveButton = screen.getByRole("button", {
      name: "Save draft",
    }) as HTMLButtonElement;

    expect(saveButton.disabled).toBe(true);

    // Step 7: Press Ctrl+S five more times.
    for (let i = 0; i < 5; i++) {
      pressSaveShortcut();
    }

    // Step 8: No additional requests should be sent.
    expect(api.updatePost).toHaveBeenCalledTimes(1);
  });

  it("does not save an unchanged post when Ctrl+S is pressed", async () => {
    // Step 1: Load an existing article.
    await loadEditor();

    // Step 2: Confirm the Save button is disabled.
    const saveButton = screen.getByRole("button", {
      name: "Save draft",
    }) as HTMLButtonElement;

    expect(saveButton.disabled).toBe(true);

    // Step 3: Press Ctrl+S without editing anything.
    pressSaveShortcut();

    // Step 4: No update request should be sent.
    expect(api.updatePost).not.toHaveBeenCalled();
  });

  it("preserves genuine version-conflict handling", async () => {
    // Step 1: Simulate a genuine backend version conflict.
    api.updatePost.mockRejectedValue(
      new ApiError(
        "VERSION_CONFLICT",
        "The article has been modified by another editor.",
        409,
      ),
    );

    // Step 2: Load an existing article.
    await loadEditor();

    // Step 3: Change the article title.
    editTitle("First");

    // Step 4: Attempt to save with Ctrl+S.
    pressSaveShortcut();

    // Step 5: Verify one API request was sent.
    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(1);
    });

    // Step 6: Verify that the conflict warning appears.
    expect(await screen.findByText("Newer version available")).toBeTruthy();

    // Step 7: The Save button must be disabled.
    const saveButton = screen.getByRole("button", {
      name: "Save draft",
    }) as HTMLButtonElement;

    expect(saveButton.disabled).toBe(true);

    // Step 8: Press Ctrl+S again after the conflict.
    pressSaveShortcut();

    // Step 9: Verify no unsafe retry was sent.
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    // Step 10: The user must still be able to reload.
    expect(
      screen.getByRole("button", {
        name: "Reload latest",
      }),
    ).toBeTruthy();
  });

  it("shows recovery banner when a local draft exists and allows restoring it", async () => {
    localStorage.setItem(
      "atomity:content-recovery:p1",
      JSON.stringify({
        formatVersion: 1,
        savedAt: Date.now(),
        baseVersion: post().version,
        form: {
          type: "news",
          title: "Recovered Unsaved Title",
          slug: "recovered-unsaved-title",
          excerpt: "",
          content: { type: "doc", content: [{ type: "paragraph" }] },
          fields: {},
        },
      }),
    );

    await loadEditor();

    expect(await screen.findByText(/An unsaved recovery draft is available/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Restore draft" }));

    expect(screen.queryByText(/An unsaved recovery draft is available/)).toBeNull();
    const titleInput = screen.getByRole("textbox", { name: "Title" }) as HTMLTextAreaElement;
    expect(titleInput.value).toBe("Recovered Unsaved Title");
  });

  it("removes recovery draft and hides banner when Discard is clicked", async () => {
    localStorage.setItem(
      "atomity:content-recovery:p1",
      JSON.stringify({
        formatVersion: 1,
        savedAt: Date.now(),
        baseVersion: post().version,
        form: {
          type: "news",
          title: "Discard Me",
          slug: "discard-me",
          excerpt: "",
          content: { type: "doc", content: [{ type: "paragraph" }] },
          fields: {},
        },
      }),
    );

    await loadEditor();

    expect(await screen.findByText(/An unsaved recovery draft is available/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Discard" }));

    expect(screen.queryByText(/An unsaved recovery draft is available/)).toBeNull();
    expect(localStorage.getItem("atomity:content-recovery:p1")).toBeNull();
  });

  it("persists draft to storage as the user edits", async () => {
    await loadEditor();
    editTitle("Freshly Typed Title");

    await waitFor(() => {
      const saved = localStorage.getItem("atomity:content-recovery:p1");
      expect(saved).not.toBeNull();
      expect(JSON.parse(saved!).form.title).toBe("Freshly Typed Title");
    });
  });

  it("preserves unresolved recovery drafts when editing or saving unrelated changes", async () => {
    localStorage.setItem(
      "atomity:content-recovery:p1",
      JSON.stringify({
        formatVersion: 1,
        savedAt: Date.now(),
        baseVersion: post().version,
        form: {
          type: "news",
          title: "Unresolved Important Draft",
          slug: "unresolved-important-draft",
          excerpt: "",
          content: { type: "doc", content: [{ type: "paragraph" }] },
          fields: {},
        },
      }),
    );

    api.updatePost.mockResolvedValue(savedPost("First"));

    await loadEditor();

    expect(await screen.findByText(/An unsaved recovery draft is available/)).toBeTruthy();

    editTitle("First");

    const stored = JSON.parse(localStorage.getItem("atomity:content-recovery:p1")!);
    expect(stored.form.title).toBe("Unresolved Important Draft");

    pressSaveShortcut();

    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(1);
    });

    expect(localStorage.getItem("atomity:content-recovery:p1")).not.toBeNull();
    const storedAfterSave = JSON.parse(localStorage.getItem("atomity:content-recovery:p1")!);
    expect(storedAfterSave.form.title).toBe("Unresolved Important Draft");
    expect(screen.getByText(/An unsaved recovery draft is available/)).toBeTruthy();
  });

  it("retains recovery draft if save fails, and cleans it up after successful save", async () => {
    localStorage.setItem(
      "atomity:content-recovery:p1",
      JSON.stringify({
        formatVersion: 1,
        savedAt: Date.now(),
        baseVersion: post().version,
        form: {
          type: "news",
          title: "Restored Form",
          slug: "restored-form",
          excerpt: "",
          content: { type: "doc", content: [{ type: "paragraph" }] },
          fields: {},
        },
      }),
    );

    const firstRequest = deferred<Post>();
    api.updatePost
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValueOnce(savedPost("Restored Form", 4));

    await loadEditor();
    fireEvent.click(screen.getByRole("button", { name: "Restore draft" }));

    pressSaveShortcut();
    expect(api.updatePost).toHaveBeenCalledTimes(1);

    await act(async () => {
      firstRequest.reject(new Error("Server Failure"));
    });

    expect(localStorage.getItem("atomity:content-recovery:p1")).not.toBeNull();

    pressSaveShortcut();

    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledTimes(2);
    });

    await waitFor(() => {
      expect(localStorage.getItem("atomity:content-recovery:p1")).toBeNull();
    });
  });

  it("preserves baseVersion when a restored draft is saved", async () => {
    api.getPost.mockResolvedValue(post({ version: 10 }));
    api.updatePost.mockResolvedValue(savedPost("Restored Title", 3));

    localStorage.setItem(
      "atomity:content-recovery:p1",
      JSON.stringify({
        formatVersion: 1,
        savedAt: Date.now(),
        baseVersion: 2,
        form: {
          type: "news",
          title: "Restored Title",
          slug: "restored-title",
          excerpt: "",
          content: { type: "doc", content: [{ type: "paragraph" }] },
          fields: {},
        },
      }),
    );

    await loadEditor();
    fireEvent.click(screen.getByRole("button", { name: "Restore draft" }));

    pressSaveShortcut();

    await waitFor(() => {
      expect(api.updatePost).toHaveBeenCalledWith(
        "p1",
        expect.objectContaining({
          title: "Restored Title",
          version: 2,
        }),
      );
    });
  });
});

describe("ContentEditorCanvas : delete action", () => {
  it("deletes a draft after confirmation", async () => {
    api.getPost.mockResolvedValue(post({ status: "DRAFT" }));
    api.deletePost.mockResolvedValue(undefined);

    await loadEditor();
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    expect(screen.getByRole("dialog")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => {
      expect(api.deletePost).toHaveBeenCalledWith("p1");
    });
  });

  it("allows deleting an archived item", async () => {
    api.getPost.mockResolvedValue(post({ status: "ARCHIVED" }));
    api.deletePost.mockResolvedValue(undefined);

    await loadEditor();
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => {
      expect(api.deletePost).toHaveBeenCalledWith("p1");
    });
  });

  it("does not offer delete for published items", async () => {
    api.getPost.mockResolvedValue(post({ status: "PUBLISHED" }));

    await loadEditor();
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));

    expect(screen.queryByRole("menuitem", { name: "Delete" })).toBeNull();
  });
});
