"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Archive, ArrowLeft, CircleAlert, EllipsisVertical, FileQuestion, RefreshCw, Send, Trash2, Undo2 } from "lucide-react";
import { GuardedLink, useBlockNavigation } from "@/components/shell/NavigationGuard";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { Menu, type MenuItem } from "@/components/ui/Menu";
import { Spinner } from "@/components/ui/Spinner";
import { PostStatusChip } from "@/components/ui/StatusChip";
import { useToast } from "@/components/ui/Toast";
import { useAsync } from "@/hooks/useAsync";
import { useUnsavedChangesWarning } from "@/hooks/useUnsavedChangesWarning";
import { countWords, isDocEmpty } from "@/lib/content";
import { timeAgo } from "@/lib/format";
import { ApiError } from "@/types/api";
import { POST_LIMITS, type ContentTypeDefinition, type Post } from "@/types/post";
import { archivePost, createPost, deletePost, getContentTypes, getPost, publishPost, unpublishPost, updatePost } from "./api";
import { describeError, type Failure } from "./errors";
import { EditorSidebar, type EditorMeta } from "./EditorSidebar";
import {
  changeType,
  describePublishProblems,
  emptyForm,
  findType,
  formAfterSave,
  formFromPost,
  hasErrors,
  isDirty,
  publishProblems,
  setField,
  slugFollowsTitle,
  snapshotOf,
  toRequest,
  validateForm,
  withTitle,
  type EditorForm,
  type FormFieldValue,
} from "./form";
import { RichTextEditor } from "./RichTextEditor";
import {
  readRecoveryDraft,
  removeRecoveryDraft,
  writeRecoveryDraft,
  type RecoveryDraft,
} from "./recoveryDraft";

export interface ContentEditorCanvasProps {
  /** Present when editing an existing item. */
  contentId?: string;
  /** The type chosen on the list screen (`?type=`), for a new item. Unknown or missing falls back to the first type. */
  initialType?: string;
}
function PostActionsMenu({
  canArchive,
  onArchive,
  canDelete,
  onDelete,
}: {
  canArchive: boolean;
  onArchive: () => void;
  canDelete: boolean;
  onDelete: () => void;
}) {
  const items: (MenuItem | "separator")[] = [];
  if (canArchive) {
    items.push({
      label: "Archive",
      icon: Archive,
      onSelect: onArchive,
    });
  }
  if (canDelete) {
    if (items.length > 0) items.push("separator");
    items.push({
      label: "Delete",
      icon: Trash2,
      danger: true,
      onSelect: onDelete,
    });
  }
  if (items.length === 0) return null;

  return (
    <Menu
      items={items}
      trigger={(props) => (
        <button
          type="button"
          {...props}
          aria-label="More actions"
          className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full text-muted hover:bg-hover hover:text-ink"
        >
          <EllipsisVertical size={16} />
        </button>
      )}
    />
  );
}

/** Route entry: loads the content types and the item (or starts a blank one), then hands over to the editor. */
export function ContentEditorCanvas({ contentId, initialType }: ContentEditorCanvasProps) {
  const types = useAsync(getContentTypes, []);
  const post = useAsync(() => (contentId ? getPost(contentId) : Promise.resolve(null)), [contentId]);

  const error = post.error ?? types.error;
  if (error) {
    const notFound = post.error instanceof ApiError && post.error.status === 404;
    return (
      <div className="mx-auto max-w-[720px] p-[var(--page-pad)]">
        {notFound ? (
          <EmptyState
            icon={FileQuestion}
            title="Content not found"
            description="It may have been removed, or the link is wrong."
            action={
              <Link href="/content" className="btn btn-primary btn-sm">
                Back to content
              </Link>
            }
          />
        ) : (
          <ErrorState
            title="Couldn't load the editor"
            error={error}
            onRetry={() => {
              post.reload();
              types.reload();
            }}
          />
        )}
      </div>
    );
  }
  if (post.loading || post.data === undefined || !types.data) return <EditorSkeleton />;

  const startTypeKey = post.data?.type ?? (findType(types.data, initialType) ?? types.data[0])?.key;
  if (!startTypeKey) {
    return (
      <div className="mx-auto max-w-[720px] p-[var(--page-pad)]">
        <EmptyState icon={FileQuestion} title="No content types" description="The server didn't list any content types to create." />
      </div>
    );
  }

  // Keyed so opening another item (or reloading after a conflict) starts from a clean editor state.
  return (
    <ContentEditor
      key={post.data?.id ?? "new"}
      contentId={post.data?.id ?? contentId}
      initial={post.data}
      types={types.data}
      startTypeKey={startTypeKey}
      onReload={post.reload}
    />
  );
}

