import { POST_STATUSES, type PostSortKey, type PostStatus } from "@/types/post";

export const SORT_KEYS: PostSortKey[] = ["updatedAt", "title", "publishedAt"];

export interface ContentListState {
  type: string;
  status: PostStatus | "";
  q: string;
  sort: {
    key: PostSortKey;
    direction: "asc" | "desc";
  };
  page: number;
}

export const DEFAULT_LIST_STATE: ContentListState = {
  type: "",
  status: "",
  q: "",
  sort: {
    key: "updatedAt",
    direction: "desc",
  },
  page: 0,
};

export const CONTENT_LIST_STORAGE_KEY = "atomity_content_list_query";

/**
 * Parses URL search params into a validated ContentListState.
 */
export function parseListParams(
  searchParams: { get: (key: string) => string | null },
): ContentListState {
  const type = searchParams.get("type") || "";

  const rawStatus = searchParams.get("status") || "";
  const status: PostStatus | "" = POST_STATUSES.includes(rawStatus as PostStatus)
    ? (rawStatus as PostStatus)
    : "";

  const q = searchParams.get("q") || "";

  const rawSort = searchParams.get("sort");
  const sortKey: PostSortKey =
    rawSort && SORT_KEYS.includes(rawSort as PostSortKey)
      ? (rawSort as PostSortKey)
      : "updatedAt";

  const rawDirection = searchParams.get("direction");
  const direction: "asc" | "desc" = rawDirection === "asc" ? "asc" : "desc";

  const rawPage = searchParams.get("page");
  const parsedPage = rawPage ? parseInt(rawPage, 10) : 0;
  const page = Number.isFinite(parsedPage) && parsedPage > 0 ? parsedPage : 0;

  return {
    type,
    status,
    q,
    sort: {
      key: sortKey,
      direction,
    },
    page,
  };
}

/**
 * Serializes ContentListState into URL query string (without leading '?').
 * Omits default values to keep URLs clean.
 */
export function serializeListParams(
  state: Partial<ContentListState>,
  currentState: ContentListState = DEFAULT_LIST_STATE,
): string {
  const merged: ContentListState = {
    ...currentState,
    ...state,
    sort: {
      ...currentState.sort,
      ...(state.sort || {}),
    },
  };

  const params = new URLSearchParams();

  if (merged.type) {
    params.set("type", merged.type);
  }

  if (merged.status) {
    params.set("status", merged.status);
  }

  const trimmedQ = merged.q.trim();
  if (trimmedQ) {
    params.set("q", trimmedQ);
  }

  if (merged.sort.key !== "updatedAt" || merged.sort.direction !== "desc") {
    params.set("sort", merged.sort.key);
    params.set("direction", merged.sort.direction);
  }

  if (merged.page > 0) {
    params.set("page", String(merged.page));
  }

  return params.toString();
}

/**
 * Combines pathname and state into a full URL path.
 */
export function buildContentListUrl(
  pathname: string,
  state: Partial<ContentListState>,
  currentState: ContentListState = DEFAULT_LIST_STATE,
): string {
  const query = serializeListParams(state, currentState);
  return query ? `${pathname}?${query}` : pathname;
}
