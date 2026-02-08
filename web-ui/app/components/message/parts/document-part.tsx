import { File, FileText } from "lucide-react";

import { resolveFileUrl } from "~/lib/files";

interface DocumentPartProps {
  url: string;
  fileName: string;
  mime: string;
}

function getDocumentIcon(mime: string) {
  if (mime === "application/pdf") {
    return <FileText className="h-4 w-4" />;
  }
  if (mime === "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
    return <FileText className="h-4 w-4" />;
  }
  return <File className="h-4 w-4" />;
}

export function DocumentPart({ url, fileName, mime }: DocumentPartProps) {
  if (!url) return null;

  const documentUrl = resolveFileUrl(url);

  return (
    <a
      className="my-2 inline-flex max-w-full items-center gap-2 rounded-full border border-muted bg-card px-3 py-1.5 text-sm hover:bg-muted/40"
      href={documentUrl}
      rel="noreferrer"
      target="_blank"
    >
      {getDocumentIcon(mime)}
      <span className="max-w-[320px] truncate">{fileName}</span>
    </a>
  );
}
