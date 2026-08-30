const {
  FILE_VIEW_NAME_ENV_KEY,
  FILE_VIEW_PATH_ENV_KEY,
  FILE_VIEW_READONLY_ENV_KEY,
  FILE_VIEW_RELATIVE_ENV_KEY,
  FILE_VIEW_ROUTE,
  callHistoryTool,
  toErrorText,
  useEnglishLocale,
  callWithTimeout,
} = require("../history_shared.js");

const PAGE_SIZE = 50;

const FILE_LABELS = {
  "book.md": { zh: "书籍信息", en: "Book info" },
  "characters.md": { zh: "人物设定", en: "Characters" },
  "ai-memory.md": { zh: "AI 记忆", en: "AI memory" },
  "catalog.json": { zh: "章节目录", en: "Chapter catalog" },
  "content.md": { zh: "正文快照", en: "Chapter text" },
  "summary.md": { zh: "章节摘要", en: "Chapter summary" },
  "comments.json": { zh: "段评", en: "Comments" },
  "meta.json": { zh: "元数据", en: "Metadata" },
};

function fileNameLabel(name, english) {
  const label = FILE_LABELS[String(name || "")];
  return label ? (english ? label.en : label.zh) : String(name || "");
}

function entryLabel(entry, english) {
  const name = String(entry && (entry.name || entry.fileName) || "");
  const chapterNumber = Number(entry && entry.chapterNumber || 0);
  const chapterTitle = String(entry && (entry.chapterTitle || entry.title) || "").trim();
  const kind = String(entry && entry.kind || "");
  const baseName = fileNameLabel(name, english);
  if (kind === "book") {
    return baseName;
  }
  if (kind === "companion") {
    return english
      ? `Companion memory · ${baseName}`
      : `角色记忆 · ${baseName}`;
  }
  if (kind === "chapter" && chapterNumber > 0) {
    return english
      ? `Ch.${chapterNumber} ${chapterTitle || ""} · ${baseName}`.trim()
      : `第${chapterNumber}章 ${chapterTitle || ""} · ${baseName}`.trim();
  }
  if (kind === "catalog") {
    return baseName;
  }
  return baseName || name;
}

function flattenEntries(payload) {
  const roots = payload && Array.isArray(payload.entries)
    ? payload.entries
    : [];
  const flattened = [];
  roots.forEach((root) => {
    if (root && typeof root === "object") {
      flattened.push({
        ...root,
        chapterNumber: Number(root.chapterNumber || 0),
        chapterTitle: String(root.chapterTitle || root.title || ""),
        kind: String(root.kind || ""),
      });
    }
  });
  return flattened.filter((entry) => String(entry.path || "").trim());
}

function persistedFilesScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState("filesInitialized", false);
  const [loading, setLoading] = ctx.useState("filesLoading", true);
  const [error, setError] = ctx.useState("filesError", "");
  const [payload, setPayload] = ctx.useState("filesPayload", null);
  const [offset, setOffset] = ctx.useState("filesOffset", 0);
  const [opening, setOpening] = ctx.useState("filesOpening", false);

  const loadPage = async (nextOffset) => {
    setLoading(true);
    setError("");
    try {
      const result = await callWithTimeout(
        () => callHistoryTool(ctx, "list_persisted_files", {
          offset: Math.max(0, Number(nextOffset || 0)),
          limit: PAGE_SIZE,
        }),
        english ? "Loading timed out" : "加载超时",
      );
      setPayload(result || {});
      setOffset(Math.max(0, Number(nextOffset || 0)));
    } catch (loadError) {
      setError(toErrorText(loadError));
    } finally {
      setLoading(false);
    }
  };

  const openFile = async (entry) => {
    const path = String(entry && entry.path || "").trim();
    if (!path || opening) {
      return;
    }
    setOpening(true);
    await Promise.resolve(ctx.setEnv(FILE_VIEW_PATH_ENV_KEY, path));
    await Promise.resolve(
      ctx.setEnv(FILE_VIEW_NAME_ENV_KEY, String(entry && entry.name || "")),
    );
    await Promise.resolve(
      ctx.setEnv(
        FILE_VIEW_RELATIVE_ENV_KEY,
        String(entry && entry.relativePath || ""),
      ),
    );
    await Promise.resolve(
      ctx.setEnv(
        FILE_VIEW_READONLY_ENV_KEY,
        entry && entry.readOnly === false ? "false" : "true",
      ),
    );
    await Promise.resolve(ctx.navigate(FILE_VIEW_ROUTE));
    setOpening(false);
  };

  const entries = flattenEntries(payload);
  const nextOffset = Number(payload && payload.nextOffset);
  const hasNext = Number.isInteger(nextOffset) && nextOffset > offset;
  const bookName = String(payload && payload.book || "").trim();
  const totalCount = Number(payload && payload.total || 0);
  const staleCatalog = !!(payload && payload.staleCatalog);
  const noBook = !!(payload && payload.noBook);

  const bookEntries = entries.filter((entry) => entry.kind === "book");
  const companionEntries = entries.filter((entry) => entry.kind === "companion");
  const chapterEntries = entries.filter((entry) => entry.kind === "chapter");
  const catalogEntries = entries.filter((entry) => entry.kind === "catalog");

  const sectionHeader = (text) =>
    UI.Text({
      text,
      style: "labelLarge",
      color: colors.primary,
    });

  const fileCard = (entry, index) => {
    const path = String(entry.path || "").trim();
    const readOnly = entry.readOnly !== false || entry.name === "content.md";
    const relativePath = String(entry.relativePath || "").trim();
    return UI.Card(
      {
        key: `persisted_file_${offset}_${index}_${path}`,
        fillMaxWidth: true,
        containerColor: colors.surface,
        modifier: ctx.Modifier.fillMaxWidth().clickable(() => openFile(entry)),
      },
      UI.Column({ fillMaxWidth: true, padding: 12, spacing: 4 }, [
        UI.Row({
          fillMaxWidth: true,
          horizontalArrangement: "spaceBetween",
          verticalAlignment: "center",
        }, [
          UI.Text({
            text: entryLabel(entry, english),
            style: "bodyMedium",
            color: colors.onSurface,
            maxLines: 2,
            overflow: "ellipsis",
          }),
          UI.Text({
            text: readOnly
              ? (english ? "Read" : "只读")
              : (english ? "Editable" : "可编辑"),
            style: "labelSmall",
            color: readOnly ? colors.onSurfaceVariant : colors.primary,
          }),
        ]),
        relativePath
          ? UI.Text({
              text: relativePath,
              style: "labelSmall",
              color: colors.onSurfaceVariant,
              maxLines: 1,
              overflow: "ellipsis",
            })
          : null,
      ]),
    );
  };

  const children = [];
  if (bookName) {
    children.push(
      UI.Row({
        fillMaxWidth: true,
        horizontalArrangement: "spaceBetween",
        verticalAlignment: "center",
      }, [
        UI.Text({
          text: bookName,
          style: "titleMedium",
          color: colors.onSurface,
          maxLines: 1,
          overflow: "ellipsis",
        }),
        totalCount > 0
          ? UI.Text({
              text: english ? `${totalCount} files` : `${totalCount} 个文件`,
              style: "labelMedium",
              color: colors.onSurfaceVariant,
            })
          : null,
      ]),
    );
  }

  if (loading) {
    children.push(
      UI.Row({ fillMaxWidth: true, spacing: 10, verticalAlignment: "center" }, [
        UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
        UI.Text({ text: english ? "Loading file list…" : "正在加载文件列表…" }),
      ]),
    );
  } else if (error) {
    children.push(
      UI.Card({ fillMaxWidth: true, containerColor: colors.errorContainer }, [
        UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          UI.Text({ text: error, color: colors.onErrorContainer }),
          UI.OutlinedButton({
            onClick: () => loadPage(offset),
          }, UI.Text({ text: english ? "Retry" : "重试" })),
        ]),
      ]),
    );
  }
  if (!loading && !error && staleCatalog) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.secondaryContainer },
        UI.Text({
          padding: 12,
          color: colors.onSecondaryContainer,
          text: english
            ? noBook
              ? "No book is available yet. Open a book in Legado, or select one in the reading companion panel."
              : "Legado is not responding right now; the list comes from local files and may not include newly added chapters."
            : noBook
              ? "还没有可用的书籍，请在 Legado 打开一本书，或在阅读伴侣面板中选择书籍。"
              : "当前未能连接 Legado，文件列表来自本地目录，新增章节可能尚未显示。",
        }),
      ),
    );
  }

  if (!loading && !error && noBook) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({
          padding: 16,
          color: colors.onSurfaceVariant,
          text: english
            ? "No saved book files yet."
            : "还没有已保存的书籍文件。",
        }),
      ),
    );
  } else if (!loading && !error && entries.length === 0) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({
          padding: 16,
          color: colors.onSurfaceVariant,
          text: english ? "No persisted files on this page." : "这一页没有已保存文件。",
        }),
      ),
    );
  } else if (!loading && !error) {
    if (bookEntries.length > 0) {
      children.push(sectionHeader(english ? "Book documents" : "书籍文档"));
      bookEntries.forEach((entry, index) =>
        children.push(fileCard(entry, `book_${index}`)),
      );
    }
    if (companionEntries.length > 0) {
      children.push(sectionHeader(english ? "Companion memory" : "角色记忆"));
      companionEntries.forEach((entry, index) =>
        children.push(fileCard(entry, `companion_${index}`)),
      );
    }
    if (chapterEntries.length > 0) {
      children.push(sectionHeader(english ? "Chapter files" : "章节文件"));
      chapterEntries.forEach((entry, index) =>
        children.push(fileCard(entry, `chapter_${index}`)),
      );
    }
    if (catalogEntries.length > 0) {
      children.push(sectionHeader(english ? "Chapter catalogs" : "章节目录"));
      catalogEntries.forEach((entry, index) =>
        children.push(fileCard(entry, `catalog_${index}`)),
      );
    }
  }

  children.push(
    UI.Row({ fillMaxWidth: true, spacing: 8 }, [
      UI.OutlinedButton({
        weight: 1,
        enabled: !loading && offset > 0,
        onClick: () => loadPage(Math.max(0, offset - PAGE_SIZE)),
      }, UI.Text({ text: english ? "Previous" : "上一页" })),
      UI.OutlinedButton({
        weight: 1,
        enabled: !loading && hasNext,
        onClick: () => loadPage(nextOffset),
      }, UI.Text({ text: english ? "Next" : "下一页" })),
    ]),
  );

  if (opening) {
    children.push(
      UI.Text({
        text: english ? "Opening file…" : "正在打开文件…",
        style: "bodySmall",
        color: colors.onSurfaceVariant,
      }),
    );
  }

  return UI.Box(
    {
      fillMaxSize: true,
      topBarTitle: UI.Text({
        text: english ? "Saved book files" : "已保存书籍文件",
        maxLines: 1,
      }),
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          await loadPage(0);
        }
      },
    },
    UI.LazyColumn({ fillMaxSize: true, padding: 16, spacing: 10 }, children),
  );
}

module.exports = persistedFilesScreen;
module.exports.default = persistedFilesScreen;
