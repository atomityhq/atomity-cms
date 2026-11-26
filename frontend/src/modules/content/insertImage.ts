import type { Editor } from "@tiptap/core";
import { Selection } from "@tiptap/pm/state";

export interface ImageAttrs {
  src: string;
  alt: string;
  mediaId: string;
}

/**
 * Inserts an image and moves the cursor to a safe text position after it.
 *
 * Inserting an image leaves it selected as a node. Without moving the cursor, the next
 * keystroke or the next inserted image replaces the one that was just added.
 */
export function insertImage(editor: Editor, attrs: ImageAttrs): void {
  editor.chain().focus().insertContent({ type: "image", attrs }).run();
  // Selection.near with bias 1 looks forward, so the cursor lands in the text after the image.
  editor.commands.command(({ tr, dispatch }) => {
    if (dispatch) tr.setSelection(Selection.near(tr.doc.resolve(tr.selection.to), 1));
    return true;
  });
}
