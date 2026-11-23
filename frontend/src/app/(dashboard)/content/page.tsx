import type { Metadata } from "next";
import { Suspense } from "react";
import { ContentListCanvas } from "@/modules/content/ContentListCanvas";

export const metadata: Metadata = { title: "Content · Atomity CMS" };

export default function ContentPage() {
  return (
    <Suspense fallback={null}>
      <ContentListCanvas />
    </Suspense>
  );
}
