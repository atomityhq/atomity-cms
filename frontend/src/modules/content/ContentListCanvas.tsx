"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { FileText, Plus, SearchX } from "lucide-react";
import { DataTable, type DataTableColumn } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { Modal } from "@/components/ui/Modal";
import { PageHeader } from "@/components/ui/PageHeader";
import { Pagination } from "@/components/ui/Pagination";
import { SearchInput } from "@/components/ui/SearchInput";
import { Select } from "@/components/ui/Select";
import { PostStatusChip } from "@/components/ui/StatusChip";
import { TypeChip } from "@/components/ui/TypeChip";
import { useAsync } from "@/hooks/useAsync";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { formatDateTime, timeAgo } from "@/lib/format";
import { POST_STATUSES, type ContentTypeDefinition, type PostSortKey, type PostStatus, type PostSummary } from "@/types/post";
import { getContentTypes, listPosts } from "./api";
import { describeDestination, typeLabel } from "./form";
import { CONTENT_LIST_STORAGE_KEY, buildContentListUrl, parseListParams } from "./listParams";

const PAGE_SIZE = 10;

const STATUS_LABELS: Record<PostStatus, string> = { DRAFT: "Drafts", PUBLISHED: "Published", ARCHIVED: "Archived" };

export function ContentListCanvas() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const activeState = useMemo(() => parseListParams(searchParams), [searchParams]);

  const [prevParamQ, setPrevParamQ] = useState(activeState.q);
  const [query, setQuery] = useState(activeState.q);
  const [choosingType, setChoosingType] = useState(false);

  // Synchronize search input when URL query param changes (e.g. back/forward, clear)
  if (prevParamQ !== activeState.q) {
    setPrevParamQ(activeState.q);
    setQuery(activeState.q);
  }

  const debouncedQuery = useDebouncedValue(query);

  // Restore saved query from sessionStorage when visiting /content with no search params
  useEffect(() => {
    if (!searchParams.toString()) {
      try {
        const saved = sessionStorage.getItem(CONTENT_LIST_STORAGE_KEY);
        if (saved) {
          router.replace(`${pathname}?${saved}`, { scroll: false });
        }
      } catch {
        // sessionStorage may fail in private browsing or restricted environments
      }
    }
  }, [searchParams, pathname, router]);

  // Persist non-empty URL query to sessionStorage
  useEffect(() => {
    const qs = searchParams.toString();
    if (qs) {
      try {
        sessionStorage.setItem(CONTENT_LIST_STORAGE_KEY, qs);
      } catch {
        // Ignore storage errors
      }
    }
  }, [searchParams]);

  const updateListUrl = useCallback(
    (updates: Partial<typeof activeState>) => {
      const targetUrl = buildContentListUrl(pathname, updates, activeState);
      router.replace(targetUrl, { scroll: false });
    },
    [pathname, activeState, router],
  );

  // Update URL search params when debounced search query changes
  useEffect(() => {
    if (debouncedQuery.trim() !== activeState.q.trim()) {
      updateListUrl({ q: debouncedQuery, page: 0 });
    }
  }, [debouncedQuery, activeState.q, updateListUrl]);

  const types = useAsync(getContentTypes, []);
  const posts = useAsync(
    () =>
      listPosts({
        type: activeState.type || undefined,
        status: activeState.status || undefined,
        q: debouncedQuery,
        sort: activeState.sort.key,
        direction: activeState.sort.direction,
        page: activeState.page,
        size: PAGE_SIZE,
      }),
    [
      activeState.type,
      activeState.status,
      debouncedQuery,
      activeState.sort.key,
      activeState.sort.direction,
      activeState.page,
    ],
  );

  const hasFilters = Boolean(activeState.type || activeState.status || query);
  const rows = posts.data?.items ?? [];
  const isFirstLoad = posts.loading && !posts.data;

  const columns: DataTableColumn<PostSummary>[] = [
    {
      key: "title",
      header: "Title",
      sortable: true,
      render: (item) => (
        <div className="min-w-0">
          <div className={item.title ? "truncate font-semibold" : "truncate font-semibold text-muted italic"}>{item.title || "Untitled"}</div>
          <div className="truncate font-mono text-[11px] text-muted">/{item.slug}</div>
        </div>
      ),
    },
    {
      key: "type",
      header: "Type",
      width: "150px",
      render: (item) => <TypeChip>{typeLabel(types.data, item.type)}</TypeChip>,
    },
    { key: "status", header: "Status", width: "112px", render: (item) => <PostStatusChip status={item.status} /> },
    {
      key: "updatedAt",
      header: "Updated",
      width: "116px",
      sortable: true,
      render: (item) => (
        <span title={formatDateTime(item.updatedAt)} className="text-secondary">
          {timeAgo(item.updatedAt)}
        </span>
      ),
    },
  ];

  const onSortChange = (key: string) => {
    const direction =
      activeState.sort.key === key && activeState.sort.direction === "desc"
        ? "asc"
        : key === "title"
          ? "asc"
          : "desc";
    updateListUrl({
      sort: { key: key as PostSortKey, direction },
      page: 0,
    });
  };

  const newContentButton = (small = false) => (
    <button type="button" className={small ? "btn btn-primary btn-sm" : "btn btn-primary"} onClick={() => setChoosingType(true)}>
      <Plus size={small ? 13 : 14} aria-hidden />
      New content
    </button>
  );

  return (
    <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-5 p-[var(--page-pad)]">
      <PageHeader title="Content" description="News, careers, documentation and knowledge-base articles." actions={newContentButton()} />

      <div className="rounded-lg border-[1.5px] border-line bg-card shadow-card">
        <div className="flex flex-wrap items-center gap-2 border-b border-line px-3.5 py-3">
          <div className="min-w-[220px] flex-1">
            <SearchInput
              label="Search content"
              placeholder="Search title, slug or content"
              value={query}
              onChange={(value) => setQuery(value)}
            />
          </div>
          <Select
            aria-label="Filter by type"
            value={activeState.type}
            onChange={(e) => updateListUrl({ type: e.target.value, page: 0 })}
            className="w-[170px]"
            options={[{ value: "", label: "All types" }, ...(types.data ?? []).map((t) => ({ value: t.key, label: t.displayName }))]}
          />
          <Select
            aria-label="Filter by status"
            value={activeState.status}
            onChange={(e) => updateListUrl({ status: e.target.value as PostStatus | "", page: 0 })}
            className="w-[150px]"
            options={[{ value: "", label: "All statuses" }, ...POST_STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))]}
          />
          {hasFilters && (
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                try {
                  sessionStorage.removeItem(CONTENT_LIST_STORAGE_KEY);
                } catch {}
                setQuery("");
                router.replace(pathname, { scroll: false });
              }}
            >
              Clear filters
            </button>
          )}
        </div>

        <div className="px-1">
          {posts.error ? (
            <div className="p-4">
              <ErrorState title="Couldn't load content" error={posts.error} onRetry={posts.reload} />
            </div>
          ) : !isFirstLoad && rows.length === 0 ? (
            <div className="p-4">
              {hasFilters ? (
                <EmptyState icon={SearchX} title="Nothing matches" description="Try a different search, type or status." />
              ) : (
                <EmptyState
                  icon={FileText}
                  title="No content yet"
                  description="Your first item is one click away."
                  action={newContentButton(true)}
                />
              )}
            </div>
          ) : (
            <DataTable
              caption="Content"
              columns={columns}
              rows={rows}
              rowKey={(item) => item.id}
              onRowClick={(item) => router.push(`/content/${item.id}`)}
              sort={activeState.sort}
              onSortChange={onSortChange}
              loading={isFirstLoad}
            />
          )}
        </div>

        {posts.data && posts.data.meta.totalElements > 0 && (
          <div className="border-t border-line px-3.5 py-2">
            <Pagination
              meta={posts.data.meta}
              onPageChange={(nextPage) => updateListUrl({ page: nextPage })}
              noun="items"
            />
          </div>
        )}
      </div>

      {choosingType && (
        <TypePickerModal
          types={types.data}
          error={types.error}
          onRetry={types.reload}
          onClose={() => setChoosingType(false)}
          onPick={(key) => router.push(`/content/new?type=${encodeURIComponent(key)}`)}
        />
      )}
    </div>
  );
}

