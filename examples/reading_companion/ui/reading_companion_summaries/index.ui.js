const {
  callHistoryTool,
  toErrorText,
  useEnglishLocale,
  callWithTimeout,
} = require("../history_shared.js");

function summariesScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState("summariesInitialized", false);
  const [loading, setLoading] = ctx.useState("summariesLoading", true);
  const [error, setError] = ctx.useState("summariesError", "");
  const [payload, setPayload] = ctx.useState("summariesPayload", null);

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      setPayload(
        await callWithTimeout(
          () => callHistoryTool(ctx, "list_summary_files", {}),
          english ? "Loading timed out" : "加载超时",
        ),
      );
    } catch (loadError) {
      setError(toErrorText(loadError));
    } finally {
      setLoading(false);
    }
  };

  const summaries =
    payload && Array.isArray(payload.summaries) ? payload.summaries : [];
  const staleCatalog = !!(payload && payload.staleCatalog);
  const noBook = !!(payload && payload.noBook);
  const children = [];
  if (loading) {
    children.push(
      UI.Row({ padding: 16, spacing: 10, verticalAlignment: "center" }, [
        UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
        UI.Text({ text: english ? "Loading summaries…" : "正在加载章节摘要…" }),
      ]),
    );
  } else if (error) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.errorContainer },
        UI.Column({ padding: 16, spacing: 10 }, [
          UI.Text({ text: error, color: colors.onErrorContainer }),
          UI.Text({
            text: english
              ? "Please make sure the book is open in Legado, then retry."
              : "请确认 Legado 中已打开正在阅读的书，然后重试。",
            style: "bodySmall",
            color: colors.onErrorContainer,
          }),
          UI.OutlinedButton({ onClick: load }, UI.Text({ text: english ? "Retry" : "重试" })),
        ]),
      ),
    );
  }
  if (!loading && !error && staleCatalog) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.secondaryContainer },
        UI.Text({
          padding: 16,
          color: colors.onSecondaryContainer,
          text: english
            ? noBook
              ? "No book is available yet. Open a book in Legado, or select one in the reading companion panel."
              : "Legado is not responding right now; the list below comes from local files, so the chapter catalog may not be the latest."
            : noBook
              ? "还没有可用的书籍，请在 Legado 打开一本书，或在阅读伴侣面板中选择书籍。"
              : "当前未能连接 Legado，以下内容来自本地已保存文件，章节目录可能不是最新的。",
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
            ? "No persisted summaries yet."
            : "还没有已持久化的章节摘要。",
        }),
      ),
    );
  } else if (!loading && !error && summaries.length === 0) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({
          padding: 16,
          color: colors.onSurfaceVariant,
          text: english
            ? "No persisted summaries yet. Use the manual summary batch in the reading companion panel to generate them."
            : "还没有已持久化的章节摘要，可在阅读伴侣面板手动生成。",
        }),
      ),
    );
  } else if (!loading && !error) {
    summaries.forEach((item) => {
      children.push(
        UI.Card(
          {
            key: String(item.chapterRef || item.chapterNumber),
            fillMaxWidth: true,
            containerColor: colors.surface,
          },
          UI.Column({ padding: 16, spacing: 6 }, [
            UI.Text({
              text: english
                ? `Chapter ${Number(item.chapterNumber || 0)} · ${String(item.chapterTitle || "")}`
                : `第 ${Number(item.chapterNumber || 0)} 章 · ${String(item.chapterTitle || "")}`,
              style: "titleMedium",
              color: colors.onSurface,
            }),
            UI.Text({
              text: String(item.summary || ""),
              style: "bodyMedium",
              color: colors.onSurface,
            }),
            UI.Text({
              text: "summary.md",
              style: "labelSmall",
              color: colors.onSurfaceVariant,
            }),
          ]),
        ),
      );
    });
  }

  return UI.Box(
    {
      fillMaxSize: true,
      topBarTitle: UI.Text({
        text: english ? "Chapter summaries" : "章节摘要",
        maxLines: 1,
      }),
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          await load();
        }
      },
    },
    UI.LazyColumn({ fillMaxSize: true, padding: 16, spacing: 12 }, children),
  );
}

module.exports = summariesScreen;
module.exports.default = summariesScreen;
