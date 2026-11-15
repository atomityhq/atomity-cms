import { describe, expect, it } from "vitest";
import {
  DEFAULT_LIST_STATE,
  buildContentListUrl,
  parseListParams,
  serializeListParams,
} from "./listParams";

describe("listParams", () => {
  describe("parseListParams", () => {
    it("returns default state when params are empty", () => {
      const searchParams = new URLSearchParams("");
      const result = parseListParams(searchParams);
      expect(result).toEqual(DEFAULT_LIST_STATE);
    });

    it("parses valid type and status filters", () => {
      const searchParams = new URLSearchParams("type=news&status=DRAFT");
      const result = parseListParams(searchParams);
      expect(result.type).toBe("news");
      expect(result.status).toBe("DRAFT");
    });

    it("ignores invalid status values", () => {
      const searchParams = new URLSearchParams("status=UNKNOWN_STATUS");
      const result = parseListParams(searchParams);
      expect(result.status).toBe("");
    });

    it("parses search query", () => {
      const searchParams = new URLSearchParams("q=audit");
      const result = parseListParams(searchParams);
      expect(result.q).toBe("audit");
    });

    it("parses custom sort and direction", () => {
      const searchParams = new URLSearchParams("sort=title&direction=asc");
      const result = parseListParams(searchParams);
      expect(result.sort).toEqual({ key: "title", direction: "asc" });
    });

    it("falls back to default sort when sort key is unknown", () => {
      const searchParams = new URLSearchParams("sort=hacked&direction=asc");
      const result = parseListParams(searchParams);
      expect(result.sort.key).toBe("updatedAt");
      expect(result.sort.direction).toBe("asc");
    });

    it("parses page number correctly", () => {
      const searchParams = new URLSearchParams("page=3");
      const result = parseListParams(searchParams);
      expect(result.page).toBe(3);
    });

    it("handles invalid or negative page numbers safely", () => {
      expect(parseListParams(new URLSearchParams("page=-2")).page).toBe(0);
      expect(parseListParams(new URLSearchParams("page=notanumber")).page).toBe(0);
    });
  });

  describe("serializeListParams", () => {
    it("returns empty string for default state", () => {
      const query = serializeListParams(DEFAULT_LIST_STATE);
      expect(query).toBe("");
    });

    it("serializes type and status", () => {
      const query = serializeListParams({ type: "career", status: "PUBLISHED" });
      expect(query).toBe("type=career&status=PUBLISHED");
    });

    it("trims search query and ignores empty search query", () => {
      expect(serializeListParams({ q: "  audit  " })).toBe("q=audit");
      expect(serializeListParams({ q: "    " })).toBe("");
    });

    it("omits default sort and direction", () => {
      const query = serializeListParams({
        sort: { key: "updatedAt", direction: "desc" },
      });
      expect(query).toBe("");
    });

    it("serializes non-default sort and direction", () => {
      const query = serializeListParams({
        sort: { key: "title", direction: "asc" },
      });
      expect(query).toBe("sort=title&direction=asc");
    });

    it("omits page 0 and serializes page > 0", () => {
      expect(serializeListParams({ page: 0 })).toBe("");
      expect(serializeListParams({ page: 2 })).toBe("page=2");
    });
  });

  describe("buildContentListUrl", () => {
    it("returns plain pathname when state matches defaults", () => {
      const url = buildContentListUrl("/content", DEFAULT_LIST_STATE);
      expect(url).toBe("/content");
    });

    it("appends query string when params are present", () => {
      const url = buildContentListUrl("/content", {
        type: "news",
        status: "DRAFT",
        q: "audit",
      });
      expect(url).toBe("/content?type=news&status=DRAFT&q=audit");
    });
  });
});
