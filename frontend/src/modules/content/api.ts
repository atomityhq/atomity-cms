"use client";

import { api, apiJson, apiRequest, query } from "@/lib/api-client";
import type { Page } from "@/types/api";
import type { ContentTypeDefinition, Post, PostInput, PostListParams, PostSummary } from "@/types/post";

/* Content API — thin wrappers over the backend's /api/v1/content-types and /api/v1/posts endpoints. */

const DEFAULT_PAGE_SIZE = 10;

/** GET /api/v1/content-types — the types and their fields; the editor is built from this. */
export function getContentTypes(): Promise<ContentTypeDefinition[]> {
  return api("GET", "/content-types");
}

/** GET /api/v1/posts?type&status&q&sort&direction&page&size */
export async function listPosts(params: PostListParams = {}): Promise<Page<PostSummary>> {
  const { type, status, q, sort, direction, page = 0, size = DEFAULT_PAGE_SIZE } = params;
  const result = await apiRequest<PostSummary[]>(
    `/posts${query({ type, status, q: q?.trim(), sort, direction, page, size })}`,
  );
  return {
    items: result.data,
    meta: result.meta ?? { page, size, totalElements: result.data.length, totalPages: 1 },
  };
}

/** GET /api/v1/posts/{id} */
export function getPost(id: string): Promise<Post> {
  return api("GET", `/posts/${id}`);
}

/** POST /api/v1/posts — new items always start as drafts. `input.type` is required. */
export function createPost(input: PostInput): Promise<Post> {
  return apiJson("POST", "/posts", input);
}

/**
 * PUT /api/v1/posts/{id}
 * `input.version` is the one the editor loaded; a mismatch means someone else saved in
 * between, and the backend answers 409 VERSION_CONFLICT rather than overwriting their work.
 */
export function updatePost(id: string, input: PostInput): Promise<Post> {
  return apiJson("PUT", `/posts/${id}`, input);
}

/** POST /api/v1/posts/{id}/publish — 422 NOT_PUBLISHABLE while a title, content or required field is missing. */
export function publishPost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/publish`);
}

/** POST /api/v1/posts/{id}/unpublish — back to draft (also restores archived items). */
export function unpublishPost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/unpublish`);
}

/** POST /api/v1/posts/{id}/archive */
export function archivePost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/archive`);
}

/** DELETE /api/v1/posts/{id} — deletes a draft or archived item; refused (409) while published. */
export async function deletePost(id: string): Promise<void> {
  await api("DELETE", `/posts/${id}`);
}