type Busy = "save" | "publish" | "status" | null;

interface Banner {
  kind: "conflict" | "not-publishable" | "validation";
  message: string;
}

function ContentEditor({
  contentId,
  initial,
  types,
  startTypeKey,
  onReload,
}: {
  contentId?: string;
  initial: Post | null;
  types: ContentTypeDefinition[];
  startTypeKey: string;
  onReload: () => void;
}) {
  const router = useRouter();
  const toast = useToast();

  const savingRef = useRef(false);

  const initialForm = initial ? formFromPost(initial, findType(types, initial.type)) : emptyForm(startTypeKey);
  const [form, setForm] = useState<EditorForm>(initialForm);
  const [savedSnapshot, setSavedSnapshot] = useState(() => snapshotOf(initialForm));
  const [post, setPost] = useState<Post | null>(initial);
  const [busy, setBusy] = useState<Busy>(null);
  const [showErrors, setShowErrors] = useState(false);
  const [publishTried, setPublishTried] = useState(false);
  const [banner, setBanner] = useState<Banner | null>(null);
  const [slugError, setSlugError] = useState<string | undefined>();
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // The slug follows the title until someone edits it by hand.
  const slugFollows = useRef(slugFollowsTitle(initial));

  const type = findType(types, form.type);
  const status = post?.status ?? "DRAFT";

  const dirty = isDirty(form, savedSnapshot);
  useUnsavedChangesWarning(dirty);
  useBlockNavigation(dirty);

  const recoveryId = contentId ?? post?.id;
  const [recoveryDraft, setRecoveryDraft] = useState<RecoveryDraft | null>(() =>
    readRecoveryDraft(recoveryId, form.type),
  );

  useEffect(() => {
    if (dirty && !recoveryDraft) {
      writeRecoveryDraft(recoveryId, form, post?.version ?? null);
    }
  }, [recoveryId, dirty, form, post?.version, recoveryDraft]);

  const restoreRecoveryDraft = () => {
    if (!recoveryDraft) return;
    setForm(recoveryDraft.form);
    if (post && recoveryDraft.baseVersion !== null) {
      setPost({ ...post, version: recoveryDraft.baseVersion });
    }
    setRecoveryDraft(null);
  };

  const discardRecoveryDraft = () => {
    removeRecoveryDraft(recoveryId);
    setRecoveryDraft(null);
  };

  const errors = useMemo(() => validateForm(form, type), [form, type]);
  const problems = useMemo(() => publishProblems(form, type), [form, type]);
  const wordCount = useMemo(() => countWords(form.content), [form.content]);
  // Published items must stay complete, so their gaps are always shown; for the rest, once a publish was tried.
  const showPublishProblems = publishTried || status === "PUBLISHED";
  const conflict = banner?.kind === "conflict";

  const locked = busy !== null || conflict;
  const saveDisabled = locked || (!dirty && Boolean(post));

  const meta: EditorMeta = {
    status,
    publishedAt: post?.publishedAt ?? null,
    updatedAt: post?.updatedAt ?? null,
  };

  const change = useCallback((patch: Partial<EditorForm>) => setForm((current) => ({ ...current, ...patch })), []);
  const onContentChange = useCallback((content: EditorForm["content"]) => change({ content }), [change]);

  /** Shows a failed request: conflicts, unpublishable and invalid content as a banner, the rest as a toast. */
  const reportFailure = (failure: Failure) => {
    if (failure.kind === "conflict" || failure.kind === "not-publishable" || failure.kind === "validation") {
      setBanner({ kind: failure.kind, message: failure.message });
    } else {
      toast.error(failure.message);
    }
    if (failure.kind === "slug-taken") setSlugError(failure.message);
    if (failure.kind === "not-publishable") setPublishTried(true);
  };

  /** Saves the form; resolves to the saved item, or null if it couldn't be saved. */
  const save = async (options: { quiet?: boolean; stayOnNew?: boolean } = {}): Promise<Post | null> => {
    // Do not save during an active request, another editor action,
    // or an unresolved version conflict.
    if (savingRef.current || saveDisabled) {
      return null;
    }

    if (!type) {
      toast.error("This content type is no longer available, so it can't be saved.");
      return null;
    }
    if (hasErrors(errors)) {
      setShowErrors(true);
      toast.error("Fix the highlighted fields before saving.");
      return null;
    }
    // Acquire the lock synchronously.
    savingRef.current = true;
    const sent = form;
    setBusy("save");
    try {
      const saved = post
        ? await updatePost(post.id, toRequest(sent, type, post.version))
        : await createPost(toRequest(sent, type));
      setPost(saved);
      // The server now holds what was sent (with the slug it may have filled in). Edits made
      // during the request stay on screen and still count as unsaved.
      setSavedSnapshot(snapshotOf({ ...sent, slug: saved.slug }));
      setForm((current) => formAfterSave(current, sent, saved.slug));
      setShowErrors(false);
      setBanner(null);
      setSlugError(undefined);
      if (!recoveryDraft) {
        removeRecoveryDraft(recoveryId);
      }
      if (!options.quiet) toast.success(post ? "Changes saved." : "Draft created.");
      if (!post && !options.stayOnNew) router.replace(`/content/${saved.id}`);
      return saved;
    } catch (error) {
      reportFailure(describeError(error, type));
      return null;
    } finally {
      setBusy(null);
      // Release the save lock when the request finishes.
      savingRef.current = false;
    }
  };

  // Latest save() for the keyboard shortcut, without re-binding the listener every render.
  const saveRef = useRef(save);
  useEffect(() => {
    saveRef.current = save;
  });
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        void saveRef.current();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  /** Status changes act on the saved item, so unsaved edits (or a new item) are saved first. */
  const changeStatus = async (action: (id: string) => Promise<Post>, success: string, kind: Busy) => {
    const wasNew = !post;
    const current = dirty || wasNew ? await save({ quiet: true, stayOnNew: true }) : post;
    if (!current) return;
    setBusy(kind);
    let failure: Failure | null = null;
    try {
      const updated = await action(current.id);
      setPost(updated);
      setBanner(null);
      toast.success(success);
    } catch (error) {
      failure = describeError(error, type);
      reportFailure(failure);
    } finally {
      setBusy(null);
    }
    if (wasNew) {
      // Only now leave /content/new, so the page that loads there already sees the new status.
      // The page remounts, which drops the banner, so a failure is also said in a toast.
      if (failure) toast.error(failure.message);
      router.replace(`/content/${current.id}`);
    }
  };

  const publish = () => {
    if (problems.length > 0) {
      // Same checks as the server; it would answer 422 NOT_PUBLISHABLE, so don't make the round trip.
      setPublishTried(true);
      setBanner({ kind: "not-publishable", message: describePublishProblems(problems) });
      return;
    }
    void changeStatus(publishPost, "Published.", "publish");
  };

  const confirmDelete = async () => {
    if (!post) return;
    try {
      await deletePost(post.id);
      removeRecoveryDraft(post.id);
      toast.success("Deleted.");
      router.replace("/content");
    } catch (error) {
      toast.error(describeError(error, type).message);
      throw error;
    }
  };

  const saveState = busy === "save" ? "Saving…" : dirty ? "Unsaved changes" : post ? `Saved ${timeAgo(post.updatedAt)}` : "Not saved yet";
  const titleProblem = showPublishProblems ? problems.find((problem) => problem.target === "title") : undefined;
  const contentProblem = showPublishProblems ? problems.find((problem) => problem.target === "content") : undefined;
  const titleError = errors.title ?? (titleProblem ? "Add a title to publish." : undefined);

  return (
    <div className="flex flex-col">
      {/* Action bar */}
      <div className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-page/95 px-[var(--page-pad)] py-2.5 backdrop-blur">
        <GuardedLink href="/content" className="flex items-center gap-1.5 text-[12px] font-semibold text-secondary hover:text-ink">
          <ArrowLeft size={14} aria-hidden /> Content
        </GuardedLink>
        <span aria-hidden className="h-4 w-px bg-line" />
        <PostStatusChip status={status} />
        <span className={dirty ? "text-[12px] font-semibold text-warning" : "text-[12px] text-muted"} aria-live="polite">
          {saveState}
        </span>

        <div className="ml-auto flex items-center gap-2">
          {status === "PUBLISHED" ? (
            <>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={locked}
                onClick={() => void changeStatus(unpublishPost, "Moved back to drafts.", "status")}
              >
                {busy === "status" ? <Spinner size={12} /> : <Undo2 size={13} aria-hidden />}
                Unpublish
              </button>
              <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={saveDisabled}>
                {busy === "save" && <Spinner size={12} />}
                Update
              </button>
            </>
          ) : (
            <>
              <button type="button" className="btn btn-secondary" onClick={() => void save()} disabled={saveDisabled}>
                {busy === "save" && <Spinner size={12} />}
                {status === "ARCHIVED" ? "Save" : "Save draft"}
              </button>
              {status === "ARCHIVED" ? (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={locked}
                  onClick={() => void changeStatus(unpublishPost, "Restored to drafts.", "status")}
                >
                  {busy === "status" ? <Spinner size={12} /> : <Undo2 size={13} aria-hidden />}
                  Restore
                </button>
              ) : (
                // aria-disabled rather than disabled: a click on an incomplete item explains what is missing.
                <button
                  type="button"
                  className="btn btn-green"
                  disabled={locked}
                  aria-disabled={problems.length > 0}
                  title={problems.length > 0 ? describePublishProblems(problems) : undefined}
                  style={problems.length > 0 ? { opacity: 0.55 } : undefined}
                  onClick={publish}
                >
                  {busy === "publish" ? <Spinner size={12} /> : <Send size={13} aria-hidden />}
                  Publish
                </button>
              )}
            </>
          )}
          {post && (
            <PostActionsMenu
              canArchive={status !== "ARCHIVED"}
              onArchive={() =>
                void changeStatus(archivePost, "Archived.", "status")
              }
              canDelete={status !== "PUBLISHED"}
              onDelete={() => setConfirmingDelete(true)}
            />
          )}
        </div>
      </div>

      {banner && (
        <div className="mx-auto w-full max-w-[1280px] px-[var(--page-pad)] pt-4">
          <StatusBanner banner={banner} onReload={onReload} onDismiss={() => setBanner(null)} />
        </div>
      )}

      {recoveryDraft && (
        <div className="mx-auto w-full max-w-[1280px] px-[var(--page-pad)] pt-4">
          <div className="flex items-center gap-3 rounded-md border border-warning bg-warning-bg px-4 py-3">
            <CircleAlert size={16} className="mt-px shrink-0 text-warning" aria-hidden />
            <p className="flex-1 text-[12.5px] text-secondary">
              {recoveryDraft.baseVersion !== (initial?.version ?? null)
                ? "This recovery draft is based on an older server version. Restoring it may require resolving a version conflict. "
                : "An unsaved recovery draft is available. "}
              Saved locally {new Date(recoveryDraft.savedAt).toLocaleTimeString()}.
            </p>
            <button type="button" className="btn btn-secondary btn-sm" onClick={discardRecoveryDraft}>
              Discard
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={restoreRecoveryDraft}>
              Restore draft
            </button>
          </div>
        </div>
      )}

      {/* Writing area + sidebar */}
      <div className="mx-auto grid w-full max-w-[1280px] grid-cols-1 gap-5 p-[var(--page-pad)] lg:grid-cols-[minmax(0,1fr)_var(--editor-sidebar-w)]">
        <div className="flex min-w-0 flex-col gap-3">
          <div>
            <label htmlFor="content-title" className="sr-only">
              Title
            </label>
            <textarea
              id="content-title"
              rows={1}
              value={form.title}
              onChange={(e) => {
                const title = e.target.value.replace(/\n/g, " ");
                setForm((current) => withTitle(current, title, slugFollows.current));
              }}
              placeholder="Title"
              aria-invalid={Boolean(titleError)}
              className="w-full resize-none bg-transparent text-[30px] leading-tight font-bold tracking-[-0.02em] outline-none [field-sizing:content] placeholder:text-[var(--atomity-gray-300)]"
            />
            <div className="flex justify-between text-[11px]">
              <span className="text-error-text" role={titleError ? "alert" : undefined}>
                {showErrors || titleProblem ? titleError : ""}
              </span>
              {form.title.length > POST_LIMITS.title * 0.8 && (
                <span className={form.title.length > POST_LIMITS.title ? "font-bold text-error" : "text-muted"}>
                  {form.title.length}/{POST_LIMITS.title}
                </span>
              )}
            </div>
          </div>
          <RichTextEditor value={form.content} onChange={onContentChange} />
          {contentProblem && isDocEmpty(form.content) && (
            <p className="text-[11px] text-error-text" role="alert">
              Add some content to publish.
            </p>
          )}
        </div>

        <aside aria-label="Content settings" className="min-w-0">
          <EditorSidebar
            form={form}
            type={type}
            types={types}
            typeEditable={!post}
            errors={showErrors ? errors : { fields: {} }}
            slugError={slugError}
            problems={problems}
            showPublishProblems={showPublishProblems}
            meta={meta}
            wordCount={wordCount}
            onTypeChange={(key) => {
              const next = findType(types, key);
              if (next) setForm((current) => changeType(current, next));
            }}
            onExcerptChange={(excerpt) => change({ excerpt })}
            onSlugChange={(slug) => {
              slugFollows.current = false;
              setSlugError(undefined);
              change({ slug });
            }}
            onSlugFromTitle={(slug) => {
              slugFollows.current = true;
              setSlugError(undefined);
              change({ slug });
            }}
            onFieldChange={(name: string, value: FormFieldValue | undefined) => setForm((current) => setField(current, name, value))}
          />
        </aside>
      </div>

      {confirmingDelete && post && (
        <ConfirmDialog
          title="Delete content?"
          message={
            <>
              Permanently delete <strong>{form.title.trim() || "Untitled"}</strong>? This cannot be undone.
            </>
          }
          confirmLabel="Delete"
          danger
          onConfirm={confirmDelete}
          onClose={() => setConfirmingDelete(false)}
        />
      )}
    </div>
  );
}

