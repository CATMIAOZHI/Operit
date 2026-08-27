const {
  DETAIL_ROUTE,
  RUN_ID_ENV_KEY,
  callHistoryTool,
  formatDate,
  formatDuration,
  statusColor,
  statusLabel,
  toErrorText,
  triggerLabel,
  useEnglishLocale,
} = require("../history_shared.js");

function historyScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState(
    "historyInitialized",
    false,
  );
  const [loading, setLoading] = ctx.useState("historyLoading", true);
  const [error, setError] = ctx.useState("historyError", "");
  const [runs, setRuns] = ctx.useState("historyRuns", []);

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      const result = await callHistoryTool(ctx, "auto_commentary_history", {
        limit: 50,
      });
      setRuns(Array.isArray(result && result.runs) ? result.runs : []);
    } catch (loadError) {
      setError(toErrorText(loadError));
    } finally {
      setLoading(false);
    }
  };

  const openRun = async (run) => {
    const runId = Number(run && run.runId || 0);
    if (!Number.isFinite(runId) || runId <= 0) {
      return;
    }
    await Promise.resolve(ctx.setEnv(RUN_ID_ENV_KEY, String(runId)));
    await Promise.resolve(ctx.navigate(DETAIL_ROUTE));
  };

  const title = english ? "Commentary history" : "段评历史";
  const empty = english ? "No commentary task records yet." : "还没有段评任务记录。";
  const errorTitle = english ? "Could not load history" : "加载历史失败";
  const refresh = english ? "Refresh" : "刷新";
  const safeHint = english
    ? "Only task metadata is shown here. Unread chapter text and locked comments are never displayed."
    : "这里只显示任务元数据，不展示未读正文或尚未解锁的段评。";

  const children = [];
  if (loading) {
    children.push(
      UI.Row(
        {
          fillMaxWidth: true,
          spacing: 10,
          verticalAlignment: "center",
          padding: 16,
        },
        [
          UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
          UI.Text({
            text: english ? "Loading records…" : "正在加载记录…",
            color: colors.onSurfaceVariant,
          }),
        ],
      ),
    );
  } else if (error) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.errorContainer },
        UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          UI.Text({ text: errorTitle, style: "titleMedium", color: colors.onErrorContainer }),
          UI.Text({ text: error, style: "bodySmall", color: colors.onErrorContainer }),
          UI.OutlinedButton({ onClick: load }, UI.Text({ text: refresh })),
        ]),
      ),
    );
  } else if (runs.length === 0) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({ text: empty, color: colors.onSurfaceVariant, padding: 16 }),
      ),
    );
  } else {
    runs.forEach((run) => {
      const runStatus = String(run && run.status || "").trim();
      const metadata = [
        triggerLabel(run && run.trigger, english),
        formatDate(run && run.startedAt, english),
      ];
      if (Number(run && run.durationMs || 0) > 0) {
        metadata.push(formatDuration(run.durationMs, english));
      }
      if (Number(run && run.commentCount || 0) > 0) {
        metadata.push(
          english
            ? `${run.commentCount} comments`
            : `${run.commentCount} 条段评`,
        );
      }
      children.push(
        UI.Card(
          {
            key: `run_${String(run && run.runId || "")}`,
            fillMaxWidth: true,
            containerColor: colors.surface,
            modifier: ctx.Modifier.fillMaxWidth().clickable(() => openRun(run)),
          },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 6 }, [
            UI.Row(
              { fillMaxWidth: true, horizontalArrangement: "spaceBetween", verticalAlignment: "center" },
              [
                UI.Column({ weight: 1, spacing: 2 }, [
                  UI.Text({
                    text: String(run && run.bookName || "") ||
                      (english ? "Legado book" : "Legado 书籍"),
                    style: "titleMedium",
                    color: colors.onSurface,
                    maxLines: 1,
                    overflow: "ellipsis",
                  }),
                  UI.Text({
                    text: run && run.chapterNumber
                      ? english
                        ? `Target chapter ${run.chapterNumber}`
                        : `目标第 ${run.chapterNumber} 章`
                      : (english ? "Target unavailable" : "目标章节未知"),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                  }),
                ]),
                UI.Text({
                  text: statusLabel(runStatus, english),
                  style: "labelLarge",
                  color: colors[statusColor(runStatus)] || colors.onSurfaceVariant,
                }),
              ],
            ),
            UI.Text({
              text: metadata.join(" · "),
              style: "bodySmall",
              color: colors.onSurfaceVariant,
              maxLines: 2,
              overflow: "ellipsis",
            }),
            UI.Text({
              text: english ? "Open details ›" : "查看详情 ›",
              style: "labelMedium",
              color: colors.primary,
            }),
          ]),
        ),
      );
    });
  }

  children.push(
    UI.Text({
      text: safeHint,
      style: "bodySmall",
      color: colors.onSurfaceVariant,
      padding: 4,
    }),
  );

  return UI.Box(
    {
      fillMaxSize: true,
      topBarTitle: UI.Text({ text: title, maxLines: 1, overflow: "ellipsis" }),
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          await load();
        }
      },
    },
    UI.LazyColumn({
      fillMaxSize: true,
      padding: 16,
      spacing: 12,
    }, children),
  );
}

module.exports = historyScreen;
module.exports.default = historyScreen;
