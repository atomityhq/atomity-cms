// @vitest-environment jsdom
import { Editor } from "@tiptap/core";
import { afterEach, describe, expect, it } from "vitest";
import { editorExtensions } from "./extensions";
import { insertImage } from "./insertImage";

const attrs = (id: string) => ({ src: `/api/v1/media/${id}/file`, alt: id, mediaId: id });

function mediaIds(editor: Editor): string[] {
  const ids: string[] = [];
  editor.state.doc.descendants((node) => {
    if (node.type.name === "image") ids.push(node.attrs.mediaId as string);
  });
  return ids;
}

describe("insertImage", () => {
  let editor: Editor;

  afterEach(() => editor?.destroy());

  function createEditor() {
    editor = new Editor({
      extensions: editorExtensions,
      content: { type: "doc", content: [{ type: "paragraph" }] },
    });
  }

  it("keeps the image when the user types right after inserting it", () => {
    createEditor();
    insertImage(editor, attrs("a"));
    editor.commands.insertContent("hello");

    expect(mediaIds(editor)).toEqual(["a"]);
    expect(editor.getText()).toContain("hello");
  });

  it("keeps every image when two are inserted one after the other", () => {
    createEditor();
    insertImage(editor, attrs("a"));
    insertImage(editor, attrs("b"));

    expect(mediaIds(editor)).toEqual(["a", "b"]);
  });

  it("keeps all images and the typed text across image, text, image", () => {
    createEditor();
    insertImage(editor, attrs("a"));
    editor.commands.insertContent("between");
    insertImage(editor, attrs("b"));
    editor.commands.insertContent("end");

    expect(mediaIds(editor)).toEqual(["a", "b"]);
    expect(editor.getText()).toContain("between");
    expect(editor.getText()).toContain("end");
  });

  it("leaves a text cursor, not a selected image, after inserting", () => {
    createEditor();
    insertImage(editor, attrs("a"));

    expect(editor.state.selection.constructor.name).not.toBe("NodeSelection");
    expect(editor.state.selection.empty).toBe(true);
  });
});