function StatusBanner({ banner, onReload, onDismiss }: { banner: Banner; onReload: () => void; onDismiss: () => void }) {
  const conflict = banner.kind === "conflict";
  return (
    <div
      role="alert"
      className={
        conflict || banner.kind === "validation"
          ? "flex items-start gap-3 rounded-md border border-[var(--color-error-border)] bg-error-bg px-3.5 py-3"
          : "flex items-start gap-3 rounded-md border border-warning bg-warning-bg px-3.5 py-3"
      }
    >
      <CircleAlert size={16} className={conflict || banner.kind === "validation" ? "mt-px shrink-0 text-error" : "mt-px shrink-0 text-warning"} aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="text-[12.5px] font-bold">
          {conflict ? "Newer version available" : banner.kind === "not-publishable" ? "Can't publish yet" : "Can't save"}
        </div>
        <p className="text-[12.5px] text-secondary">{banner.message}</p>
      </div>
      {conflict ? (
        <button type="button" className="btn btn-secondary btn-sm shrink-0" onClick={onReload}>
          <RefreshCw size={12} aria-hidden /> Reload latest
        </button>
      ) : (
        <button type="button" className="btn btn-ghost btn-sm shrink-0" onClick={onDismiss}>
          Dismiss
        </button>
      )}
    </div>
  );
}

function EditorSkeleton() {
  return (
    <div className="flex flex-col" aria-busy="true" aria-label="Loading content">
      <div className="h-[53px] border-b border-line" />
      <div className="mx-auto grid w-full max-w-[1280px] grid-cols-1 gap-5 p-[var(--page-pad)] lg:grid-cols-[minmax(0,1fr)_var(--editor-sidebar-w)]">
        <div className="flex flex-col gap-4">
          <div className="skeleton h-9 w-2/3" />
          <div className="skeleton h-[460px] w-full !rounded-lg" />
        </div>
        <div className="flex flex-col gap-3">
          {[120, 180, 160, 140].map((h) => (
            <div key={h} className="skeleton w-full !rounded-lg" style={{ height: h }} />
          ))}
        </div>
      </div>
    </div>
  );
}