/** First step of "New content": what kind of item is it? The editor then builds its form from that type. */
function TypePickerModal({
  types,
  error,
  onRetry,
  onClose,
  onPick,
}: {
  types: ContentTypeDefinition[] | undefined;
  error: Error | undefined;
  onRetry: () => void;
  onClose: () => void;
  onPick: (key: string) => void;
}) {
  return (
    <Modal
      title="New content"
      description="What are you writing?"
      onClose={onClose}
      maxWidth={520}
      footer={
        <button type="button" className="btn btn-secondary" onClick={onClose}>
          Cancel
        </button>
      }
    >
      {error ? (
        <ErrorState title="Couldn't load the content types" error={error} onRetry={onRetry} />
      ) : !types ? (
        <div className="flex flex-col gap-2" aria-busy="true">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="skeleton h-[58px] !rounded-md" />
          ))}
        </div>
      ) : (
        <ul className="flex flex-col gap-2">
          {types.map((type) => (
            <li key={type.key}>
              <button
                type="button"
                onClick={() => onPick(type.key)}
                className="flex w-full cursor-pointer flex-col gap-0.5 rounded-md border-[1.5px] border-line px-3.5 py-2.5 text-left transition-colors hover:border-line-strong hover:bg-subtle"
              >
                <span className="text-[13px] font-bold">{type.displayName}</span>
                <span className="text-[12px] text-secondary">{type.description}</span>
                <span className="font-mono text-[10.5px] text-muted">{describeDestination(type)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
