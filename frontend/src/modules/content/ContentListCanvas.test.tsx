// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Page } from "@/types/api";
import type { PostSummary } from "@/types/post";
import { ContentListCanvas } from "./ContentListCanvas";
import { TYPES } from "./form.fixtures";
import { CONTENT_LIST_STORAGE_KEY } from "./listParams";

const mockRouter = {
  push: vi.fn(),
  replace: vi.fn(),
};

let currentSearchParams = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => mockRouter,
  usePathname: () => "/content",
  useSearchParams: () => currentSearchParams,
}));

const mockApi = vi.hoisted(() => ({
  getContentTypes: vi.fn(),
  listPosts: vi.fn(),
}));

vi.mock("./api", () => mockApi);

const samplePost: PostSummary = {
  id: "post-1",
  type: "news",
  title: "Launch Announcement",
  slug: "launch-announcement",
  excerpt: "Exciting news about our launch.",
  status: "PUBLISHED",
  publishedAt: "2026-11-01T10:00:00Z",
  updatedAt: "2026-11-02T12:00:00Z",
};

const samplePage: Page<PostSummary> = {
  items: [samplePost],
  meta: {
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  },
};

describe("ContentListCanvas", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    currentSearchParams = new URLSearchParams();
    mockApi.getContentTypes.mockResolvedValue(TYPES);
    mockApi.listPosts.mockResolvedValue(samplePage);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders content list and calls listPosts with defaults when no searchParams", async () => {
    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(screen.getByText("Launch Announcement")).toBeTruthy();
    });

    expect(mockApi.listPosts).toHaveBeenCalledWith(
      expect.objectContaining({
        type: undefined,
        status: undefined,
        q: "",
        sort: "updatedAt",
        direction: "desc",
        page: 0,
        size: 10,
      }),
    );
  });

  it("initializes filters and search from URL searchParams", async () => {
    currentSearchParams = new URLSearchParams("type=news&status=DRAFT&q=audit&page=2");

    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(mockApi.listPosts).toHaveBeenCalledWith(
        expect.objectContaining({
          type: "news",
          status: "DRAFT",
          q: "audit",
          page: 2,
        }),
      );
    });

    const searchInput = screen.getByLabelText("Search content") as HTMLInputElement;
    expect(searchInput.value).toBe("audit");

    const typeSelect = screen.getByLabelText("Filter by type") as HTMLSelectElement;
    expect(typeSelect.value).toBe("news");

    const statusSelect = screen.getByLabelText("Filter by status") as HTMLSelectElement;
    expect(statusSelect.value).toBe("DRAFT");
  });

  it("updates URL and resets page when type filter is changed", async () => {
    currentSearchParams = new URLSearchParams("page=2");
    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(screen.getByLabelText("Filter by type")).toBeTruthy();
    });

    const typeSelect = screen.getByLabelText("Filter by type");
    fireEvent.change(typeSelect, { target: { value: "career" } });

    expect(mockRouter.replace).toHaveBeenCalledWith("/content?type=career", {
      scroll: false,
    });
  });

  it("updates URL and resets page when status filter is changed", async () => {
    currentSearchParams = new URLSearchParams("type=news&page=1");
    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(screen.getByLabelText("Filter by status")).toBeTruthy();
    });

    const statusSelect = screen.getByLabelText("Filter by status");
    fireEvent.change(statusSelect, { target: { value: "ARCHIVED" } });

    expect(mockRouter.replace).toHaveBeenCalledWith(
      "/content?type=news&status=ARCHIVED",
      { scroll: false },
    );
  });

  it("updates URL with debounced search query and resets page", async () => {
    render(<ContentListCanvas />);

    const searchInput = screen.getByLabelText("Search content") as HTMLInputElement;
    fireEvent.change(searchInput, { target: { value: "changelog" } });
    expect(searchInput.value).toBe("changelog");

    await waitFor(
      () => {
        expect(mockRouter.replace).toHaveBeenCalledWith("/content?q=changelog", {
          scroll: false,
        });
      },
      { timeout: 1500 },
    );
  });

  it("updates URL when a table column header is sorted", async () => {
    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(screen.getByText("Launch Announcement")).toBeTruthy();
    });

    const titleHeader = screen.getByRole("button", { name: /Title/i });
    fireEvent.click(titleHeader);

    expect(mockRouter.replace).toHaveBeenCalledWith(
      "/content?sort=title&direction=asc",
      { scroll: false },
    );
  });

  it("persists active searchParams to sessionStorage", async () => {
    currentSearchParams = new URLSearchParams("type=news&status=PUBLISHED");
    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(sessionStorage.getItem(CONTENT_LIST_STORAGE_KEY)).toBe(
        "type=news&status=PUBLISHED",
      );
    });
  });

  it("restores active query from sessionStorage when navigating to /content with no searchParams", async () => {
    sessionStorage.setItem(CONTENT_LIST_STORAGE_KEY, "type=career&status=DRAFT");
    currentSearchParams = new URLSearchParams("");

    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(mockRouter.replace).toHaveBeenCalledWith(
        "/content?type=career&status=DRAFT",
        { scroll: false },
      );
    });
  });

  it("clears filters, resets URL, and removes query from sessionStorage when 'Clear filters' is clicked", async () => {
    sessionStorage.setItem(CONTENT_LIST_STORAGE_KEY, "type=news&status=DRAFT");
    currentSearchParams = new URLSearchParams("type=news&status=DRAFT");

    render(<ContentListCanvas />);

    await waitFor(() => {
      expect(screen.getByText("Clear filters")).toBeTruthy();
    });

    const clearButton = screen.getByText("Clear filters");
    fireEvent.click(clearButton);

    expect(mockRouter.replace).toHaveBeenCalledWith("/content", { scroll: false });
    expect(sessionStorage.getItem(CONTENT_LIST_STORAGE_KEY)).toBeNull();
  });
});
